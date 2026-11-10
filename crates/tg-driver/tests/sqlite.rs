//! The relational contract on SQLite (always runs), plus SQLite's own cases from `DatabaseTest.kt`.

mod contract;
mod support;

use tg_core::connection::ConnectionConfig;
use tg_driver::{ExecOptions, QueryResult};
use tg_sql::model::DatabaseType;

fn config(path: &std::path::Path) -> ConnectionConfig {
    let mut config = ConnectionConfig::new("contract", "contract", DatabaseType::SQLITE);
    config.database = path.to_string_lossy().into_owned();
    config
}

#[tokio::test(flavor = "multi_thread")]
async fn contract() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("contract.db");
    contract::run(
        support::connect(&config(&path), None).await,
        contract::Options::default(),
    )
    .await;
    let mut read_only = config(&path);
    read_only.read_only = true;
    contract::read_only(support::connect(&read_only, None).await).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn sessions_and_the_pool_share_the_in_memory_database() {
    let config = ConnectionConfig::new("memory", "memory", DatabaseType::SQLITE);
    let db = support::connect(&config, None).await;
    let mut session = db.open_session().await.unwrap();
    session
        .execute("CREATE TABLE t (x INTEGER)", ExecOptions::default())
        .await
        .unwrap();
    let tables = db.tables(&db.namespaces().await.unwrap()[0]).await.unwrap();
    assert_eq!(
        tables
            .iter()
            .map(|t| t.table.name.as_str())
            .collect::<Vec<_>>(),
        ["t"]
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn binary_cells_preview_in_grids_and_stay_placeholders_in_exports() {
    let config = ConnectionConfig::new("binary", "binary", DatabaseType::SQLITE);
    let db = support::connect(&config, None).await;
    let mut session = db.open_session().await.unwrap();
    session
        .execute("CREATE TABLE b (v BLOB)", ExecOptions::default())
        .await
        .unwrap();
    session
        .execute(
            "INSERT INTO b VALUES (x'00ff'), (zeroblob(20000))",
            ExecOptions::default(),
        )
        .await
        .unwrap();
    let QueryResult::Rows(rows) = session
        .execute("SELECT v FROM b", ExecOptions::default())
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(rows.rows[0][0].as_deref(), Some("0x00FF"));
    assert!(
        rows.rows[1][0]
            .as_deref()
            .unwrap()
            .ends_with("… (20000 bytes)")
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn backs_up_into_a_file() {
    let dir = tempfile::tempdir().unwrap();
    let db = support::connect(&config(&dir.path().join("a.db")), None).await;
    support::seed(
        db.as_ref(),
        "CREATE TABLE t (x INTEGER); INSERT INTO t VALUES (7);",
    )
    .await;
    let target = dir.path().join("copy.db");
    db.backup_into(&target).await.unwrap();
    let copy = support::connect(&config(&target), None).await;
    let mut s = copy.open_session().await.unwrap();
    let QueryResult::Rows(r) = s
        .execute("SELECT x FROM t", ExecOptions::default())
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(r.rows, vec![vec![Some("7".to_owned())]]);
}
