//! The workspace comes back as the Kotlin app saved it, and is saved back the same (`SessionKeeperTest`).

use std::path::PathBuf;

use tg_app_logic::session::{restore, restored_agent_split, restored_sidebar_split, snapshot};
use tg_app_logic::workspace::TabSpec;
use tg_app_logic::{AppModel, LeftTool};
use tg_core::connection::ConnectionStore;
use tg_core::connection::InMemorySecretStore;
use tg_core::session::{Session, SessionStore, WindowBounds};
use tg_core::settings::Settings;

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../conformance/core/fixtures/home")
        .join(name)
}

fn app(settings: Settings) -> AppModel {
    let store = ConnectionStore::new(
        fixture("connections.json"),
        Box::new(InMemorySecretStore::new()),
    );
    AppModel::new(settings, store.load().unwrap())
}

#[test]
fn kotlins_session_reopens_its_tabs_and_saves_back_the_same() {
    let saved = SessionStore::new(fixture("session.json")).load();
    let mut app = app(Settings::default());
    let restored = restore(&mut app, &saved);

    let titles: Vec<&str> = app
        .workspace
        .tabs
        .iter()
        .map(|t| t.title.as_str())
        .collect();
    assert_eq!(
        titles,
        [
            "PostgreSQL sample",
            "Via bastion (key) (2)",
            "orders",
            "users"
        ]
    );
    assert_eq!(app.workspace.selected().unwrap().title, "orders");
    assert!(
        matches!(&app.workspace.selected().unwrap().spec, TabSpec::Table { structure: true, filter: Some(f), .. } if f == "status = 'paid'")
    );
    // The selected tab's connection is current.
    assert_eq!(app.current_connection_id.as_deref(), Some("conn-00"));
    // "LastUsed" with no tool saved: the tool window stays hidden.
    assert_eq!(app.left_tool, None);
    assert!(
        !app.agent_open,
        "the agent opens only when Settings say LastUsed or Open"
    );
    assert_eq!(restored.window.as_ref().unwrap().width, 1440.0);

    let again = snapshot(&app, saved.window.clone());
    assert_eq!(again.tabs, saved.tabs);
    assert_eq!(again.selected, saved.selected);
    assert_eq!(again.agent_chat, saved.agent_chat);
    assert_eq!(again.sidebar_split, saved.sidebar_split);

    // And the file it writes is the Kotlin one, byte for byte, once the parts it decides itself agree.
    let dir = tempfile::tempdir().unwrap();
    let out = SessionStore::new(dir.path().join("session.json"));
    out.save(&Session {
        agent_open: true,
        left_tool: None,
        ..again
    })
    .unwrap();
    assert_eq!(
        std::fs::read_to_string(out.file()).unwrap(),
        std::fs::read_to_string(fixture("session.json")).unwrap()
    );
}

#[test]
fn tabs_of_deleted_connections_are_dropped_and_settings_decide_the_rest() {
    let mut saved = SessionStore::new(fixture("session.json")).load();
    saved.tabs[0] = tg_core::session::SessionTab::Table {
        connection_id: "gone".into(),
        catalog: None,
        schema: None,
        name: "t".into(),
        structure: false,
        filter: None,
    };
    saved.selected = 0;
    saved.left_tool = Some("History".into());
    saved.sidebar_split = Some(0.95);
    saved.agent_split = Some(0.05);
    let mut app = app(Settings {
        startup_agent: "LastUsed".into(),
        ..Settings::default()
    });
    restore(&mut app, &saved);
    assert_eq!(app.workspace.tabs.len(), 3);
    // The selected tab was dropped: the last reopened one is in front.
    assert_eq!(app.workspace.selected().unwrap().title, "users");
    assert_eq!(app.left_tool, Some(LeftTool::History));
    assert!(app.agent_open);
    assert_eq!(app.sidebar_split, restored_sidebar_split(0.95));
    assert_eq!(app.agent_split, restored_agent_split(0.05));

    let mut app = self::app(Settings {
        restore_tabs: false,
        startup_tool: "Queries".into(),
        remember_window_bounds: false,
        ..Settings::default()
    });
    let restored = restore(&mut app, &saved);
    assert!(app.workspace.tabs.is_empty());
    assert_eq!(app.left_tool, Some(LeftTool::Queries));
    assert_eq!(restored.window, None);
    assert_eq!(app.current_connection_id.as_deref(), Some("conn-00"));
}

#[test]
fn a_small_saved_window_grows_to_the_minimum() {
    let saved = Session {
        window: Some(WindowBounds {
            x: 0.0,
            y: 0.0,
            width: 100.0,
            height: 100.0,
            maximized: true,
        }),
        ..Session::default()
    };
    let mut app = app(Settings::default());
    let w = restore(&mut app, &saved).window.unwrap();
    assert_eq!((w.width, w.height, w.maximized), (640.0, 420.0, true));
    assert_eq!(app.left_tool, Some(LeftTool::Explorer));
}
