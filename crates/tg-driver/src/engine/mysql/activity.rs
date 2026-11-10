//! `MySqlActivity` from `activity/Activity.kt`: `PROCESSLIST` for sessions, `INNODB_TRX` for open transactions, lock
//! waits from `performance_schema.data_lock_waits` (MySQL 8) or `INNODB_LOCK_WAITS` (MariaDB, MySQL 5.7). Ages are
//! computed on the server, in seconds, so time zones don't matter.

use crate::activity::{ActivityReader, ActivitySnapshot, SessionActivity, SessionState};
use crate::error::{DriverError, QueryError, Result};
use crate::sql::{SqlConn, with_timeout};
use async_trait::async_trait;
use chrono::{TimeDelta, Utc};
use std::time::Duration;

pub struct MySqlActivity;

const SESSIONS: &str = "SELECT p.ID, p.USER, p.HOST, p.DB, p.COMMAND, p.TIME, p.STATE, p.INFO, p.ID = CONNECTION_ID() AS own, \
       TIMESTAMPDIFF(SECOND, t.trx_started, NOW()) AS trx_age, t.trx_id \
FROM information_schema.PROCESSLIST p \
LEFT JOIN information_schema.INNODB_TRX t ON t.trx_mysql_thread_id = p.ID \
WHERE p.COMMAND NOT IN ('Daemon', 'Binlog Dump', 'Binlog Dump GTID') AND p.USER NOT IN ('event_scheduler', 'system user')";

const LOCK_WAITS: [&str; 2] = [
    "SELECT r.trx_mysql_thread_id AS waiting, b.trx_mysql_thread_id AS blocking \
     FROM performance_schema.data_lock_waits w \
     JOIN information_schema.INNODB_TRX r ON r.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID \
     JOIN information_schema.INNODB_TRX b ON b.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID",
    "SELECT r.trx_mysql_thread_id AS waiting, b.trx_mysql_thread_id AS blocking \
     FROM information_schema.INNODB_LOCK_WAITS w \
     JOIN information_schema.INNODB_TRX r ON r.trx_id = w.requesting_trx_id \
     JOIN information_schema.INNODB_TRX b ON b.trx_id = w.blocking_trx_id",
];

const PROGRAM_NAMES: &str = "SELECT PROCESSLIST_ID, ATTR_VALUE FROM performance_schema.session_connect_attrs WHERE ATTR_NAME = 'program_name'";

type Row = Vec<Option<String>>;

/// The first of `queries` the server accepts, or nothing: lock waits and attributes need privileges or features some
/// servers lack.
async fn optional(conn: &mut dyn SqlConn, queries: &[&str]) -> Vec<Row> {
    for sql in queries {
        if let Ok(rows) =
            with_timeout(Some(Duration::from_secs(10)), conn.query(sql, &[], None)).await
        {
            return rows.rows;
        }
    }
    Vec::new()
}

#[async_trait]
impl ActivityReader for MySqlActivity {
    async fn read(&self, conn: &mut dyn SqlConn) -> Result<ActivitySnapshot> {
        let now = Utc::now();
        let waits = optional(conn, &LOCK_WAITS).await;
        let programs = optional(conn, &[PROGRAM_NAMES]).await;
        let rows = with_timeout(
            Some(Duration::from_secs(10)),
            conn.query(SESSIONS, &[], None),
        )
        .await?;
        let sessions = rows
            .rows
            .iter()
            .map(|r| {
                let text = |i: usize| r.get(i).cloned().flatten();
                let id = text(0).unwrap_or_default();
                let command = text(4).unwrap_or_default();
                let state_text = text(6).filter(|s| !s.trim().is_empty());
                let in_transaction = text(10).is_some();
                let mut blocked_by: Vec<String> = Vec::new();
                for w in &waits {
                    if w[0].as_deref() == Some(id.as_str())
                        && let Some(b) = &w[1]
                        && !blocked_by.contains(b)
                    {
                        blocked_by.push(b.clone());
                    }
                }
                // Metadata and table locks don't show up as InnoDB lock waits; the state says so instead.
                let waiting_on_lock = state_text.as_deref().is_some_and(|s| {
                    s.to_lowercase().starts_with("waiting") && s.to_lowercase().contains("lock")
                });
                let state = if !blocked_by.is_empty() || waiting_on_lock {
                    SessionState::Blocked
                } else if command == "Sleep" {
                    if in_transaction {
                        SessionState::IdleInTransaction
                    } else {
                        SessionState::Idle
                    }
                } else if command == "Query" || command == "Execute" {
                    SessionState::Active
                } else {
                    SessionState::Other
                };
                let ago = |secs: Option<i64>| secs.map(|s| now - TimeDelta::seconds(s));
                let in_state = text(5).and_then(|t| t.parse::<i64>().ok());
                SessionActivity {
                    application: programs
                        .iter()
                        .find(|p| p[0].as_deref() == Some(id.as_str()))
                        .and_then(|p| p[1].clone()),
                    id,
                    user: text(1),
                    database: text(3),
                    client: text(2).map(|h| match h.rfind(':') {
                        Some(i) => h[..i].to_owned(),
                        None => h,
                    }),
                    wait: state_text
                        .clone()
                        .filter(|_| state == SessionState::Blocked),
                    query: text(7).filter(|q| !q.trim().is_empty()),
                    // TIME is seconds in the current state: the query's age while one runs.
                    query_start: ago(in_state)
                        .filter(|_| matches!(state, SessionState::Active | SessionState::Blocked)),
                    transaction_start: ago(text(9).and_then(|t| t.parse().ok())),
                    blocked_by,
                    own: text(8).as_deref() == Some("1"),
                    state,
                }
            })
            .collect();
        Ok(ActivitySnapshot {
            sessions,
            server_time: now,
        })
    }

    async fn missing_privileges(&self, conn: &mut dyn SqlConn) -> Result<Vec<String>> {
        let sql = "SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES \
                   WHERE GRANTEE = CONCAT('''', SUBSTRING_INDEX(CURRENT_USER(), '@', 1), '''@''', SUBSTRING_INDEX(CURRENT_USER(), '@', -1), '''')";
        let Ok(rows) = conn.query(sql, &[], None).await else {
            return Ok(Vec::new());
        };
        let privileges: Vec<String> = rows
            .rows
            .iter()
            .filter_map(|r| r[0].as_deref().map(str::to_uppercase))
            .collect();
        let has = |p: &str| privileges.iter().any(|x| x == p);
        let mut missing = Vec::new();
        if !has("PROCESS") {
            missing.push(
                "PROCESS — only your own user's sessions are listed, and lock waits are hidden"
                    .to_owned(),
            );
        }
        if !has("SUPER") && !has("CONNECTION_ADMIN") {
            missing.push(
                "CONNECTION_ADMIN or SUPER — only your own user's sessions can be cancelled or terminated"
                    .to_owned(),
            );
        }
        Ok(missing)
    }

    async fn cancel(&self, conn: &mut dyn SqlConn, id: &str) -> Result<bool> {
        kill(conn, "KILL QUERY", id).await
    }

    async fn terminate(&self, conn: &mut dyn SqlConn, id: &str) -> Result<bool> {
        kill(conn, "KILL", id).await
    }
}

async fn kill(conn: &mut dyn SqlConn, statement: &str, id: &str) -> Result<bool> {
    let thread: u64 = id.parse().map_err(|_| {
        DriverError::Query(QueryError::new(format!("Not a MySQL connection id: {id}")))
    })?;
    match conn.execute(&format!("{statement} {thread}"), &[]).await {
        Ok(_) => Ok(true),
        // 1094: unknown thread id, i.e. it ended already.
        Err(DriverError::Query(e)) if e.vendor_code == Some(1094) => Ok(false),
        Err(e) => Err(e),
    }
}
