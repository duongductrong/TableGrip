//! `PostgresActivity` from `activity/Activity.kt`: `pg_stat_activity` and `pg_blocking_pids`, cancel and terminate by
//! backend pid. Timestamps are read as epoch milliseconds so the session's time zone doesn't matter.

use crate::activity::{ActivityReader, ActivitySnapshot, SessionActivity, SessionState};
use crate::error::{DriverError, QueryError, Result};
use crate::sql::SqlConn;
use async_trait::async_trait;
use chrono::{DateTime, Utc};

pub struct PostgresActivity;

const SESSIONS: &str = "SELECT pid, usename, datname, client_addr::text AS client, client_hostname, application_name, state, \
       wait_event_type, wait_event, query, (extract(epoch FROM query_start) * 1000)::int8, \
       (extract(epoch FROM xact_start) * 1000)::int8, \
       array_to_string(pg_blocking_pids(pid), ','), pid = pg_backend_pid(), (extract(epoch FROM now()) * 1000)::int8 \
FROM pg_stat_activity WHERE backend_type = 'client backend' ORDER BY query_start NULLS LAST, pid";

fn instant(v: &Option<String>) -> Option<DateTime<Utc>> {
    v.as_deref()
        .and_then(|ms| ms.parse::<i64>().ok())
        .and_then(DateTime::from_timestamp_millis)
}

#[async_trait]
impl ActivityReader for PostgresActivity {
    async fn read(&self, conn: &mut dyn SqlConn) -> Result<ActivitySnapshot> {
        let rows = crate::sql::with_timeout(
            Some(std::time::Duration::from_secs(10)),
            conn.query(SESSIONS, &[], None),
        )
        .await?;
        let mut server_time = None;
        let sessions = rows
            .rows
            .iter()
            .map(|r| {
                server_time = server_time.or_else(|| instant(&r[14]));
                let blocked_by: Vec<String> = r[12]
                    .as_deref()
                    .unwrap_or("")
                    .split(',')
                    .filter(|s| !s.trim().is_empty())
                    .map(str::to_owned)
                    .collect();
                let state = if !blocked_by.is_empty() {
                    SessionState::Blocked
                } else {
                    match r[6].as_deref() {
                        Some("active") => SessionState::Active,
                        Some("idle") => SessionState::Idle,
                        Some("idle in transaction" | "idle in transaction (aborted)") => {
                            SessionState::IdleInTransaction
                        }
                        _ => SessionState::Other,
                    }
                };
                let client = r[4].clone().or_else(|| {
                    r[3].as_deref()
                        .map(|c| c.split('/').next().unwrap_or(c).to_owned())
                });
                let wait = r[7]
                    .as_deref()
                    .filter(|t| *t != "Client")
                    .map(|t| match &r[8] {
                        Some(event) => format!("{t}: {event}"),
                        None => t.to_owned(),
                    });
                SessionActivity {
                    id: r[0].clone().unwrap_or_default(),
                    user: r[1].clone(),
                    database: r[2].clone(),
                    // A null address is a Unix socket.
                    client: Some(client.unwrap_or_else(|| "local".into())),
                    application: r[5].clone().filter(|a| !a.trim().is_empty()),
                    state,
                    wait,
                    query: r[9].clone().filter(|q| !q.trim().is_empty()),
                    query_start: instant(&r[10]),
                    transaction_start: instant(&r[11]),
                    blocked_by,
                    own: r[13].as_deref() == Some("t"),
                }
            })
            .collect();
        Ok(ActivitySnapshot {
            sessions,
            server_time: server_time.unwrap_or_else(Utc::now),
        })
    }

    async fn missing_privileges(&self, conn: &mut dyn SqlConn) -> Result<Vec<String>> {
        let sql = "SELECT r.rolsuper, pg_has_role(current_user, 'pg_read_all_stats', 'USAGE'), \
                   pg_has_role(current_user, 'pg_signal_backend', 'USAGE') FROM pg_roles r WHERE r.rolname = current_user";
        let Ok(rows) = conn.query(sql, &[], None).await else {
            return Ok(Vec::new());
        };
        let Some(r) = rows.rows.first() else {
            return Ok(Vec::new());
        };
        let yes = |i: usize| r[i].as_deref() == Some("t");
        if yes(0) {
            return Ok(Vec::new());
        }
        let mut missing = Vec::new();
        if !yes(1) {
            missing
                .push("pg_read_all_stats — other users' queries and states are hidden".to_owned());
        }
        if !yes(2) {
            missing.push(
                "pg_signal_backend — only your own user's sessions can be cancelled or terminated"
                    .to_owned(),
            );
        }
        Ok(missing)
    }

    async fn cancel(&self, conn: &mut dyn SqlConn, id: &str) -> Result<bool> {
        signal(conn, "pg_cancel_backend", id).await
    }

    async fn terminate(&self, conn: &mut dyn SqlConn, id: &str) -> Result<bool> {
        signal(conn, "pg_terminate_backend", id).await
    }
}

async fn signal(conn: &mut dyn SqlConn, function: &str, id: &str) -> Result<bool> {
    let pid: i32 = id.parse().map_err(|_| {
        DriverError::Query(QueryError::new(format!(
            "Not a PostgreSQL backend pid: {id}"
        )))
    })?;
    let rows = conn
        .query(&format!("SELECT {function}({pid})"), &[], None)
        .await?;
    Ok(rows.rows.first().and_then(|r| r[0].as_deref()) == Some("t"))
}
