//! Ports of ConnectionStoreTest, AiAccessTest and DatabaseFilesTest (the part that doesn't parse strings), plus checks
//! that the store keeps what it doesn't understand.

use std::fs;
use std::path::Path;
use tg_core::connection::files;
use tg_core::connection::{
    AiAccess, ConnectionConfig, ConnectionStore, DataFileOptions, DriverId, Environment,
    InMemorySecretStore, SshAuth, SshConfig,
};
use tg_core::settings::Settings;
use tg_sql::model::DatabaseType;

fn pg() -> ConnectionConfig {
    ConnectionConfig {
        database: "app".into(),
        user: "me".into(),
        ..ConnectionConfig::new("pg", "Local PG", DatabaseType::POSTGRESQL)
    }
}

fn store(dir: &tempfile::TempDir) -> ConnectionStore {
    ConnectionStore::new(
        dir.path().join("connections.json"),
        Box::new(InMemorySecretStore::new()),
    )
}

#[test]
fn saves_profiles_to_disk_and_passwords_to_the_secret_store_only() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    store.save(&pg(), Some("s3cret"), None).unwrap();

    assert_eq!(store.load().unwrap(), vec![pg()]);
    assert_eq!(store.password("pg").as_deref(), Some("s3cret"));
    assert!(!fs::read_to_string(store.file()).unwrap().contains("s3cret"));
}

#[test]
fn updates_in_place_keeps_password_when_none_and_clears_it_when_empty() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    store.save(&pg(), Some("s3cret"), None).unwrap();

    let renamed = ConnectionConfig {
        name: "Renamed".into(),
        ..pg()
    };
    assert_eq!(
        store.save(&renamed, None, None).unwrap(),
        vec![renamed.clone()]
    );
    assert_eq!(store.password("pg").as_deref(), Some("s3cret"));

    store.save(&renamed, Some(""), None).unwrap();
    assert_eq!(store.password("pg"), None);
}

#[test]
fn delete_removes_profile_and_password() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    store.save(&pg(), Some("s3cret"), Some("phrase")).unwrap();
    assert_eq!(store.ssh_secret("pg").as_deref(), Some("phrase"));

    assert_eq!(store.delete("pg").unwrap(), vec![]);
    assert_eq!(store.password("pg"), None);
    assert_eq!(store.ssh_secret("pg"), None);
}

#[test]
fn a_profile_this_version_doesnt_know_is_skipped_but_kept_in_the_file() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    let future = r#"{"id":"f","name":"Future","type":"QuantumDB","extra":{"nested":[1,2]}}"#;
    fs::write(store.file(), format!("[{future}]")).unwrap();
    assert_eq!(store.load().unwrap(), vec![]);

    store.save(&pg(), None, None).unwrap();
    assert_eq!(store.load().unwrap(), vec![pg()]);
    let text: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(store.file()).unwrap()).unwrap();
    assert_eq!(text[0]["id"], "pg");
    assert_eq!(
        text[1],
        serde_json::from_str::<serde_json::Value>(future).unwrap()
    );

    store.delete("pg").unwrap();
    let text: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(store.file()).unwrap()).unwrap();
    assert_eq!(text.as_array().unwrap().len(), 1);
}

#[test]
fn only_fields_that_differ_from_the_defaults_are_written_in_declaration_order() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    let config = ConnectionConfig {
        port: Some(5433),
        environment: Environment::Production,
        read_only: true,
        ssh: Some(SshConfig {
            auth: SshAuth::Password,
            ..SshConfig::new("bastion", "ops")
        }),
        data_file: Some(DataFileOptions::default()),
        ..ConnectionConfig::new("a", "A", DatabaseType::POSTGRESQL)
    };
    store.save(&config, None, None).unwrap();
    let expected = r#"[
    {
        "id": "a",
        "name": "A",
        "type": "PostgreSQL",
        "port": 5433,
        "environment": "Production",
        "readOnly": true,
        "ssh": {
            "host": "bastion",
            "user": "ops",
            "auth": "Password"
        },
        "dataFile": {}
    }
]"#;
    assert_eq!(fs::read_to_string(store.file()).unwrap(), expected);
    assert_eq!(store.load().unwrap(), vec![config]);
}

#[test]
fn fields_missing_from_an_older_file_take_their_defaults() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    fs::write(
        store.file(),
        r#"[{"id":"c","name":"C","type":"PostgreSQL","somethingNew":true}]"#,
    )
    .unwrap();
    let loaded = store.load().unwrap();
    assert_eq!(
        loaded,
        vec![ConnectionConfig::new("c", "C", DatabaseType::POSTGRESQL)]
    );
    assert_eq!(loaded[0].host, "localhost");
    assert_eq!(loaded[0].effective_driver(), DriverId::PostgreSQL);
}

#[test]
fn the_effective_driver_falls_back_to_the_types_default() {
    let mut c = ConnectionConfig::new("c", "C", DatabaseType::MYSQL);
    assert_eq!(c.effective_driver(), DriverId::MariaDB);
    c.driver = Some(DriverId::MySQL);
    assert_eq!(c.effective_driver(), DriverId::MySQL);
    c.driver = Some(DriverId::SqlServer);
    assert_eq!(c.effective_driver(), DriverId::MariaDB);
}

fn config_with(environment: Environment, access: Option<AiAccess>) -> ConnectionConfig {
    ConnectionConfig {
        environment,
        ai_access: access,
        ..ConnectionConfig::new("c", "C", DatabaseType::POSTGRESQL)
    }
}

#[test]
fn ai_access_defaults_share_no_data_and_unknown_names_never_share_more() {
    assert_eq!(
        AiAccess::of(&Settings::default().ai_access),
        AiAccess::SchemaOnly
    );
    assert_eq!(AiAccess::of("Everything"), AiAccess::SchemaOnly);
    let loaded: Settings = tg_core::json::from_lenient_str("{}").unwrap();
    assert_eq!(AiAccess::of(&loaded.ai_access), AiAccess::SchemaOnly);
    let config: ConnectionConfig =
        serde_json::from_str(r#"{"id":"c","name":"C","type":"PostgreSQL"}"#).unwrap();
    assert_eq!(config.ai_access, None);
}

#[test]
fn a_connections_own_ai_choice_wins_otherwise_production_never_shares_data() {
    use AiAccess::*;
    let eff = AiAccess::effective;
    assert_eq!(
        eff(
            SchemaAndSamples,
            Some(&config_with(Environment::None, None))
        ),
        SchemaAndSamples
    );
    assert_eq!(
        eff(
            SchemaAndSamples,
            Some(&config_with(Environment::Production, None))
        ),
        SchemaOnly
    );
    assert_eq!(
        eff(Off, Some(&config_with(Environment::Production, None))),
        Off
    );
    assert_eq!(
        eff(
            SchemaOnly,
            Some(&config_with(
                Environment::Production,
                Some(SchemaAndSamples)
            ))
        ),
        SchemaAndSamples
    );
    assert_eq!(
        eff(
            SchemaAndSamples,
            Some(&config_with(Environment::None, Some(Off)))
        ),
        Off
    );
    assert_eq!(eff(SchemaOnly, None), SchemaOnly);
    assert!(SchemaOnly.shares_schema() && !SchemaOnly.shares_data() && !Off.shares_schema());
}

/// A file with SQLite's header, which is all detection reads.
fn sqlite_file(dir: &Path, name: &str) -> std::path::PathBuf {
    let file = dir.join(name);
    let mut bytes = b"SQLite format 3\0".to_vec();
    bytes.resize(4096, 0);
    fs::write(&file, bytes).unwrap();
    file
}

#[test]
fn files_are_recognised_by_content_not_name() {
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(
        files::detect(&sqlite_file(dir.path(), "chinook")),
        Some(DatabaseType::SQLITE)
    );
    fs::write(dir.path().join("notes.db"), "just text").unwrap();
    assert_eq!(files::detect(&dir.path().join("notes.db")), None);
    assert_eq!(files::detect(&dir.path().join("missing.db")), None);
    assert_eq!(files::detect(dir.path()), None);

    let mut duck = vec![0u8; 8];
    duck.extend(b"DUCK");
    duck.resize(64, 0);
    fs::write(dir.path().join("a.duckdb"), duck).unwrap();
    assert_eq!(
        files::detect(&dir.path().join("a.duckdb")),
        Some(DatabaseType::DUCKDB)
    );
}

#[test]
fn missing_and_empty_files_are_fine_to_open_other_files_are_not() {
    let dir = tempfile::tempdir().unwrap();
    let sqlite = DatabaseType::SQLITE;
    assert_eq!(files::mismatch(&dir.path().join("new.db"), sqlite), None);
    fs::write(dir.path().join("empty.db"), "").unwrap();
    assert_eq!(files::mismatch(&dir.path().join("empty.db"), sqlite), None);
    assert_eq!(
        files::mismatch(&sqlite_file(dir.path(), "app.sqlite"), sqlite),
        None
    );
    fs::write(dir.path().join("data.db"), "id,name\n1,a\n").unwrap();
    assert_eq!(
        files::mismatch(&dir.path().join("data.db"), sqlite).as_deref(),
        Some("data.db isn't a SQLite database: its header doesn't match.")
    );
    assert_eq!(
        files::mismatch(&sqlite_file(dir.path(), "x.db"), DatabaseType::DUCKDB).as_deref(),
        Some("x.db is a SQLite database, not DuckDB.")
    );
}
