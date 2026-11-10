//! Live connections by profile id (`connections/DatabaseRegistry.kt`).
//!
//! Connecting runs on tokio (the registry holds the runtime's [`Handle`]), so a caller that goes away doesn't abort
//! the attempt, and every caller asking while it runs shares it. The status of each profile is read synchronously by
//! the UI; a [`watch`] revision ticks whenever one changes, so the UI knows when to draw again without polling.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use futures_util::FutureExt;
use futures_util::future::{BoxFuture, Shared};
use tg_core::TableGripPaths;
use tg_core::connection::ConnectionConfig;
use tg_data::datafile::DataFileConnection;
use tg_data::datafile::DataFileStore;
use tg_driver::{
    Connection, DocumentConnection, KeyValueConnection, RelationalConnection, Secrets,
};
use tokio::runtime::Handle;
use tokio::sync::watch;

/// A live connection: the driver's, plus the data file behind it when the profile is a CSV, Excel or JSON file.
#[derive(Clone)]
pub struct Live {
    pub connection: Connection,
    pub data_file: Option<Arc<DataFileConnection>>,
}

impl Live {
    pub fn relational(&self) -> Option<Arc<dyn RelationalConnection>> {
        match &self.connection {
            Connection::Relational(c) => Some(c.clone()),
            _ => None,
        }
    }

    pub fn key_value(&self) -> Option<Arc<dyn KeyValueConnection>> {
        match &self.connection {
            Connection::KeyValue(c) => Some(c.clone()),
            _ => None,
        }
    }

    pub fn documents(&self) -> Option<Arc<dyn DocumentConnection>> {
        match &self.connection {
            Connection::Document(c) => Some(c.clone()),
            _ => None,
        }
    }

    fn close(&self) {
        if let Some(file) = &self.data_file {
            file.close();
        }
    }
}

/// Where a profile's connection stands, for the explorer's and the status bar's dots.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Status {
    Disconnected,
    /// `since` is when the attempt started, so the UI can say how long it has been trying.
    Connecting {
        since: Instant,
    },
    Connected,
    Failed(String),
}

/// Reads a profile's password and SSH secret. Called on a blocking thread: the OS keychain may block or prompt.
pub type SecretsSource = Arc<dyn Fn(&ConnectionConfig) -> Secrets + Send + Sync>;

type Attempt = Shared<BoxFuture<'static, Result<Live, String>>>;

enum Slot {
    Connecting {
        attempt: Attempt,
        since: Instant,
        generation: u64,
    },
    Connected(Live),
    Failed(String),
}

struct Inner {
    slots: HashMap<String, Slot>,
    generation: u64,
}

/// Live connections by profile id. Cheap to clone; clones share the connections.
#[derive(Clone)]
pub struct Registry {
    inner: Arc<Mutex<Inner>>,
    runtime: Handle,
    paths: TableGripPaths,
    secrets: SecretsSource,
    revision: Arc<watch::Sender<u64>>,
}

impl Registry {
    pub fn new(runtime: Handle, paths: TableGripPaths, secrets: SecretsSource) -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                slots: HashMap::new(),
                generation: 0,
            })),
            runtime,
            paths,
            secrets,
            revision: Arc::new(watch::channel(0).0),
        }
    }

    /// Ticks whenever a status changes.
    pub fn revisions(&self) -> watch::Receiver<u64> {
        self.revision.subscribe()
    }

    pub fn status(&self, id: &str) -> Status {
        match self.inner.lock().unwrap().slots.get(id) {
            None => Status::Disconnected,
            Some(Slot::Connecting { since, .. }) => Status::Connecting { since: *since },
            Some(Slot::Connected(_)) => Status::Connected,
            Some(Slot::Failed(message)) => Status::Failed(message.clone()),
        }
    }

    /// The live connection if there is one; never connects.
    pub fn live(&self, id: &str) -> Option<Live> {
        match self.inner.lock().unwrap().slots.get(id) {
            Some(Slot::Connected(live)) => Some(live.clone()),
            _ => None,
        }
    }

    /// Ids of every profile that is connected or connecting.
    pub fn active(&self) -> Vec<String> {
        let inner = self.inner.lock().unwrap();
        inner
            .slots
            .iter()
            .filter(|(_, s)| !matches!(s, Slot::Failed(_)))
            .map(|(id, _)| id.clone())
            .collect()
    }

    /// The live connection, connecting first if need be. Concurrent callers share one attempt, and dropping the
    /// returned future doesn't abort it. The future runs on any executor.
    pub fn connection(
        &self,
        config: &ConnectionConfig,
    ) -> BoxFuture<'static, Result<Live, String>> {
        let mut inner = self.inner.lock().unwrap();
        match inner.slots.get(&config.id) {
            Some(Slot::Connected(live)) => {
                return futures_util::future::ready(Ok(live.clone())).boxed();
            }
            Some(Slot::Connecting { attempt, .. }) => return attempt.clone().boxed(),
            _ => {}
        }
        inner.generation += 1;
        let generation = inner.generation;
        let task = {
            let (config, paths, secrets, registry) = (
                config.clone(),
                self.paths.clone(),
                self.secrets.clone(),
                self.clone(),
            );
            self.runtime.spawn(async move {
                let result = open(&config, secrets, &paths).await;
                registry.finish(&config.id, generation, &result);
                result
            })
        };
        let attempt: Attempt = async move { task.await.unwrap_or_else(|e| Err(e.to_string())) }
            .boxed()
            .shared();
        inner.slots.insert(
            config.id.clone(),
            Slot::Connecting {
                attempt: attempt.clone(),
                since: Instant::now(),
                generation,
            },
        );
        drop(inner);
        self.bump();
        attempt.boxed()
    }

    /// Records how the attempt `generation` ended, unless the profile was disconnected (or reconnected) since.
    fn finish(&self, id: &str, generation: u64, result: &Result<Live, String>) {
        let mut inner = self.inner.lock().unwrap();
        let current = matches!(inner.slots.get(id), Some(Slot::Connecting { generation: g, .. }) if *g == generation);
        if !current {
            if let Ok(live) = result {
                live.close();
            }
            return;
        }
        let slot = match result {
            Ok(live) => Slot::Connected(live.clone()),
            Err(message) => Slot::Failed(message.clone()),
        };
        inner.slots.insert(id.to_owned(), slot);
        drop(inner);
        self.bump();
    }

    /// Closes the profile's connection (or forgets its attempt or failure).
    pub fn disconnect(&self, id: &str) {
        let removed = self.inner.lock().unwrap().slots.remove(id);
        if let Some(Slot::Connected(live)) = &removed {
            live.close();
        }
        if removed.is_some() {
            self.bump();
        }
    }

    pub fn close_all(&self) {
        let ids: Vec<String> = self.inner.lock().unwrap().slots.keys().cloned().collect();
        for id in ids {
            self.disconnect(&id);
        }
    }

    fn bump(&self) {
        self.revision.send_modify(|r| *r += 1);
    }
}

/// Reads the secrets off the async threads, then opens the profile: a data file through its working copy, anything
/// else through its engine.
async fn open(
    config: &ConnectionConfig,
    secrets: SecretsSource,
    paths: &TableGripPaths,
) -> Result<Live, String> {
    if config.db_type.is_data_file() {
        let file = DataFileStore::connect(config, paths)
            .await
            .map_err(|e| e.to_string())?;
        return Ok(Live {
            connection: Connection::Relational(file.clone()),
            data_file: Some(file),
        });
    }
    let secrets = {
        let config = config.clone();
        tokio::task::spawn_blocking(move || secrets(&config))
            .await
            .map_err(|e| e.to_string())?
    };
    let connection = tg_driver::connect(config, &secrets, paths)
        .await
        .map_err(|e| e.to_string())?;
    Ok(Live {
        connection,
        data_file: None,
    })
}

/// "Test Connection": a single connection, closed again, with what it learned. Data files are read without a
/// working copy.
pub async fn test(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<tg_driver::TestOutcome, String> {
    if config.db_type.is_data_file() {
        let config = config.clone();
        let server = tokio::task::spawn_blocking(move || DataFileStore::test(&config))
            .await
            .map_err(|e| e.to_string())?
            .map_err(|e| e.to_string())?;
        return Ok(tg_driver::TestOutcome {
            suggested_type: None,
            server: Some(server),
        });
    }
    tg_driver::test(config, secrets, paths)
        .await
        .map_err(|e| e.to_string())
}
