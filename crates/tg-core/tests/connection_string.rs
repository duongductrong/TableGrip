//! Port of ConnectionStringTest (cases follow the libpq, pgjdbc, Connector/J and MariaDB Connector/J documentation)
//! and the pasted-path part of DatabaseFilesTest.

use indexmap::IndexMap;
use std::fs;
use tg_core::connection::string::{parse as parse_str, strip_secrets};
use tg_core::connection::{DriverId, ParsedConnection};
use tg_sql::model::DatabaseType;

fn parse(text: &str) -> ParsedConnection {
    parse_str(text).unwrap_or_else(|e| panic!("{text}: {e}"))
}

fn props(pairs: &[(&str, &str)]) -> IndexMap<String, String> {
    pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
}

#[test]
fn libpq_uris_with_user_info_port_database_and_parameters() {
    let parsed = parse(
        "postgresql://other:s%40cret@localhost:5433/otherdb?connect_timeout=10&application_name=myapp",
    );
    assert_eq!(parsed.db_type, DatabaseType::POSTGRESQL);
    assert_eq!(parsed.host.as_deref(), Some("localhost"));
    assert_eq!(parsed.port, Some(5433));
    assert_eq!(parsed.database.as_deref(), Some("otherdb"));
    assert_eq!(parsed.user.as_deref(), Some("other"));
    assert_eq!(parsed.password.as_deref(), Some("s@cret"));
    assert_eq!(
        parsed.properties,
        props(&[("connectTimeout", "10"), ("ApplicationName", "myapp")])
    );
    assert_eq!(parsed.url, None);
}

#[test]
fn libpq_uris_accept_the_postgres_scheme_bare_forms_and_named_parameters() {
    assert_eq!(
        parse("postgres://localhost").host.as_deref(),
        Some("localhost")
    );
    assert_eq!(parse("postgresql://").host, None);
    let named = parse("postgresql:///mydb?host=localhost&port=5433");
    assert_eq!(named.host.as_deref(), Some("localhost"));
    assert_eq!(named.port, Some(5433));
    assert_eq!(named.database.as_deref(), Some("mydb"));
}

#[test]
fn libpq_multiple_hosts_ipv6_and_percent_encoded_options() {
    let multi = parse(
        "postgresql://host1:123,host2:456/somedb?target_session_attrs=any&application_name=myapp",
    );
    assert_eq!(multi.host.as_deref(), Some("host1:123,host2:456"));
    assert_eq!(multi.port, None);
    assert_eq!(multi.properties["targetServerType"], "any");

    let ipv6 = parse("postgresql://[2001:db8::1234]/database");
    assert_eq!(ipv6.host.as_deref(), Some("2001:db8::1234"));

    let options =
        parse("postgresql://user@localhost:5433/mydb?options=-c%20synchronous_commit%3Doff");
    assert_eq!(options.properties["options"], "-c synchronous_commit=off");
}

#[test]
fn libpq_ssl_true_means_sslmode_require_sockets_fall_back_to_localhost() {
    assert_eq!(
        parse("postgresql://db.example.com/app?ssl=true").properties["sslmode"],
        "require"
    );
    let socket = parse("postgresql://%2Fvar%2Flib%2Fpostgresql/dbname");
    assert_eq!(socket.host.as_deref(), Some("localhost"));
    assert!(socket.warnings.iter().any(|w| w.contains("Unix-domain")));
}

#[test]
fn libpq_keyword_value_strings_with_quoting_and_escapes() {
    let parsed = parse(
        r"host=localhost port=5432 dbname='my db' user=me password='it\'s \\ ok' connect_timeout=10 sslmode=verify-full",
    );
    assert_eq!(parsed.host.as_deref(), Some("localhost"));
    assert_eq!(parsed.port, Some(5432));
    assert_eq!(parsed.database.as_deref(), Some("my db"));
    assert_eq!(parsed.password.as_deref(), Some(r"it's \ ok"));
    assert_eq!(
        parsed.properties,
        props(&[("connectTimeout", "10"), ("sslmode", "verify-full")])
    );
}

#[test]
fn pgjdbc_url_forms() {
    assert_eq!(
        parse("jdbc:postgresql:accounting").database.as_deref(),
        Some("accounting")
    );
    let full = parse(
        "jdbc:postgresql://[::1]:5740/accounting?user=fred&password=p+w&sslmode=require&currentSchema=app",
    );
    assert_eq!(full.driver, Some(DriverId::PostgreSQL));
    assert_eq!(full.host.as_deref(), Some("::1"));
    assert_eq!(full.port, Some(5740));
    assert_eq!(full.user.as_deref(), Some("fred"));
    assert_eq!(
        full.password.as_deref(),
        Some("p+w"),
        "a plus is not a space outside form encoding"
    );
    assert_eq!(
        full.properties,
        props(&[("sslmode", "require"), ("currentSchema", "app")])
    );
    assert_eq!(
        parse("jdbc:postgresql://host1:5432,host2:5433/db")
            .host
            .as_deref(),
        Some("host1:5432,host2:5433")
    );
}

#[test]
fn mysql_and_mariadb_uris() {
    let mysql = parse(
        "mysql://sandy:secret@db.example.com:3307/sakila?ssl-mode=required&serverTimezone=UTC",
    );
    assert_eq!(mysql.db_type, DatabaseType::MYSQL);
    assert_eq!(mysql.driver, None);
    assert_eq!(mysql.host.as_deref(), Some("db.example.com"));
    assert_eq!(mysql.port, Some(3307));
    assert_eq!(mysql.database.as_deref(), Some("sakila"));
    assert_eq!(mysql.user.as_deref(), Some("sandy"));
    assert_eq!(mysql.password.as_deref(), Some("secret"));
    assert_eq!(
        mysql.properties,
        props(&[("sslMode", "REQUIRED"), ("serverTimezone", "UTC")])
    );

    let jdbc = parse("jdbc:mariadb://localhost:3306/shop?user=root&sslMode=verify-full");
    assert_eq!(jdbc.db_type, DatabaseType::MARIADB);
    assert_eq!(jdbc.driver, Some(DriverId::MariaDB));
    assert_eq!(jdbc.user.as_deref(), Some("root"));
    assert_eq!(jdbc.properties, props(&[("sslMode", "verify-full")]));

    // The type's default driver (MariaDB Connector/J) takes jdbc:mysql URLs, and TableGrip can download it.
    assert_eq!(parse("jdbc:mysql://host1:33060/sakila").driver, None);
}

#[test]
fn advanced_connector_j_and_mariadb_forms_are_kept_as_urls_without_the_password() {
    let replication = parse(
        "jdbc:mysql:replication://sandy:secret@primary:3306,replica:3306/db?password=other&useSSL=false",
    );
    assert_eq!(
        replication.url.as_deref(),
        Some("jdbc:mysql:replication://sandy@primary:3306,replica:3306/db?useSSL=false")
    );
    assert_eq!(replication.user.as_deref(), Some("sandy"));
    assert_eq!(
        replication.password.as_deref(),
        Some("other"),
        "the query password is the last one removed"
    );

    let key_value = parse("jdbc:mysql://address=(host=myhost)(port=1111)(key1=value1)/db");
    assert_eq!(
        key_value.url.as_deref(),
        Some("jdbc:mysql://address=(host=myhost)(port=1111)(key1=value1)/db")
    );

    let sequential = parse("jdbc:mariadb:sequential://host1,host2/db");
    assert_eq!(sequential.db_type, DatabaseType::MARIADB);
    assert_eq!(
        sequential.url.as_deref(),
        Some("jdbc:mariadb:sequential://host1,host2/db")
    );
}

#[test]
fn sqlite_urls_and_paths() {
    assert_eq!(
        parse("jdbc:sqlite:/Users/me/app.db").database.as_deref(),
        Some("/Users/me/app.db")
    );
    assert_eq!(
        parse("jdbc:sqlite::memory:").database.as_deref(),
        Some(":memory:")
    );
    assert_eq!(
        parse("sqlite:///abs/data.sqlite").database.as_deref(),
        Some("/abs/data.sqlite")
    );
    assert_eq!(
        parse("sqlite:////abs/data.sqlite").database.as_deref(),
        Some("/abs/data.sqlite")
    );
    assert_eq!(parse("sqlite://").database.as_deref(), Some(":memory:"));
    assert_eq!(
        parse("file:///tmp/my%20db.db").database.as_deref(),
        Some("/tmp/my db.db")
    );
    let home = tg_core::paths::user_home();
    assert_eq!(
        parse("~/data/app.sqlite3").database,
        Some(format!("{}/data/app.sqlite3", home.display()))
    );
    assert_eq!(parse("./local.db").db_type, DatabaseType::SQLITE);
}

#[test]
fn rejects_unsupported_strings_with_a_helpful_message() {
    assert!(parse_str("mysqlx://host/db").is_err());
    assert!(parse_str("hello world").is_err());
    assert!(parse_str("postgresql://host:99999/db").is_err());
    assert!(parse_str("   ").is_err());
    assert_eq!(
        parse_str("mysqlx://host/db").unwrap_err().0,
        "X Protocol (mysqlx://) isn't supported; use a mysql:// URL."
    );
}

#[test]
fn other_jdbc_urls_become_profiles_for_the_users_own_driver_and_known_ones_get_their_engine() {
    let oracle = parse("jdbc:oracle:thin:@host:1521:orcl");
    assert_eq!(
        (oracle.db_type, oracle.driver),
        (DatabaseType::OTHER_JDBC, Some(DriverId::Generic))
    );
    assert_eq!(
        oracle.url.as_deref(),
        Some("jdbc:oracle:thin:@host:1521:orcl")
    );

    let mssql =
        parse("jdbc:sqlserver://db:1433;databaseName=shop;user=sa;password=s3cret;encrypt=true");
    assert_eq!(mssql.db_type, DatabaseType::SQLSERVER);
    assert_eq!(
        (mssql.url.as_deref(), mssql.password.as_deref()),
        (
            Some("jdbc:sqlserver://db:1433;databaseName=shop;user=sa;encrypt=true"),
            Some("s3cret")
        )
    );

    assert_eq!(
        parse("jdbc:clickhouse://ch:8123/default").db_type,
        DatabaseType::CLICKHOUSE
    );
    assert_eq!(parse("jdbc:h2:mem:test").db_type, DatabaseType::H2);
    assert_eq!(
        parse("mongodb+srv://u:pw@cluster/db").db_type,
        DatabaseType::MONGODB
    );
    assert_eq!(
        parse("mongodb://u:pw@cluster/db").url.as_deref(),
        Some("mongodb://u@cluster/db")
    );
}

#[test]
fn strips_passwords_from_user_info_and_query() {
    assert_eq!(
        strip_secrets("jdbc:postgresql://u:p%40ss@h/db?ssl=true"),
        (
            "jdbc:postgresql://u@h/db?ssl=true".to_string(),
            Some("p@ss".to_string())
        )
    );
    assert_eq!(
        strip_secrets("jdbc:mysql://h/db?password=x"),
        ("jdbc:mysql://h/db".to_string(), Some("x".to_string()))
    );
    assert_eq!(
        strip_secrets("jdbc:mysql://h/db"),
        ("jdbc:mysql://h/db".to_string(), None)
    );
}

#[test]
fn redis_and_valkey_urls_with_tls_and_the_jdbc_form() {
    let plain = parse("redis://:pw@cache.local:6380/2");
    assert_eq!(plain.db_type, DatabaseType::REDIS);
    assert_eq!(plain.host.as_deref(), Some("cache.local"));
    assert_eq!(plain.port, Some(6380));
    assert_eq!(plain.password.as_deref(), Some("pw"));
    assert_eq!(plain.user, None);
    assert_eq!(plain.warnings.len(), 1);
    // A plain redis:// URL asks for no TLS (the Kotlin parser used to test endsWith("s") and so enabled it).
    assert!(plain.properties.is_empty());
    let tls = parse("rediss://default:pw@host");
    assert_eq!(tls.user.as_deref(), Some("default"));
    assert_eq!(tls.properties, props(&[("ssl", "true")]));
    assert_eq!(parse("valkey://localhost").db_type, DatabaseType::VALKEY);
    assert!(parse("valkey://localhost").properties.is_empty());
    assert_eq!(
        parse("valkeys://localhost").properties,
        props(&[("ssl", "true")])
    );
    assert_eq!(
        parse("jdbc:redis://localhost:6379/0").host.as_deref(),
        Some("localhost")
    );
}

#[test]
fn percent_decoding_keeps_plus_and_survives_bad_escapes() {
    let parsed = parse("postgres://u%C3%A9:a+b%zz%@h/d%20b");
    assert_eq!(parsed.user.as_deref(), Some("u\u{e9}"));
    assert_eq!(parsed.password.as_deref(), Some("a+b%zz%"));
    assert_eq!(parsed.database.as_deref(), Some("d b"));
}

#[test]
fn pasted_paths_use_the_content_too() {
    let dir = tempfile::tempdir().unwrap();
    let mut header = b"SQLite format 3\0".to_vec();
    header.resize(4096, 0);
    let bare = dir.path().join("chinook");
    fs::write(&bare, header).unwrap();
    // No extension and not absolute-looking: only its header says it's SQLite. Relative to the working directory.
    let cwd = std::env::current_dir().unwrap();
    let relative = pathdiff(&bare, &cwd);
    assert_eq!(parse(&relative).db_type, DatabaseType::SQLITE);

    let text = dir.path().join("data.db");
    fs::write(&text, "not a database").unwrap();
    assert!(
        parse(text.to_str().unwrap())
            .warnings
            .iter()
            .any(|w| w.contains("header doesn't match"))
    );
    let empty = dir.path().join("x.db");
    fs::write(&empty, "").unwrap();
    assert!(parse(empty.to_str().unwrap()).warnings.is_empty());
}

/// `target` relative to `base`, both absolute.
fn pathdiff(target: &std::path::Path, base: &std::path::Path) -> String {
    let target = target.canonicalize().unwrap();
    let base = base.canonicalize().unwrap();
    let common = target
        .components()
        .zip(base.components())
        .take_while(|(a, b)| a == b)
        .count();
    let mut parts: Vec<String> = base
        .components()
        .skip(common)
        .map(|_| "..".to_string())
        .collect();
    parts.extend(
        target
            .components()
            .skip(common)
            .map(|c| c.as_os_str().to_string_lossy().into_owned()),
    );
    parts.join("/")
}
