//! The relational contract on PostgreSQL, in a database created for the run. Skipped unless
//! `TG_IT_POSTGRES=host:port` is set (user `postgres`, password `TG_IT_POSTGRES_PASSWORD`, default `pw`):
//!
//! ```text
//! docker run -d --rm --name dg-pg -e POSTGRES_PASSWORD=pw -p 55432:5432 postgres:17-alpine
//! TG_IT_POSTGRES=localhost:55432 cargo test -p tg-driver --test postgres
//! ```
//!
//! CockroachDB and TimescaleDB go through the same engine: `TG_IT_COCKROACH` and `TG_IT_TIMESCALE` run the contract
//! on them (commands in `ServerEnginesIntegrationTest.kt`).
#![cfg(feature = "postgres")]

mod contract;
mod support;

use tg_core::connection::ConnectionConfig;
use tg_driver::{ExecOptions, QueryResult, Secrets};
use tg_sql::model::DatabaseType;

fn password() -> String {
    std::env::var("TG_IT_POSTGRES_PASSWORD").unwrap_or_else(|_| "pw".into())
}

fn config(
    var: &str,
    db_type: tg_sql::model::DatabaseType,
    database: &str,
    user: &str,
) -> Option<ConnectionConfig> {
    let (host, port) = support::server(var)?;
    let mut config = ConnectionConfig::new(var, var, db_type);
    config.host = host;
    config.port = Some(port);
    config.database = database.into();
    config.user = user.into();
    config.properties.insert("sslmode".into(), "disable".into());
    Some(config)
}

/// A fresh database on the server, named for this run.
async fn fresh(admin: &ConnectionConfig, password: Option<&str>) -> ConnectionConfig {
    let name = format!(
        "dg_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let db = support::connect(admin, password).await;
    let mut session = db.open_session().await.unwrap();
    session
        .execute(&format!("CREATE DATABASE {name}"), ExecOptions::default())
        .await
        .unwrap();
    let mut config = admin.clone();
    config.database = name;
    config
}

async fn run_contract(admin: ConnectionConfig, password: Option<&str>, options: contract::Options) {
    let config = fresh(&admin, password).await;
    contract::run(support::connect(&config, password).await, options).await;
    let mut read_only = config.clone();
    read_only.read_only = true;
    contract::read_only(support::connect(&read_only, password).await).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn postgres() {
    let Some(admin) = config(
        "TG_IT_POSTGRES",
        DatabaseType::POSTGRESQL,
        "postgres",
        "postgres",
    ) else {
        return;
    };
    run_contract(admin, Some(&password()), contract::Options::default()).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn cockroach() {
    let Some(admin) = config(
        "TG_IT_COCKROACH",
        DatabaseType::COCKROACHDB,
        "defaultdb",
        "root",
    ) else {
        return;
    };
    // CockroachDB ignores pgwire cancel requests, so a dropped query runs on (see the S3 log).
    run_contract(
        admin,
        None,
        contract::Options {
            cancellation: false,
            ..contract::Options::default()
        },
    )
    .await;
}

#[tokio::test(flavor = "multi_thread")]
async fn timescale() {
    let Some(admin) = config(
        "TG_IT_TIMESCALE",
        DatabaseType::TIMESCALEDB,
        "postgres",
        "postgres",
    ) else {
        return;
    };
    run_contract(admin, Some("pw"), contract::Options::default()).await;
}

/// Test Connection names the real engine when a profile picked another in its family (ServerEnginesIntegrationTest).
#[tokio::test(flavor = "multi_thread")]
async fn servers_are_recognised_within_their_family() {
    let paths = support::paths();
    if let Some(c) = config(
        "TG_IT_POSTGRES",
        DatabaseType::POSTGRESQL,
        "postgres",
        "postgres",
    ) {
        let secrets = Secrets {
            password: Some(password()),
            ssh: None,
        };
        assert_eq!(
            tg_driver::test(&c, &secrets, &paths)
                .await
                .unwrap()
                .suggested_type,
            None
        );
        // Hosted variants are taken at their word.
        let mut neon = c.clone();
        neon.db_type = DatabaseType::NEON;
        assert_eq!(
            tg_driver::test(&neon, &secrets, &paths)
                .await
                .unwrap()
                .suggested_type,
            None
        );
    }
    if let Some(c) = config(
        "TG_IT_COCKROACH",
        DatabaseType::POSTGRESQL,
        "defaultdb",
        "root",
    ) {
        let outcome = tg_driver::test(&c, &Secrets::default(), &paths)
            .await
            .unwrap();
        assert_eq!(outcome.suggested_type, Some(DatabaseType::COCKROACHDB));
    }
    if let Some(c) = config(
        "TG_IT_TIMESCALE",
        DatabaseType::POSTGRESQL,
        "postgres",
        "postgres",
    ) {
        let secrets = Secrets {
            password: Some("pw".into()),
            ssh: None,
        };
        assert_eq!(
            tg_driver::test(&c, &secrets, &paths)
                .await
                .unwrap()
                .suggested_type,
            Some(DatabaseType::TIMESCALEDB)
        );
    }
}

/// Postgres-only behaviour: server errors carry SQLSTATE and position, bytea previews, the activity monitor.
#[tokio::test(flavor = "multi_thread")]
async fn errors_binary_and_activity() {
    let Some(admin) = config(
        "TG_IT_POSTGRES",
        DatabaseType::POSTGRESQL,
        "postgres",
        "postgres",
    ) else {
        return;
    };
    let db = support::connect(&admin, Some(&password())).await;
    let mut session = db.open_session().await.unwrap();
    let error = session
        .execute("SELECT * FROM no_such_table", ExecOptions::default())
        .await
        .unwrap_err();
    let q = error.query_error();
    assert_eq!(q.sql_state.as_deref(), Some("42P01"));
    assert_eq!(q.position, Some(14));
    assert!(
        q.message
            .starts_with("ERROR: relation \"no_such_table\" does not exist"),
        "{}",
        q.message
    );

    let QueryResult::Rows(r) = session
        .execute("SELECT '\\x00ff'::bytea, 'it''s'", ExecOptions::default())
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(
        r.rows[0],
        [Some("0x00FF".to_owned()), Some("it's".to_owned())]
    );

    let mut monitor = db.open_activity_monitor().await.unwrap();
    let snapshot = monitor.snapshot().await.unwrap();
    assert!(snapshot.sessions.iter().any(|s| s.own));
    assert!(
        snapshot
            .sessions
            .iter()
            .any(|s| s.application.as_deref() == Some("TableGrip"))
    );
    assert_eq!(db.server_major_version().await.unwrap(), Some(17));
}

/// Console reads stop at `max_rows` through a cursor, outside and inside a transaction, without failing it.
#[tokio::test(flavor = "multi_thread")]
async fn limited_reads_keep_the_transaction() {
    let Some(admin) = config(
        "TG_IT_POSTGRES",
        DatabaseType::POSTGRESQL,
        "postgres",
        "postgres",
    ) else {
        return;
    };
    let db = support::connect(&admin, Some(&password())).await;
    let mut session = db.open_session().await.unwrap();
    let limit = ExecOptions {
        max_rows: 1000,
        timeout: None,
    };
    let QueryResult::Rows(r) = session
        .execute("SELECT generate_series(1, 1000000) AS n", limit)
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!((r.rows.len(), r.truncated), (1000, true));
    assert_eq!(r.columns[0].type_name, "int4");

    session.set_auto_commit(false).await.unwrap();
    session
        .execute("CREATE TEMP TABLE kept (x int)", limit)
        .await
        .unwrap();
    let QueryResult::Rows(r) = session
        .execute("SELECT generate_series(1, 5000)", limit)
        .await
        .unwrap()
    else {
        panic!()
    };
    assert!(r.truncated);
    // A statement that can't be a cursor still runs, and the transaction is intact.
    assert!(
        session
            .execute("SELECT 1 INTO TEMP other", limit)
            .await
            .is_ok()
    );
    session
        .execute("INSERT INTO kept VALUES (1)", limit)
        .await
        .unwrap();
    let QueryResult::Rows(r) = session
        .execute("SELECT count(*) FROM kept", limit)
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(r.rows[0][0].as_deref(), Some("1"));
    session.rollback().await.unwrap();
}
