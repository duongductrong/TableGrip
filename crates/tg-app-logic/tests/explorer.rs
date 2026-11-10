//! The explorer over a real SQLite file, connected through the registry on tokio (`ExplorerViewModelTest`).

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use tg_app_logic::app::MenuAction;
use tg_app_logic::explorer::{Explorer, LoadJob, NodeKind, connection_key, connection_of, load};
use tg_app_logic::workspace::TabSpec;
use tg_app_logic::{AppModel, Effect, Registry, Status};
use tg_core::TableGripPaths;
use tg_core::connection::ConnectionConfig;
use tg_core::settings::Settings;
use tg_driver::Secrets;
use tg_sql::model::DatabaseType;
use tg_ui_logic::Item;

fn sqlite_profile(dir: &std::path::Path) -> ConnectionConfig {
    let file = dir.join("app.db");
    let mut c = ConnectionConfig::new("s", "local", DatabaseType::SQLITE);
    c.database = file.to_string_lossy().into_owned();
    c
}

async fn seed(registry: &Registry, config: &ConnectionConfig) {
    let live = registry.connection(config).await.unwrap();
    let mut session = live.relational().unwrap().open_session().await.unwrap();
    for sql in [
        "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL UNIQUE)",
        "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id))",
        "CREATE VIEW recent AS SELECT * FROM orders",
    ] {
        session
            .execute(sql, tg_driver::ExecOptions::default())
            .await
            .unwrap();
    }
}

fn labels(
    items: &[Item<String>],
    e: &tg_app_logic::explorer::Built,
    profiles: &[ConnectionConfig],
) -> Vec<String> {
    items
        .iter()
        .map(|i| e.nodes[&i.key].label(profiles))
        .collect()
}

async fn run(
    registry: &Registry,
    explorer: &mut Explorer,
    profiles: &[ConnectionConfig],
    job: LoadJob,
) {
    let mut jobs = vec![job];
    while let Some(job) = jobs.pop() {
        let config = profiles.iter().find(|c| c.id == job.connection_id).unwrap();
        let result = match registry.connection(config).await {
            Ok(live) => load(&live, &job).await,
            Err(e) => Err(e),
        };
        jobs.extend(explorer.loaded(&job, result, profiles));
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn connections_namespaces_tables_and_columns_load_lazily() {
    let dir = tempfile::tempdir().unwrap();
    let config = sqlite_profile(dir.path());
    let profiles = vec![config.clone()];
    let calls = Arc::new(AtomicUsize::new(0));
    let counted = calls.clone();
    let registry = Registry::new(
        tokio::runtime::Handle::current(),
        TableGripPaths::new(dir.path().join("home")),
        Arc::new(move |_| {
            counted.fetch_add(1, Ordering::SeqCst);
            Secrets::default()
        }),
    );
    // Callers asking at once share one attempt.
    let (a, b) = tokio::join!(registry.connection(&config), registry.connection(&config));
    assert!(a.is_ok() && b.is_ok());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    assert_eq!(registry.status("s"), Status::Connected);
    seed(&registry, &config).await;

    let mut explorer = Explorer::default();
    let built = explorer.build(&profiles);
    assert_eq!(labels(&built.items, &built, &profiles), ["local"]);
    assert!(
        built.items[0].node,
        "a connection is a node before anything loads"
    );

    // Opening the connection loads its namespaces; SQLite's lone `main` opens by itself.
    let job = explorer.opened(&connection_key("s"), &profiles).unwrap();
    assert!(
        explorer.opened(&connection_key("s"), &profiles).is_none(),
        "already loading"
    );
    run(&registry, &mut explorer, &profiles, job).await;
    let (open, _) = explorer.take_pending();
    assert_eq!(open.len(), 1);
    let built = explorer.build(&profiles);
    let schema = &built.items[0].children[0];
    assert_eq!(built.nodes[&schema.key].label(&profiles), "main");
    let folders = labels(&schema.children, &built, &profiles);
    assert_eq!(folders, ["tables", "views"]);
    let tables = labels(&schema.children[0].children, &built, &profiles);
    assert_eq!(tables, ["orders", "users"]);
    assert_eq!(
        labels(&schema.children[1].children, &built, &profiles),
        ["recent"]
    );

    // A table loads its columns, indexes and foreign keys.
    let orders = schema.children[0].children[0].key.clone();
    assert_eq!(connection_of(&orders), Some("s"));
    let job = explorer.opened(&orders, &profiles).unwrap();
    run(&registry, &mut explorer, &profiles, job).await;
    let built = explorer.build(&profiles);
    let orders_item = &built.items[0].children[0].children[0].children[0];
    assert_eq!(
        labels(&orders_item.children, &built, &profiles),
        ["columns", "foreign keys"]
    );
    let columns = &orders_item.children[0].children;
    assert_eq!(labels(columns, &built, &profiles), ["id", "user_id"]);
    assert_eq!(
        built.nodes[&columns[0].key].detail().as_deref(),
        Some("integer · PK")
    );

    // Search keeps the path to a loaded match.
    explorer.set_search("user_");
    let built = explorer.build(&profiles);
    let users_path = &built.items[0].children[0].children[0];
    assert_eq!(labels(&users_path.children, &built, &profiles), ["orders"]);
    assert!(explorer.search_match_ancestors(&profiles).contains(&orders));
    explorer.set_search("");

    // The menu opens the table's tab and the console with its SELECT.
    let mut app = AppModel::new(Settings::default(), profiles.clone());
    let node = built.nodes[&orders].clone();
    let entries = app.menu(&node, true);
    assert!(entries.iter().any(|e| e.label == "Open Structure"));
    app.menu_action(Some(&node), &MenuAction::NewConsoleWithSelect, "", "");
    assert!(
        matches!(&app.workspace.selected().unwrap().spec, TabSpec::Console { text, .. } if text == "SELECT * FROM \"orders\";")
    );
    app.activate(&node);
    assert!(
        matches!(&app.workspace.selected().unwrap().spec, TabSpec::Table { table, structure: false, .. } if table.name == "orders")
    );
    assert_eq!(
        app.menu_action(Some(&node), &MenuAction::CopyName("orders".into()), "", ""),
        vec![Effect::Copy("orders".into())]
    );

    // Refresh drops the cache and loads again if open; disconnecting forgets the connection and collapses it.
    assert!(explorer.refresh(&orders, &profiles).is_some());
    explorer.forget("s");
    let (_, close) = explorer.take_pending();
    assert_eq!(close, [connection_key("s")]);
    assert!(!explorer.is_open(&orders));
    registry.disconnect("s");
    assert_eq!(registry.status("s"), Status::Disconnected);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_failed_connection_shows_its_error_under_the_node() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = ConnectionConfig::new("p", "nowhere", DatabaseType::POSTGRESQL);
    config.host = "127.0.0.1".into();
    config.port = Some(1);
    config.database = "x".into();
    let profiles = vec![config.clone()];
    let registry = Registry::new(
        tokio::runtime::Handle::current(),
        TableGripPaths::new(dir.path()),
        Arc::new(|_| Secrets::default()),
    );
    let mut explorer = Explorer::default();
    let job = explorer.opened(&connection_key("p"), &profiles).unwrap();
    let built = explorer.build(&profiles);
    assert_eq!(
        built.nodes[&built.items[0].children[0].key].kind,
        NodeKind::Loading
    );
    run(&registry, &mut explorer, &profiles, job).await;
    let built = explorer.build(&profiles);
    let row = &built.nodes[&built.items[0].children[0].key];
    assert!(
        matches!(&row.kind, NodeKind::Failure { message } if !message.is_empty()),
        "{row:?}"
    );
    assert!(matches!(registry.status("p"), Status::Failed(_)));
}

#[test]
fn favourites_then_groups_by_name_then_the_rest() {
    let mk = |id: &str, group: &str, fav: bool| ConnectionConfig {
        group: group.into(),
        favorite: fav,
        ..ConnectionConfig::new(id, id, DatabaseType::SQLITE)
    };
    let profiles = vec![
        mk("z", "", false),
        mk("w", "work", false),
        mk("f", "work", true),
        mk("h", "Home", false),
    ];
    let e = Explorer::default();
    let built = e.build(&profiles);
    assert_eq!(
        labels(&built.items, &built, &profiles),
        ["Favorites", "Home", "work", "z"]
    );
    assert_eq!(labels(&built.items[0].children, &built, &profiles), ["f"]);
}
