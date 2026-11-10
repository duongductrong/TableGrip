//! The relational contract on DuckDB, which is built in: it runs by default, on a database file of its own.
#![cfg(feature = "duckdb")]

mod contract;
mod support;

use tg_core::connection::ConnectionConfig;
use tg_driver::{ExecOptions, QueryResult};
use tg_sql::model::DatabaseType;

fn options() -> contract::Options {
    contract::Options {
        endless: "SELECT count(*) FROM range(1000000000000000)",
        ..contract::Options::default()
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn duckdb() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = ConnectionConfig::new("duck", "duck", DatabaseType::DUCKDB);
    config.database = dir
        .path()
        .join("contract.duckdb")
        .to_string_lossy()
        .into_owned();
    let db = support::connect(&config, None).await;
    contract::run(db.clone(), options()).await;

    // DuckDB writes itself out as Parquet files (Database.backupInto).
    let backup = dir.path().join("backup");
    db.backup_into(&backup).await.unwrap();
    assert!(backup.join("schema.sql").exists());
    drop(db);

    let mut read_only = config.clone();
    read_only.read_only = true;
    contract::read_only(support::connect(&read_only, None).await).await;
}

/// An in-memory database lives as long as its connection, shared by the pool and the consoles.
#[tokio::test(flavor = "multi_thread")]
async fn in_memory() {
    let config = ConnectionConfig::new("mem", "mem", DatabaseType::DUCKDB);
    let db = support::connect(&config, None).await;
    contract::run(db.clone(), options()).await;
}

/// Values written out as DuckDB JDBC's `getString` did.
#[tokio::test(flavor = "multi_thread")]
async fn cells_read_like_duckdb_jdbc() {
    let config = ConnectionConfig::new("cells", "cells", DatabaseType::DUCKDB);
    let db = support::connect(&config, None).await;
    let mut session = db.open_session().await.unwrap();
    let QueryResult::Rows(r) = session
        .execute(
            "SELECT 1 AS i, 2.5::DOUBLE AS f, 12.30::DECIMAL(10,2) AS d, 'a' AS t, '\\x00\\xFF'::BLOB AS b, NULL AS n, \
             true AS yes, DATE '2024-02-03' AS day, TIMESTAMP '2024-02-03 04:05:06.5' AS ts, TIME '04:05:06' AS tm, \
             [1, 2] AS l, {'a': 1} AS s, INTERVAL 1 DAY AS iv, 3000000000 AS big",
            ExecOptions::default(),
        )
        .await
        .unwrap()
    else {
        panic!()
    };
    let cells: Vec<Option<&str>> = r.rows[0].iter().map(|c| c.as_deref()).collect();
    assert_eq!(
        cells,
        [
            Some("1"),
            Some("2.5"),
            Some("12.30"),
            Some("a"),
            Some("0x00FF"),
            None,
            Some("true"),
            Some("2024-02-03"),
            Some("2024-02-03 04:05:06.5"),
            Some("04:05:06"),
            Some("[1, 2]"),
            Some("{a: 1}"),
            Some("1 day"),
            Some("3000000000"),
        ]
    );
    let types: Vec<(&str, i32)> = r
        .columns
        .iter()
        .map(|c| (c.type_name.as_str(), c.jdbc_type))
        .collect();
    assert_eq!(types[0], ("INTEGER", 4));
    assert_eq!(types[2], ("DECIMAL(10,2)", 3));
    assert_eq!(types[4], ("BLOB", 2004));
    assert_eq!(types[10], ("INTEGER[]", 2003));
}
