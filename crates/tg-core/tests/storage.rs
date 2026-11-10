//! Ports of the storage part of StorageTest, QueryLibraryTest, SessionStoreTest and ActivityTest.support.

use std::fs;
use std::io::Write;
use tg_core::activity::{ActivitySupport, SessionActivity, SessionState};
use tg_core::history::{HistoryEntry, QueryHistory};
use tg_core::queries::{QueryLibrary, connection_of, with_connection};
use tg_core::session::{Session, SessionStore, SessionTab, WindowBounds};
use tg_sql::model::DatabaseType;

#[test]
fn history_returns_most_recent_entries_first_per_connection_skipping_corrupt_lines() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("history.jsonl");
    let history = QueryHistory::new(&file);
    history
        .append(&HistoryEntry::new("a", "SELECT 1", 1, 1))
        .unwrap();
    fs::OpenOptions::new()
        .append(true)
        .open(&file)
        .unwrap()
        .write_all(b"{ broken\n")
        .unwrap();
    history
        .append(&HistoryEntry::new("b", "SELECT 2", 2, 1))
        .unwrap();
    history
        .append(&HistoryEntry {
            error: Some("boom".into()),
            ..HistoryEntry::new("a", "SELECT 3", 3, 1)
        })
        .unwrap();

    let sql = |entries: Vec<HistoryEntry>| entries.into_iter().map(|e| e.sql).collect::<Vec<_>>();
    assert_eq!(
        sql(history.recent(Some("a"), 200)),
        ["SELECT 3", "SELECT 1"]
    );
    assert_eq!(
        sql(history.recent(None, 200)),
        ["SELECT 3", "SELECT 2", "SELECT 1"]
    );
    assert_eq!(sql(history.recent(None, 2)), ["SELECT 3", "SELECT 2"]);
}

#[test]
fn history_lines_are_compact_json_without_default_fields() {
    let dir = tempfile::tempdir().unwrap();
    let history = QueryHistory::new(dir.path().join("sub").join("history.jsonl"));
    history
        .append(&HistoryEntry::new("c", "SELECT 'é'\n", 5, 0))
        .unwrap();
    history
        .append(&HistoryEntry {
            error: Some("x".into()),
            ..HistoryEntry::new("c", "q", 6, 7)
        })
        .unwrap();
    assert_eq!(
        fs::read_to_string(history.file()).unwrap(),
        "{\"connectionId\":\"c\",\"sql\":\"SELECT 'é'\\n\",\"executedAt\":5,\"elapsedMillis\":0}\n\
         {\"connectionId\":\"c\",\"sql\":\"q\",\"executedAt\":6,\"elapsedMillis\":7,\"error\":\"x\"}\n"
    );
    assert_eq!(
        QueryHistory::new(dir.path().join("none")).recent(None, 5),
        vec![]
    );
}

fn library() -> (tempfile::TempDir, QueryLibrary) {
    let dir = tempfile::tempdir().unwrap();
    let library = QueryLibrary::new(dir.path());
    (dir, library)
}

#[test]
fn saves_into_folders_and_lists_by_folder_and_name() {
    let (_dir, library) = library();
    library.save("Top users", "", "SELECT 1", None).unwrap();
    library
        .save("monthly", "reports/finance", "SELECT 2", Some("Prod DB"))
        .unwrap();
    let queries = library.list();
    assert_eq!(
        queries
            .iter()
            .map(|q| (q.folder.as_str(), q.name.as_str()))
            .collect::<Vec<_>>(),
        [("", "Top users"), ("reports/finance", "monthly")]
    );
    assert_eq!(queries[1].connection.as_deref(), Some("Prod DB"));
    assert_eq!(
        fs::read_to_string(&queries[1].file).unwrap(),
        "-- connection: Prod DB\nSELECT 2"
    );
    assert_eq!(queries[0].connection, None);
}

#[test]
fn connection_line_is_replaced_or_removed() {
    assert_eq!(
        with_connection("-- connection: A\nSELECT 1", Some("B")),
        "-- connection: B\nSELECT 1"
    );
    assert_eq!(
        with_connection("-- connection: A\nSELECT 1", None),
        "SELECT 1"
    );
    assert_eq!(
        with_connection("-- just a comment\nSELECT 1", None),
        "-- just a comment\nSELECT 1"
    );
    assert_eq!(
        connection_of("--CONNECTION:   My DB  \nSELECT 1").as_deref(),
        Some("My DB")
    );
    assert_eq!(connection_of("SELECT 1\n-- connection: A"), None);
}

#[test]
fn names_become_safe_file_names() {
    let (_dir, library) = library();
    let query = library.save("a/b: c?", "../x", "SELECT 1", None).unwrap();
    assert_eq!(query.name, "a_b_ c_");
    assert!(query.file.starts_with(&library.root));
    assert_eq!(library.file_for("", ""), library.root.join("query.sql"));
}

#[test]
fn rename_moves_the_file_and_refuses_to_overwrite() {
    let (_dir, library) = library();
    let first = library.save("one", "", "SELECT 1", None).unwrap();
    library.save("two", "", "SELECT 2", None).unwrap();
    let refused = library.rename(&first, "two", None).unwrap_err();
    assert_eq!(refused.kind(), std::io::ErrorKind::AlreadyExists);
    assert_eq!(
        refused.to_string(),
        "A query named two already exists there"
    );

    let moved = library.rename(&first, "uno", Some("spanish")).unwrap();
    assert!(!first.file.exists());
    assert_eq!(
        (moved.folder.as_str(), moved.name.as_str()),
        ("spanish", "uno")
    );
    library.delete(&moved).unwrap();
    library.delete(&moved).unwrap();
    assert_eq!(
        library
            .list()
            .iter()
            .map(|q| q.name.as_str())
            .collect::<Vec<_>>(),
        ["two"]
    );
}

fn session_store() -> (tempfile::TempDir, SessionStore) {
    let dir = tempfile::tempdir().unwrap();
    let store = SessionStore::new(dir.path().join("session.json"));
    (dir, store)
}

#[test]
fn session_round_trips_tabs() {
    let (_dir, store) = session_store();
    let session = Session {
        tabs: vec![
            SessionTab::Console {
                connection_id: "pg".into(),
                text: "SELECT 1;\n".into(),
                file: Some("/q/a.sql".into()),
                caret: 3,
                saved_text: Some("SELECT 1;".into()),
            },
            SessionTab::Table {
                connection_id: "pg".into(),
                catalog: None,
                schema: Some("public".into()),
                name: "orders".into(),
                structure: false,
                filter: Some("status = 'paid'".into()),
            },
        ],
        selected: 1,
        left_tool: None,
        connection_id: Some("pg".into()),
        window: Some(WindowBounds {
            x: 10.5,
            y: 20.0,
            width: 1280.0,
            height: 800.0,
            maximized: false,
        }),
        sidebar_split: Some(0.25),
        ..Session::default()
    };
    store.save(&session).unwrap();
    assert_eq!(store.load(), session);
}

#[test]
fn a_broken_or_missing_session_file_is_an_empty_session() {
    let (_dir, store) = session_store();
    assert_eq!(store.load(), Session::default());
    fs::write(store.file(), "{ not json").unwrap();
    assert_eq!(store.load(), Session::default());
}

#[test]
fn the_session_file_has_the_type_first_four_space_indent_and_only_non_defaults() {
    let (_dir, store) = session_store();
    store
        .save(&Session {
            tabs: vec![SessionTab::Console {
                connection_id: "c".into(),
                text: String::new(),
                file: None,
                caret: 0,
                saved_text: None,
            }],
            left_tool: Some("Explorer".into()),
            ..Session::default()
        })
        .unwrap();
    assert_eq!(
        fs::read_to_string(store.file()).unwrap(),
        "{\n    \"tabs\": [\n        {\n            \"type\": \"console\",\n            \"connectionId\": \"c\",\n            \"text\": \"\"\n        }\n    ]\n}"
    );
    // `leftTool` missing means the default; present and null means hidden.
    fs::write(store.file(), "{}").unwrap();
    assert_eq!(store.load().left_tool.as_deref(), Some("Explorer"));
    fs::write(store.file(), r#"{"leftTool":null,"future":1}"#).unwrap();
    assert_eq!(store.load().left_tool, None);
}

#[test]
fn activity_support() {
    use DatabaseType as T;
    for supported in [T::POSTGRESQL, T::SUPABASE, T::MYSQL, T::MARIADB] {
        assert_eq!(
            ActivitySupport::of(&supported),
            ActivitySupport::Supported,
            "{supported}"
        );
    }
    for not_yet in [T::TIDB, T::COCKROACHDB, T::SQLSERVER] {
        assert!(
            matches!(ActivitySupport::of(&not_yet), ActivitySupport::NotYet(_)),
            "{not_yet}"
        );
    }
    for na in [
        T::SQLITE,
        T::DUCKDB,
        T::H2,
        T::REDIS,
        T::MONGODB,
        T::OTHER_JDBC,
    ] {
        assert_eq!(
            ActivitySupport::of(&na),
            ActivitySupport::NotApplicable,
            "{na}"
        );
    }
    assert_eq!(
        ActivitySupport::of(&T::TIDB),
        ActivitySupport::NotYet(
            "The activity monitor doesn't support TiDB yet. It supports PostgreSQL, MySQL and MariaDB.".into()
        )
    );
}

#[test]
fn a_sessions_running_time_depends_on_its_state() {
    use chrono::{Duration, TimeZone, Utc};
    let now = Utc.with_ymd_and_hms(2026, 10, 31, 10, 0, 0).unwrap();
    let session = |state| SessionActivity {
        id: "1".into(),
        user: None,
        database: None,
        client: None,
        application: None,
        state,
        wait: None,
        query: None,
        query_start: Some(now - Duration::seconds(30)),
        transaction_start: Some(now - Duration::seconds(90)),
        blocked_by: vec![],
        own: false,
    };
    assert_eq!(
        session(SessionState::Active).running_for(now),
        Some(Duration::seconds(30))
    );
    assert_eq!(
        session(SessionState::IdleInTransaction).running_for(now),
        Some(Duration::seconds(90))
    );
    assert_eq!(session(SessionState::Idle).running_for(now), None);
    assert_eq!(
        session(SessionState::Active).running_for(now - Duration::seconds(60)),
        Some(Duration::zero())
    );
}
