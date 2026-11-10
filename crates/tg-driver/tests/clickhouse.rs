//! The relational contract on ClickHouse, in a database created for the run. Skipped unless
//! `TG_IT_CLICKHOUSE=host:port` (the HTTP port) is set (user `default`, password `TG_IT_CLICKHOUSE_PASSWORD`, default
//! `pw`):
//!
//! ```text
//! docker run -d --rm --name dg-it-clickhouse -p 58123:8123 -e CLICKHOUSE_PASSWORD=pw clickhouse/clickhouse-server:25.3
//! TG_IT_CLICKHOUSE=localhost:58123 cargo test -p tg-driver --test clickhouse
//! ```

mod contract;
mod support;

use tg_core::connection::ConnectionConfig;
use tg_driver::{ExecOptions, QueryResult};
use tg_sql::model::DatabaseType;

fn password() -> String {
    std::env::var("TG_IT_CLICKHOUSE_PASSWORD").unwrap_or_else(|_| "pw".into())
}

fn admin() -> Option<ConnectionConfig> {
    let (host, port) = support::server("TG_IT_CLICKHOUSE")?;
    let mut config = ConnectionConfig::new("clickhouse", "clickhouse", DatabaseType::CLICKHOUSE);
    config.host = host;
    config.port = Some(port);
    config.database = "default".into();
    config.user = "default".into();
    Some(config)
}

#[tokio::test(flavor = "multi_thread")]
async fn clickhouse() {
    let Some(admin) = admin() else {
        return;
    };
    let name = format!(
        "dg_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let db = support::connect(&admin, Some(&password())).await;
    let mut session = db.open_session().await.unwrap();
    session
        .execute(&format!("CREATE DATABASE {name}"), ExecOptions::default())
        .await
        .unwrap();
    let mut config = admin.clone();
    config.database = name;
    let db = support::connect(&config, Some(&password())).await;
    contract::run(
        db.clone(),
        contract::Options {
            int_type: "Int32",
            nullable_int_type: Some("Nullable(Int32)"),
            text_type: "String",
            table_suffix: " ENGINE = MergeTree ORDER BY id",
            foreign_keys: false,
            transactions: false,
            endless: "SELECT count() FROM system.numbers",
            cancellation: true,
        },
    )
    .await;

    // A console is an HTTP session: settings and temporary tables last between statements.
    let mut session = db.open_session().await.unwrap();
    session
        .execute(
            "CREATE TEMPORARY TABLE scratch (x Int32)",
            ExecOptions::default(),
        )
        .await
        .unwrap();
    session
        .execute("INSERT INTO scratch VALUES (7)", ExecOptions::default())
        .await
        .unwrap();
    let QueryResult::Rows(r) = session
        .execute(
            "SELECT x, 'a\\tb' AS t, NULL AS n FROM scratch",
            ExecOptions::default(),
        )
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(
        r.rows,
        vec![vec![Some("7".into()), Some("a\tb".into()), None]]
    );
    assert_eq!(r.columns[0].type_name, "Int32");

    let mut read_only = config.clone();
    read_only.read_only = true;
    contract::read_only(support::connect(&read_only, Some(&password())).await).await;
}
