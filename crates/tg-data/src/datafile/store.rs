//! A data file open as a [`RelationalConnection`] (`datafile/DataFileStore.kt`). Everything reads and runs on the
//! [`WorkingCopy`] through tg-driver's SQLite engine, so tables, SQL consoles, filters, editing and imports work as on
//! any database; writes made through this connection are saved to the file as soon as they commit, and the sync's
//! polling catches the rest (consoles).
//!
//! tg-driver refuses data-file profiles (it doesn't depend on tg-data); the app opens them here.

use super::sync::{DataFileSync, FileStamp};
use super::working_copy::WorkingCopy;
use super::{DataFileError, DataFileFormat, Result};
use async_trait::async_trait;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tg_core::connection::{ConnectionConfig, DataFileOptions, Environment};
use tg_core::paths::TableGripPaths;
use tg_driver::result::{Cell, PageRequest, Progress, Rows, TableQuery};
use tg_driver::{DriverError, QuerySession, RelationalConnection, RowSink, Secrets};
use tg_sql::SqlDialect;
use tg_sql::ddl::DdlScript;
use tg_sql::edit::ChangeStatement;
use tg_sql::model::{
    ColumnInfo, DatabaseType, Namespace, TableDetails, TableInfo, TableRef, TableRelation,
    TableStructure,
};
use tg_sql::schema::SchemaSnapshot;

type DriverResult<T> = std::result::Result<T, DriverError>;

pub struct DataFileConnection {
    config: ConnectionConfig,
    database: Arc<dyn RelationalConnection>,
    /// The file's side: its state, saving, reloading.
    pub sync: Arc<DataFileSync>,
    path: PathBuf,
}

impl DataFileConnection {
    /// Saves on a blocking thread, as the sync does file and SQLite I/O.
    async fn save(&self) {
        let sync = self.sync.clone();
        let _ = tokio::task::spawn_blocking(move || sync.save(false)).await;
    }

    /// The tables' names in the file's own order (a workbook's sheets as its tabs list them), then those added since.
    pub async fn table_order(&self) -> DriverResult<Vec<String>> {
        let mut session = self.database.open_session().await?;
        let result = session
            .execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY rowid",
                Default::default(),
            )
            .await?;
        Ok(match result {
            tg_driver::QueryResult::Rows(rows) => rows
                .rows
                .into_iter()
                .filter_map(|r| r.into_iter().next().flatten())
                .collect(),
            _ => Vec::new(),
        })
    }

    /// Saves what consoles committed since the last tick, then deletes the working copy.
    pub fn close(&self) {
        self.sync.close();
    }
}

impl Drop for DataFileConnection {
    fn drop(&mut self) {
        self.sync.close();
        WorkingCopy::delete(&self.path);
    }
}

#[async_trait]
impl RelationalConnection for DataFileConnection {
    fn config(&self) -> &ConnectionConfig {
        &self.config
    }

    fn dialect(&self) -> SqlDialect {
        self.database.dialect()
    }

    async fn namespaces(&self) -> DriverResult<Vec<Namespace>> {
        self.database.namespaces().await
    }

    async fn tables(&self, namespace: &Namespace) -> DriverResult<Vec<TableInfo>> {
        self.database.tables(namespace).await
    }

    async fn table_details(&self, table: &TableRef) -> DriverResult<TableDetails> {
        self.database.table_details(table).await
    }

    async fn table_columns(&self, table: &TableRef) -> DriverResult<TableDetails> {
        self.database.table_columns(table).await
    }

    async fn table_structure(&self, table: &TableRef) -> DriverResult<TableStructure> {
        self.database.table_structure(table).await
    }

    async fn columns_of(
        &self,
        namespace: &Namespace,
    ) -> DriverResult<Vec<(String, Vec<ColumnInfo>)>> {
        self.database.columns_of(namespace).await
    }

    async fn relations(&self, table: &TableRef) -> DriverResult<Vec<TableRelation>> {
        self.database.relations(table).await
    }

    async fn sample_values(
        &self,
        table: &TableRef,
        column: &str,
        prefix: &str,
        limit: usize,
    ) -> DriverResult<Vec<String>> {
        self.database
            .sample_values(table, column, prefix, limit)
            .await
    }

    async fn fetch_page(&self, request: &PageRequest<'_>) -> DriverResult<Rows> {
        self.database.fetch_page(request).await
    }

    async fn export_table(
        &self,
        query: &TableQuery<'_>,
        sink: &mut dyn RowSink,
        progress: Progress<'_>,
    ) -> DriverResult<u64> {
        self.database.export_table(query, sink, progress).await
    }

    async fn count_rows(
        &self,
        query: &TableQuery<'_>,
        timeout: Option<Duration>,
    ) -> DriverResult<u64> {
        self.database.count_rows(query, timeout).await
    }

    async fn apply_changes(&self, statements: &[ChangeStatement]) -> DriverResult<u64> {
        let n = self.database.apply_changes(statements).await?;
        self.save().await;
        Ok(n)
    }

    async fn schema_snapshot(
        &self,
        namespace: &Namespace,
        progress: &(dyn Fn(usize, usize) + Send + Sync),
    ) -> DriverResult<SchemaSnapshot> {
        self.database.schema_snapshot(namespace, progress).await
    }

    async fn execute_script(
        &self,
        script: &DdlScript,
        progress: &(dyn Fn(usize) + Send + Sync),
    ) -> DriverResult<usize> {
        let n = self.database.execute_script(script, progress).await?;
        self.save().await;
        Ok(n)
    }

    async fn insert_rows(
        &self,
        table: &TableRef,
        columns: &[ColumnInfo],
        rows: &mut (dyn Iterator<Item = Vec<Cell>> + Send),
        describe: &(dyn Fn(u64) -> String + Send + Sync),
        progress: Progress<'_>,
    ) -> DriverResult<u64> {
        let n = self
            .database
            .insert_rows(table, columns, rows, describe, progress)
            .await?;
        self.save().await;
        Ok(n)
    }

    async fn open_session(&self) -> DriverResult<Box<dyn QuerySession>> {
        self.database.open_session().await
    }

    async fn backup_into(&self, _target: &Path) -> DriverResult<()> {
        Err(DriverError::Unsupported(format!(
            "{} is a file of its own; copy it to back it up",
            self.config.db_type.display_name
        )))
    }
}

/// Opens data files as connections: reads the file into a fresh working copy, then serves it like a SQLite database.
pub struct DataFileStore;

impl DataFileStore {
    /// Reads the profile's file into a working copy and connects to it.
    pub async fn connect(
        config: &ConnectionConfig,
        paths: &TableGripPaths,
    ) -> Result<Arc<DataFileConnection>> {
        let format = DataFileFormat::of(&config.db_type)?;
        let file = Self::file_of(config)?;
        let options = config.data_file.clone().unwrap_or_default();
        let path = WorkingCopy::new_path(&paths.working_copies(), &config.id)?;
        let opened = {
            let (config, path, file, options) =
                (config.clone(), path.clone(), file.clone(), options.clone());
            tokio::task::spawn_blocking(move || {
                open_blocking(&config, format, &file, &options, &path)
            })
            .await
            .map_err(|e| DataFileError(e.to_string()))
        };
        let (sync, read_only) = match opened.and_then(|r| r) {
            Ok(v) => v,
            Err(e) => {
                WorkingCopy::delete(&path);
                return Err(e);
            }
        };
        let inner = working_copy_config(config, &path, read_only);
        let database =
            match tg_driver::engine::connect_relational(&inner, &Secrets::default(), paths).await {
                Ok(d) => d,
                Err(e) => {
                    sync.close();
                    WorkingCopy::delete(&path);
                    return Err(DataFileError(e.to_string()));
                }
            };
        sync.start();
        Ok(Arc::new(DataFileConnection {
            config: config.clone(),
            database,
            sync,
            path,
        }))
    }

    /// Reads the file's tables without loading them, e.g. for "Test Connection"; describes what it found.
    pub fn test(config: &ConnectionConfig) -> Result<String> {
        let file = Self::file_of(config)?;
        let document = DataFileFormat::of(&config.db_type)?
            .open(&file, &config.data_file.clone().unwrap_or_default())?;
        let tables = document.tables();
        let summary = match tables.len() {
            0 => "no tables yet".to_owned(),
            1 => format!("{} columns", tables[0].columns.len()),
            _ => format!(
                "{} tables: {}",
                tables.len(),
                tables
                    .iter()
                    .map(|t| t.name.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
        };
        let read_only = document
            .read_only_reason()
            .map(|r| format!(" Opens read-only: {r}"))
            .unwrap_or_default();
        Ok(format!(
            "{} ({}), {summary}.{read_only}",
            file.file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default(),
            config.db_type.display_name
        ))
    }

    /// The profile's file; it must exist.
    pub fn file_of(config: &ConnectionConfig) -> Result<PathBuf> {
        if config.database.trim().is_empty() {
            return Err(DataFileError(format!(
                "Choose the {} to open.",
                config.db_type.display_name
            )));
        }
        let file = Self::path_of(&config.database);
        if !file.is_file() {
            return Err(DataFileError(format!(
                "{} doesn't exist or isn't a file.",
                file.display()
            )));
        }
        Ok(file)
    }

    /// A profile's file path as typed, with `~` expanded, absolute and normalized.
    pub fn path_of(database: &str) -> PathBuf {
        let raw = database.trim();
        let expanded = match raw.strip_prefix("~/") {
            Some(rest) => tg_core::paths::user_home().join(rest),
            None => PathBuf::from(raw),
        };
        let absolute = if expanded.is_absolute() {
            expanded
        } else {
            std::env::current_dir().unwrap_or_default().join(expanded)
        };
        let mut out = PathBuf::new();
        for part in absolute.components() {
            match part {
                std::path::Component::ParentDir => {
                    out.pop();
                }
                std::path::Component::CurDir => {}
                p => out.push(p),
            }
        }
        out
    }

    /// A profile for a dropped or chosen file: its format from the extension, named after it. None if no format
    /// claims it.
    pub fn profile_for(file: &Path, id: &str, group: &str) -> Option<ConnectionConfig> {
        let format = DataFileFormat::detect(file)?;
        let name = file.file_name()?.to_string_lossy().into_owned();
        let mut config = ConnectionConfig::new(id, &name, format.db_type());
        config.host = String::new();
        config.database = std::path::absolute(file)
            .unwrap_or_else(|_| file.to_owned())
            .to_string_lossy()
            .into_owned();
        config.environment = Environment::Local;
        config.group = group.to_owned();
        Some(config)
    }
}

fn open_blocking(
    config: &ConnectionConfig,
    format: DataFileFormat,
    file: &Path,
    options: &DataFileOptions,
    path: &Path,
) -> Result<(Arc<DataFileSync>, bool)> {
    // The stamp is taken before reading, so a change made while the file is read is noticed afterwards.
    let stamp = FileStamp::of(file);
    let mut document = format.open(file, options)?;
    let read_only_reason = document.read_only_reason().or_else(|| {
        config
            .read_only
            .then(|| "The connection is read-only.".to_owned())
    });
    let mut copy = WorkingCopy::open(path)?;
    copy.load(document.as_mut(), &[], &mut |_| {})?;
    let read_only = read_only_reason.is_some();
    let sync = DataFileSync::new(
        file,
        format,
        options.clone(),
        copy,
        document,
        read_only_reason,
        stamp,
    )?;
    Ok((sync, read_only))
}

/// What the SQLite connection over the working copy is opened with. WAL lets the sync read a consistent snapshot
/// while a console writes; the timeout rides out each other's locks.
fn working_copy_config(
    config: &ConnectionConfig,
    path: &Path,
    read_only: bool,
) -> ConnectionConfig {
    let mut inner = ConnectionConfig::new(
        &format!("{}#working-copy", config.id),
        &config.name,
        DatabaseType::SQLITE,
    );
    inner.host = String::new();
    inner.database = path.to_string_lossy().into_owned();
    inner.read_only = read_only;
    inner.properties.insert("journal_mode".into(), "WAL".into());
    inner
        .properties
        .insert("busy_timeout".into(), "10000".into());
    inner
}
