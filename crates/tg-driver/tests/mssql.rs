//! The relational contract on SQL Server, in a database created for the run. Skipped unless
//! `TG_IT_MSSQL=host:port` is set (user `sa`, password `TG_IT_MSSQL_PASSWORD`, default `TableGrip#2026`):
//!
//! ```text
//! docker run -d --rm --name dg-it-mssql --platform linux/amd64 -p 51433:1433 -e ACCEPT_EULA=Y \
//!   -e 'MSSQL_SA_PASSWORD=TableGrip#2026' mcr.microsoft.com/mssql/server:2022-latest
//! TG_IT_MSSQL=localhost:51433 cargo test -p tg-driver --test mssql
//! ```

mod contract;
mod support;

use tg_core::connection::ConnectionConfig;
use tg_driver::{ExecOptions, QueryResult};
use tg_sql::SqlSplitter;
use tg_sql::model::DatabaseType;

fn password() -> String {
    std::env::var("TG_IT_MSSQL_PASSWORD").unwrap_or_else(|_| "TableGrip#2026".into())
}

fn admin() -> Option<ConnectionConfig> {
    let (host, port) = support::server("TG_IT_MSSQL")?;
    let mut config = ConnectionConfig::new("mssql", "mssql", DatabaseType::SQLSERVER);
    config.host = host;
    config.port = Some(port);
    config.database = "master".into();
    config.user = "sa".into();
    config
        .properties
        .insert("trustServerCertificate".into(), "true".into());
    Some(config)
}

/// A fresh database on the server, named for this run.
async fn fresh(admin: &ConnectionConfig) -> ConnectionConfig {
    let name = format!(
        "dg_{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis()
    );
    let db = support::connect(admin, Some(&password())).await;
    let mut session = db.open_session().await.unwrap();
    session
        .execute(&format!("CREATE DATABASE {name}"), ExecOptions::default())
        .await
        .unwrap();
    let mut config = admin.clone();
    config.database = name;
    config
}

#[tokio::test(flavor = "multi_thread")]
async fn mssql() {
    let Some(admin) = admin() else {
        return;
    };
    let config = fresh(&admin).await;
    let db = support::connect(&config, Some(&password())).await;
    contract::run(
        db.clone(),
        contract::Options {
            // A recursive CTE stops at MAXRECURSION, and WAITFOR can't be in a view.
            endless: "SELECT COUNT_BIG(*) AS n FROM sys.all_objects a CROSS JOIN sys.all_objects b \
                      CROSS JOIN sys.all_objects c CROSS JOIN sys.all_objects d",
            ..contract::Options::default()
        },
    )
    .await;

    // EXPLAIN is a SHOWPLAN batch around the statement; the plan comes back as rows.
    let mut session = db.open_session().await.unwrap();
    let mut results = Vec::new();
    for sql in db.dialect().explain("SELECT * FROM [users] WHERE [id] = 1") {
        results.push(session.execute(&sql, ExecOptions::default()).await.unwrap());
    }
    let QueryResult::Rows(plan) = &results[1] else {
        panic!("{results:?}")
    };
    assert!(!plan.rows.is_empty());

    // Brackets and GO survive the splitter, as consoles run scripts.
    let script = "CREATE TABLE [odd]]name] ([a b] INT)\nGO\nINSERT INTO [odd]]name] VALUES (1)\nGO 1\nSELECT * FROM [odd]]name]";
    let statements: Vec<String> = SqlSplitter::new(db.dialect().syntax())
        .split(script)
        .into_iter()
        .map(|s| s.text)
        .collect();
    assert_eq!(statements.len(), 3);
    let mut session = db.open_session().await.unwrap();
    for sql in &statements {
        session.execute(sql, ExecOptions::default()).await.unwrap();
    }

    let mut read_only = config.clone();
    read_only.read_only = true;
    contract::read_only(support::connect(&read_only, Some(&password())).await).await;
}

/// Values written out as mssql-jdbc's `getString` did.
#[tokio::test(flavor = "multi_thread")]
async fn cells_read_like_mssql_jdbc() {
    let Some(config) = admin() else {
        return;
    };
    let db = support::connect(&config, Some(&password())).await;
    let mut session = db.open_session().await.unwrap();
    let QueryResult::Rows(r) = session
        .execute(
            "SELECT CAST(1 AS bit) AS b, CAST(2.5 AS float) AS f, CAST(12.34 AS money) AS m, \
             CAST(123.45 AS decimal(10, 3)) AS d, CAST('2024-02-03 04:05:06.5' AS datetime) AS dt, \
             CAST('2024-02-03 04:05:06.5' AS datetime2(3)) AS dt2, CAST('2024-02-03' AS date) AS day, \
             CAST('04:05:06' AS time(0)) AS t, CAST('2024-02-03 04:05:06 +01:00' AS datetimeoffset(0)) AS o, \
             CAST('b0a1d9d0-0f3e-4b1a-9b6c-6c1f1c0f9a11' AS uniqueidentifier) AS u, 0x00FF AS bin, N'ü' AS s, \
             CAST(NULL AS int) AS z",
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
            Some("12.3400"),
            Some("123.450"),
            Some("2024-02-03 04:05:06.5"),
            Some("2024-02-03 04:05:06.500"),
            Some("2024-02-03"),
            Some("04:05:06"),
            Some("2024-02-03 04:05:06 +01:00"),
            Some("B0A1D9D0-0F3E-4B1A-9B6C-6C1F1C0F9A11"),
            Some("0x00FF"),
            Some("ü"),
            None,
        ]
    );
    let types: Vec<(&str, i32)> = r
        .columns
        .iter()
        .map(|c| (c.type_name.as_str(), c.jdbc_type))
        .collect();
    assert_eq!(types[0], ("bit", -7));
    assert_eq!(types[3], ("decimal", 3));
    assert_eq!(types[10], ("varbinary", -3));
}
