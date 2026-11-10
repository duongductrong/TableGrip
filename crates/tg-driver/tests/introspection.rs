//! Introspection and result reading held to what the Kotlin driver (JDBC) reported, recorded by
//! `IntrospectionGoldenTest` into `conformance/core/golden/db/`. SQLite always runs; PostgreSQL needs
//! `TG_IT_POSTGRES=host:port` and a server seeded with `structure-it/postgres.sql` (see that file).
#![cfg_attr(
    not(any(feature = "sqlite", feature = "postgres", feature = "mysql")),
    allow(dead_code, unused_imports)
)]

mod support;

use serde_json::Value;
use tg_core::connection::ConnectionConfig;
use tg_sql::model::DatabaseType;

fn golden(engine: &str) -> Value {
    let path = support::repo_root().join(format!(
        "conformance/core/golden/db/introspection-{engine}.json"
    ));
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn queries(golden: &Value) -> Vec<String> {
    golden["queries"]
        .as_array()
        .unwrap()
        .iter()
        .map(|q| q["sql"].as_str().unwrap().to_owned())
        .collect()
}

#[cfg(feature = "sqlite")]
#[tokio::test]
async fn sqlite_matches_kotlin() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = ConnectionConfig::new("golden", "golden", DatabaseType::SQLITE);
    config.database = dir.path().join("golden.db").to_string_lossy().into_owned();
    let db = support::connect(&config, None).await;
    let script = std::fs::read_to_string(
        support::repo_root().join("core/src/test/resources/structure-it/sqlite.sql"),
    )
    .unwrap();
    support::seed(db.as_ref(), &script).await;
    let expected = golden("sqlite");
    let queries = queries(&expected);
    let refs: Vec<&str> = queries.iter().map(String::as_str).collect();
    let actual = support::dump(db.as_ref(), &refs, |_| true).await;
    support::assert_json_eq(&expected, &actual);
}

#[cfg(feature = "postgres")]
#[tokio::test]
async fn postgres_matches_kotlin() {
    let Some((host, port)) = support::server("TG_IT_POSTGRES") else {
        return;
    };
    let mut config = ConnectionConfig::new("golden", "golden", DatabaseType::POSTGRESQL);
    config.host = host;
    config.port = Some(port);
    config.database = "postgres".into();
    config.user = "postgres".into();
    let password = std::env::var("TG_IT_POSTGRES_PASSWORD").unwrap_or_else(|_| "pw".into());
    let db = support::connect(&config, Some(&password)).await;
    let expected = golden("postgres");
    let queries = queries(&expected);
    let refs: Vec<&str> = queries.iter().map(String::as_str).collect();
    let actual = support::dump(db.as_ref(), &refs, |n| {
        matches!(n.schema.as_deref(), Some("shop" | "public"))
    })
    .await;
    support::assert_json_eq(&expected, &actual);
}

/// A MySQL-family server seeded with `structure-it/mysql.sql` in database `app` (root password `pw`), compared with
/// the golden of the engine it turns out to be.
#[cfg(feature = "mysql")]
async fn mysql_family(var: &str) {
    let Some((host, port)) = support::server(var) else {
        return;
    };
    let mut config = ConnectionConfig::new("golden", "golden", DatabaseType::MYSQL);
    config.host = host;
    config.port = Some(port);
    config.database = "app".into();
    config.user = "root".into();
    let db = support::connect(&config, Some("pw")).await;
    let mut session = db.open_session().await.unwrap();
    let version = session
        .execute("SELECT version()", tg_driver::ExecOptions::default())
        .await
        .unwrap()
        .into_rows()
        .unwrap()
        .rows[0][0]
        .clone()
        .unwrap();
    let expected = golden(if version.contains("MariaDB") {
        "mariadb"
    } else {
        "mysql"
    });
    let queries = queries(&expected);
    let refs: Vec<&str> = queries.iter().map(String::as_str).collect();
    let mut actual =
        support::dump(db.as_ref(), &refs, |n| n.catalog.as_deref() == Some("app")).await;
    // Databases are namespaces: ignore the contract's scratch ones (`dg_…`) if a run left any behind.
    if let Some(all) = actual["namespaces"].as_array_mut() {
        all.retain(|n| !n["catalog"].as_str().is_some_and(|c| c.starts_with("dg_")));
    }
    support::assert_json_eq(&expected, &actual);
}

/// `TG_IT_MYSQL=host:port`; see the top of `structure-it/mysql.sql` for seeding.
#[cfg(feature = "mysql")]
#[tokio::test(flavor = "multi_thread")]
async fn mysql_matches_kotlin() {
    mysql_family("TG_IT_MYSQL").await;
}

#[cfg(feature = "mysql")]
#[tokio::test(flavor = "multi_thread")]
async fn mariadb_matches_kotlin() {
    mysql_family("TG_IT_MARIADB").await;
}
