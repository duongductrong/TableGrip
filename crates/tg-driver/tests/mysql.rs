//! The relational contract on the MySQL family, each in a database created for the run. Skipped unless the server's
//! variable is set: `TG_IT_MYSQL`, `TG_IT_MARIADB` (user `root`, password `pw`) and `TG_IT_TIDB` (user `root`, no
//! password), as `host:port`.
//!
//! ```text
//! docker run -d --rm --name dg-it-mysql -e MYSQL_ROOT_PASSWORD=pw -p 53306:3306 mysql:8.4
//! docker run -d --rm --name dg-it-mariadb -e MARIADB_ROOT_PASSWORD=pw -p 53307:3306 mariadb:11.4
//! docker run -d --rm --name dg-it-tidb -p 54000:4000 pingcap/tidb:v8.5.1
//! TG_IT_MYSQL=localhost:53306 TG_IT_MARIADB=localhost:53307 TG_IT_TIDB=localhost:54000 \
//!   cargo test -p tg-driver --test mysql
//! ```

mod contract;
mod support;

use tg_core::connection::ConnectionConfig;
use tg_driver::{ExecOptions, QueryResult};
use tg_sql::model::DatabaseType;

fn config(var: &str, db_type: DatabaseType, database: &str) -> Option<ConnectionConfig> {
    let (host, port) = support::server(var)?;
    let mut config = ConnectionConfig::new(var, var, db_type);
    config.host = host;
    config.port = Some(port);
    config.database = database.into();
    config.user = "root".into();
    Some(config)
}

fn options() -> contract::Options {
    contract::Options {
        // A recursive CTE stops at cte_max_recursion_depth; SLEEP runs until killed.
        endless: "SELECT SLEEP(60)",
        ..contract::Options::default()
    }
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

async fn run_contract(admin: ConnectionConfig, password: Option<&str>) {
    let config = fresh(&admin, password).await;
    contract::run(support::connect(&config, password).await, options()).await;
    let mut read_only = config.clone();
    read_only.read_only = true;
    contract::read_only(support::connect(&read_only, password).await).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn mysql() {
    let Some(admin) = config("TG_IT_MYSQL", DatabaseType::MYSQL, "mysql") else {
        return;
    };
    run_contract(admin, Some("pw")).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn mariadb() {
    let Some(admin) = config("TG_IT_MARIADB", DatabaseType::MARIADB, "mysql") else {
        return;
    };
    run_contract(admin, Some("pw")).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn tidb() {
    let Some(admin) = config("TG_IT_TIDB", DatabaseType::TIDB, "test") else {
        return;
    };
    run_contract(admin, None).await;
}

/// Test Connection names the real engine when a profile picked another in its family (ServerEnginesIntegrationTest).
#[tokio::test(flavor = "multi_thread")]
async fn servers_are_recognised_within_their_family() {
    let paths = support::paths();
    let secrets = |p: Option<&str>| tg_driver::Secrets {
        password: p.map(str::to_owned),
        ssh: None,
    };
    if let Some(c) = config("TG_IT_TIDB", DatabaseType::MYSQL, "test") {
        let outcome = tg_driver::test(&c, &secrets(None), &paths).await.unwrap();
        assert_eq!(outcome.suggested_type, Some(DatabaseType::TIDB));
    }
    if let Some(c) = config("TG_IT_MYSQL", DatabaseType::MARIADB, "mysql") {
        let outcome = tg_driver::test(&c, &secrets(Some("pw")), &paths)
            .await
            .unwrap();
        assert_eq!(outcome.suggested_type, Some(DatabaseType::MYSQL));
    }
    if let Some(c) = config("TG_IT_MARIADB", DatabaseType::MYSQL, "mysql") {
        let outcome = tg_driver::test(&c, &secrets(Some("pw")), &paths)
            .await
            .unwrap();
        assert_eq!(outcome.suggested_type, Some(DatabaseType::MARIADB));
    }
}

/// Text results: what the server sends, BIT as Connector/J spelled it, binary as previews.
#[tokio::test(flavor = "multi_thread")]
async fn cells_read_like_connector_j() {
    let Some(c) = config("TG_IT_MYSQL", DatabaseType::MYSQL, "mysql") else {
        return;
    };
    let db = support::connect(&c, Some("pw")).await;
    let mut session = db.open_session().await.unwrap();
    let QueryResult::Rows(r) = session
        .execute(
            "SELECT b'101' + 0 AS n, CAST(b'101' AS BINARY) AS b, 'it''s \\\\ fine' AS t",
            ExecOptions::default(),
        )
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(
        r.rows[0],
        [
            Some("5".into()),
            Some("0x05".into()),
            Some("it's \\ fine".into())
        ]
    );
}
