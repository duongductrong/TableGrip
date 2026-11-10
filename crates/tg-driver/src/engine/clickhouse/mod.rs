//! ClickHouse over its HTTP interface (reqwest, rustls on ring). Results come as `TabSeparatedWithNamesAndTypes`, so
//! cells are the server's own text; parameters are inlined as literals. HTTP is stateless: explorer and tables share
//! one client, and a console is an HTTP session (`session_id`) so its settings and temporary tables last. Each request
//! carries a `query_id`; dropping its future sends `KILL QUERY` for it. ClickHouse has no transactions.

mod catalog;
mod conn;
mod types;

use crate::engine::{Secrets, host_and_port, open_tunnel};
use crate::error::{DriverError, QueryError, Result};
use crate::relational::{RelationalConnection, SqlDatabase};
use crate::sql::{ConnSource, SqlConn};
use async_trait::async_trait;
use conn::ChConn;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use tg_core::connection::ConnectionConfig;
use tg_core::paths::TableGripPaths;

/// What every request needs: where the server is, who's asking, and which database.
pub(crate) struct Http {
    client: reqwest::Client,
    url: String,
    user: String,
    password: String,
    database: String,
    read_only: bool,
}

static NEXT_ID: AtomicU64 = AtomicU64::new(0);

/// An id for a query or session, unique in this process and unlikely to clash with other clients'.
pub(crate) fn new_id(kind: &str) -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_nanos());
    format!(
        "tablegrip-{kind}-{nanos:x}-{}",
        NEXT_ID.fetch_add(1, Ordering::Relaxed)
    )
}

/// A server error as ClickHouse words it (`Code: 60. DB::Exception: …`), with its code.
pub(crate) fn server_error(text: &str) -> DriverError {
    let text = text.trim();
    let code = text
        .strip_prefix("Code: ")
        .and_then(|r| r.split(['.', ' ']).next())
        .and_then(|c| c.parse::<i32>().ok());
    // 394: QUERY_WAS_CANCELLED.
    if code == Some(394) {
        return DriverError::Cancelled;
    }
    DriverError::Query(QueryError {
        message: text.to_owned(),
        sql_state: None,
        vendor_code: code,
        position: None,
    })
}

fn transport(e: reqwest::Error) -> DriverError {
    DriverError::Connect(e.to_string())
}

impl Http {
    /// Sends `sql`; `session` keeps a console's state; `wait` makes the server finish (and count) before answering.
    pub(crate) async fn post(
        &self,
        sql: &str,
        query_id: &str,
        session: Option<&str>,
        wait: bool,
    ) -> Result<reqwest::Response> {
        let mut params: Vec<(&str, &str)> = vec![
            ("query_id", query_id),
            ("default_format", "TabSeparatedWithNamesAndTypes"),
        ];
        if !self.database.is_empty() {
            params.push(("database", &self.database));
        }
        if let Some(s) = session {
            params.push(("session_id", s));
        }
        if wait {
            params.push(("wait_end_of_query", "1"));
        }
        if self.read_only {
            // Reads only, but settings may change (readonly=1 would refuse SET too).
            params.push(("readonly", "2"));
        }
        let response = self
            .client
            .post(&self.url)
            .query(&params)
            .header("X-ClickHouse-User", &self.user)
            .header("X-ClickHouse-Key", &self.password)
            .body(sql.to_owned())
            .send()
            .await
            .map_err(transport)?;
        if !response.status().is_success() {
            let text = response.text().await.map_err(transport)?;
            return Err(server_error(&text));
        }
        Ok(response)
    }

    /// `KILL QUERY` for `query_id`, from a request of its own (a session runs one query at a time).
    pub(crate) async fn kill(&self, query_id: &str) {
        let sql = format!(
            "KILL QUERY WHERE query_id = '{}' ASYNC",
            query_id.replace('\'', "")
        );
        if let Ok(r) = self
            .client
            .post(&self.url)
            .query(&[("query_id", new_id("kill").as_str())])
            .header("X-ClickHouse-User", &self.user)
            .header("X-ClickHouse-Key", &self.password)
            .body(sql)
            .send()
            .await
        {
            let _ = r.bytes().await;
        }
    }
}

fn http(config: &ConnectionConfig, host: &str, port: u16, password: Option<&str>) -> Result<Http> {
    let mut properties = config.properties.clone();
    let mut database = config.database.clone();
    let mut user = config.user.clone();
    if let Some(url) = &config.url {
        let parsed =
            tg_core::connection::string::parse(url).map_err(|e| DriverError::Connect(e.0))?;
        database = parsed.database.unwrap_or(database);
        user = parsed.user.unwrap_or(user);
        for (k, v) in parsed.properties {
            properties.entry(k).or_insert(v);
        }
    }
    let property = |k: &str| {
        properties
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(k))
            .map(|(_, v)| v.as_str())
    };
    let secure = property("ssl")
        .or_else(|| property("secure"))
        .is_some_and(|v| v.eq_ignore_ascii_case("true"))
        || port == 8443;
    crate::engine::use_ring();
    let mut builder = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .pool_idle_timeout(Duration::from_secs(60))
        .user_agent("TableGrip");
    if secure && property("sslmode").is_some_and(|m| m.eq_ignore_ascii_case("none")) {
        builder = builder.tls_danger_accept_invalid_certs(true);
    }
    let client = builder.build().map_err(transport)?;
    let host = if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]")
    } else {
        host.to_owned()
    };
    let user = property("user").map(str::to_owned).unwrap_or(user);
    Ok(Http {
        client,
        url: format!("{}://{host}:{port}/", if secure { "https" } else { "http" }),
        user: if user.trim().is_empty() {
            "default".into()
        } else {
            user.trim().to_owned()
        },
        password: password
            .filter(|p| !p.is_empty())
            .map(str::to_owned)
            .or_else(|| property("password").map(str::to_owned))
            .unwrap_or_default(),
        database: database.trim().to_owned(),
        read_only: config.read_only,
    })
}

pub async fn connect(
    config: &ConnectionConfig,
    secrets: &Secrets,
    paths: &TableGripPaths,
) -> Result<Arc<dyn RelationalConnection>> {
    let (host, port) = host_and_port(config)?;
    let endpoint = open_tunnel(config, &host, port, secrets, paths).await?;
    let http = Arc::new(http(
        config,
        &endpoint.host,
        endpoint.port,
        secrets.password.as_deref(),
    )?);
    // Validate now, so bad credentials fail here rather than in the first tab.
    let mut probe = ChConn::new(http.clone(), None);
    probe.query("SELECT 1", &[], None).await?;
    Ok(Arc::new(SqlDatabase::new(
        config.clone(),
        Arc::new(ChSource { http }),
        endpoint.tunnel,
    )))
}

struct ChSource {
    http: Arc<Http>,
}

#[async_trait]
impl ConnSource for ChSource {
    async fn pooled(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(ChConn::new(self.http.clone(), None)))
    }

    async fn dedicated(&self) -> Result<Box<dyn SqlConn>> {
        Ok(Box::new(ChConn::new(
            self.http.clone(),
            Some(new_id("session")),
        )))
    }
}
