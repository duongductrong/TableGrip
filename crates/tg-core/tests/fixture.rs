//! Reads the sample `~/.tablegrip` the Kotlin stores wrote (`conformance/core/fixtures/home`, recorded by
//! `StorageFixtureTest`), writes it back through the Rust stores into a temporary directory, and compares: the JSON must
//! be equivalent, and the text is expected to be byte-identical too.

use serde_json::Value;
use std::fs;
use std::path::{Path, PathBuf};
use tg_core::TableGripPaths;
use tg_core::connection::{ConnectionStore, InMemorySecretStore};
use tg_core::history::QueryHistory;
use tg_core::queries::QueryLibrary;
use tg_core::session::SessionStore;
use tg_core::settings::{Settings, SettingsStore};
use tg_core::theme::ThemeJson;

fn fixture() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance/core/fixtures/home")
}

/// The two texts must parse to the same JSON; returns whether they are the same bytes.
fn equivalent(what: &str, fixture_text: &str, written: &str) -> bool {
    let (a, b): (Value, Value) = (
        serde_json::from_str(fixture_text).unwrap_or_else(|e| panic!("{what}: fixture: {e}")),
        serde_json::from_str(written).unwrap_or_else(|e| panic!("{what}: written: {e}")),
    );
    assert_eq!(a, b, "{what}: JSON differs");
    let same = fixture_text == written;
    println!(
        "{what}: JSON equivalent, bytes {}",
        if same { "identical" } else { "DIFFER" }
    );
    same
}

fn read(path: &Path) -> String {
    fs::read_to_string(path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

#[test]
fn the_fixture_round_trips_through_the_rust_stores() {
    let home = TableGripPaths::new(fixture());
    let out_dir = tempfile::tempdir().unwrap();
    let out = TableGripPaths::new(out_dir.path());
    let mut identical = Vec::new();

    // Connections: load everything, save each profile again over a copy of the file, so profiles this version
    // doesn't know (the fixture has one from "the future") must survive.
    fs::copy(home.connections(), out.connections()).unwrap();
    let store = ConnectionStore::new(out.connections(), Box::new(InMemorySecretStore::new()));
    let configs = store.load().unwrap();
    assert!(configs.len() >= 30, "{} connections", configs.len());
    for config in &configs {
        store.save(config, None, None).unwrap();
    }
    assert_eq!(store.load().unwrap(), configs);
    // Every engine is there, SSH in all three forms, data files with options.
    for db in tg_sql::model::DatabaseType::ENTRIES {
        assert!(configs.iter().any(|c| c.db_type == *db), "no {db} profile");
    }
    assert!(configs.iter().filter(|c| c.ssh.is_some()).count() >= 3);
    identical.push(equivalent(
        "connections.json",
        &read(&home.connections()),
        &read(&out.connections()),
    ));
    // Re-saving into an empty directory loses only the unknown profile.
    let fresh = tempfile::tempdir().unwrap();
    let fresh_store = ConnectionStore::new(
        fresh.path().join("connections.json"),
        Box::new(InMemorySecretStore::new()),
    );
    for config in &configs {
        fresh_store.save(config, None, None).unwrap();
    }
    let kept: Vec<Value> =
        serde_json::from_str(&read(&fresh.path().join("connections.json"))).unwrap();
    assert_eq!(kept.len(), configs.len());

    // Settings.
    let settings_store = SettingsStore::new(home.settings());
    let settings = settings_store.load();
    assert_eq!(settings_store.load_error(), None);
    assert_ne!(settings, Settings::default());
    assert_eq!(settings.backup_schedules.len(), 3);
    assert_eq!(settings.key_bindings.len(), 2);
    let settings_out = SettingsStore::new(out.settings());
    assert!(settings_out.save(&settings).unwrap());
    identical.push(equivalent(
        "settings.json",
        &read(&home.settings()),
        &read(&out.settings()),
    ));
    assert_eq!(settings_out.load(), settings);

    // Themes.
    let themes = ThemeJson::load_directory(&home.themes());
    assert_eq!(themes.errors, vec![]);
    assert_eq!(themes.families.len(), 1);
    fs::create_dir_all(out.themes()).unwrap();
    fs::write(
        out.themes().join("mine.json"),
        ThemeJson::encode(&themes.families[0]),
    )
    .unwrap();
    identical.push(equivalent(
        "themes/mine.json",
        &read(&home.themes().join("mine.json")),
        &read(&out.themes().join("mine.json")),
    ));

    // History.
    let entries = QueryHistory::new(home.history()).recent(None, 1000);
    assert_eq!(entries.len(), 3);
    let history_out = QueryHistory::new(out.history());
    for entry in entries.iter().rev() {
        history_out.append(entry).unwrap();
    }
    let (fixture_history, written_history) = (read(&home.history()), read(&out.history()));
    for (a, b) in fixture_history.lines().zip(written_history.lines()) {
        equivalent("history.jsonl line", a, b);
    }
    identical.push(fixture_history == written_history);

    // Saved queries.
    let library = QueryLibrary::new(home.queries());
    let queries = library.list();
    assert_eq!(queries.len(), 3);
    let library_out = QueryLibrary::new(out.queries());
    for q in &queries {
        let saved = library_out
            .save(&q.name, &q.folder, &q.sql, q.connection.as_deref())
            .unwrap();
        assert_eq!(
            (&saved.name, &saved.folder, &saved.sql, &saved.connection),
            (&q.name, &q.folder, &q.sql, &q.connection)
        );
        let relative = q.file.strip_prefix(home.queries()).unwrap();
        assert_eq!(
            read(&q.file),
            read(&out.queries().join(relative)),
            "{}",
            relative.display()
        );
    }

    // Session.
    let session = SessionStore::new(home.session()).load();
    assert_eq!(session.tabs.len(), 4);
    assert_eq!(session.left_tool, None);
    SessionStore::new(out.session()).save(&session).unwrap();
    identical.push(equivalent(
        "session.json",
        &read(&home.session()),
        &read(&out.session()),
    ));

    assert!(
        identical.iter().all(|same| *same),
        "JSON is equivalent but some bytes differ: {identical:?}"
    );
}
