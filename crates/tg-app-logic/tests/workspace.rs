//! Tabs and the window's commands (`WorkspaceViewModelTest`, `Main.kt`'s shortcuts).

use tg_app_logic::workspace::TabSpec;
use tg_app_logic::{AppModel, Effect, LeftTool};
use tg_core::connection::ConnectionConfig;
use tg_core::settings::Settings;
use tg_sql::model::{DatabaseType, Namespace, TableRef};

fn config(id: &str, name: &str, t: DatabaseType) -> ConnectionConfig {
    ConnectionConfig::new(id, name, t)
}

fn app() -> AppModel {
    AppModel::new(
        Settings::default(),
        vec![
            config("a", "alpha", DatabaseType::POSTGRESQL),
            config("b", "beta", DatabaseType::SQLITE),
            config("m", "mongo", DatabaseType::MONGODB),
        ],
    )
}

fn users() -> TableRef {
    TableRef::new(Namespace::new(None, Some("public")), "users")
}

#[test]
fn consoles_are_numbered_and_tables_open_once() {
    let mut app = app();
    let a = app.connections.by_id("a").unwrap().clone();
    let first = app.workspace.open_console(&a, "", 0, None);
    let second = app.workspace.open_console(&a, "", 0, None);
    assert_eq!(app.workspace.tab(first).unwrap().title, "alpha");
    assert_eq!(app.workspace.tab(second).unwrap().title, "alpha (2)");

    let t1 = app.workspace.open_table(&a, &users(), None);
    app.workspace.select(first);
    let t2 = app.workspace.open_table(&a, &users(), Some(true));
    assert_eq!(t1, t2, "an open table is focused, not opened twice");
    assert_eq!(app.workspace.selected().unwrap().key, t1);
    assert!(matches!(
        app.workspace.tab(t1).unwrap().spec,
        TabSpec::Table {
            structure: true,
            ..
        }
    ));

    // A document store's console gets a database: the profile's, or "test".
    let m = app.connections.by_id("m").unwrap().clone();
    let k = app.workspace.open_console(&m, "", 0, None);
    assert!(
        matches!(&app.workspace.tab(k).unwrap().spec, TabSpec::Console { database: Some(d), .. } if d == "test")
    );
    // Only consoles and tables go into the session.
    let c = app.workspace.open_collection(&m, "test", "people");
    assert!(app.workspace.tab(c).unwrap().spec.to_session().is_none());
}

#[test]
fn closing_selects_the_next_tab_and_switching_wraps() {
    let mut app = app();
    let a = app.connections.by_id("a").unwrap().clone();
    let b = app.connections.by_id("b").unwrap().clone();
    let k1 = app.workspace.open_console(&a, "", 0, None);
    let k2 = app.workspace.open_console(&b, "", 0, None);
    let k3 = app.workspace.open_table(&a, &users(), None);
    app.select_tab(k2);
    assert_eq!(app.current_connection_id.as_deref(), Some("b"));
    app.close_tab(k2);
    assert_eq!(app.workspace.selected().unwrap().key, k3);
    assert_eq!(app.current_connection_id.as_deref(), Some("a"));

    app.window_command("NextTab").unwrap();
    assert_eq!(app.workspace.selected().unwrap().key, k1, "wraps around");
    app.window_command("PreviousTab").unwrap();
    assert_eq!(app.workspace.selected().unwrap().key, k3);

    app.workspace.move_tab(1, 0);
    assert_eq!(
        app.workspace.tabs.iter().map(|t| t.key).collect::<Vec<_>>(),
        [k3, k1]
    );
    app.window_command("CloseTab").unwrap();
    assert_eq!(app.workspace.tabs.len(), 1);
}

#[test]
fn window_commands_toggle_tools_settings_and_zoom() {
    let mut app = app();
    assert_eq!(app.window_command("Explorer"), Some(vec![]));
    assert_eq!(app.left_tool, None);
    app.window_command("History").unwrap();
    assert_eq!(app.left_tool, Some(LeftTool::History));
    app.window_command("ToggleSidebar").unwrap();
    assert_eq!(app.left_tool, None);
    app.window_command("ToggleSidebar").unwrap();
    assert_eq!(
        app.left_tool,
        Some(LeftTool::History),
        "the sidebar brings back the tool it hid"
    );

    app.window_command("Settings").unwrap();
    assert!(app.settings_open);
    // ⌘W leaves settings rather than closing a hidden tab.
    let a = app.connections.by_id("a").unwrap().clone();
    app.workspace.open_console(&a, "", 0, None);
    app.window_command("CloseTab").unwrap();
    assert!(!app.settings_open);
    assert_eq!(app.workspace.tabs.len(), 1);
    // A tool button from settings goes back with that tool shown.
    app.settings_open = true;
    app.toggle_tool(LeftTool::History);
    assert_eq!(app.left_tool, Some(LeftTool::History));

    assert_eq!(app.window_command("ZoomIn"), Some(vec![Effect::Zoom(110)]));
    assert_eq!(app.window_command("ZoomOut"), Some(vec![Effect::Zoom(90)]));
    assert_eq!(
        app.window_command("ResetZoom"),
        Some(vec![Effect::Zoom(100)])
    );
    assert_eq!(
        app.window_command("RunStatement"),
        None,
        "the console's, not the window's"
    );

    // A new console goes to the current connection, which is the first one before any is picked.
    app.window_command("NewConsole").unwrap();
    assert_eq!(app.workspace.selected().unwrap().spec.connection_id(), "a");
}

#[test]
fn breadcrumbs_follow_the_selected_tab() {
    let mut app = app();
    let a = app.connections.by_id("a").unwrap().clone();
    assert_eq!(app.breadcrumbs(), ["alpha"]);
    let k = app.workspace.open_table(&a, &users(), None);
    app.select_tab(k);
    assert_eq!(app.breadcrumbs(), ["alpha", "public", "users"]);
}
