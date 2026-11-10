//! The main window (`ui/MainScreen.kt`, `ui/TitleBarView.kt`, `ui/StatusBar.kt`, the window part of `Main.kt`):
//! the title bar, the tool stripe, the left tool window and the workspace split, the status bar, and the dialogs in
//! front. The state is [`AppModel`]; this file draws it and runs the [`Effect`]s it asks for on tokio.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use gpui::{
    AnyView, App, AppContext, ClipboardItem, Context, Entity, FocusHandle, IntoElement,
    KeyDownEvent, ParentElement, Pixels, Point, Render, SharedString, Styled, Subscription, Window,
    div, prelude::*,
};
use tg_app_logic::app::{MenuAction, MenuEntry};
use tg_app_logic::explorer::{Built, LoadJob, Node, load};
use tg_app_logic::{AppModel, Dialog, Effect, LeftTool, Registry, Status, appearance, session};
use tg_core::TableGripPaths;
use tg_core::connection::ConnectionStore;
use tg_core::session::{Session, SessionStore, WindowBounds};
use tg_core::settings::SettingsStore;
use tg_ui::window_chrome::{Os, SharedRegions, bar_title, title_bar};
use tg_ui::{
    TextColor, TextInput, ThemeExt, hsla, icon_button, island, split_layout, status_bar,
    status_divider, status_dot, status_widget, stripe_button, text, tool_stripe,
};
use tg_ui_logic::keymap::Keymap;

use crate::io;
use crate::tabs::TabViews;

pub const TITLE_BAR_HEIGHT: f32 = 40.0;
const CONNECTED: tg_theme::Rgba = tg_theme::Rgba::rgb(0x5FB865);
const CONNECTING: tg_theme::Rgba = tg_theme::Rgba::rgb(0xD9822B);
const FAILED: tg_theme::Rgba = tg_theme::Rgba::rgb(0xE5534B);

/// A context menu open at `position`, for a node or (None) the explorer itself.
pub struct ContextMenu {
    pub id: u64,
    pub node: Option<Node>,
    pub entries: Vec<MenuEntry>,
    pub position: Point<Pixels>,
}

/// What the explorer tree draws from, rebuilt when the explorer or the profiles change.
pub struct TreeCache {
    pub key: (u64, u64),
    pub items: tg_ui::tree::Items,
    pub built: Rc<Built>,
}

pub struct Shell {
    pub model: AppModel,
    pub registry: Registry,
    pub store: Arc<Mutex<ConnectionStore>>,
    pub paths: TableGripPaths,
    pub settings_store: Arc<SettingsStore>,
    session_store: SessionStore,
    pub keymap: Keymap,
    pub regions: SharedRegions,
    pub focus: FocusHandle,
    /// Bumped whenever the profile list changes, so the tree rebuilds.
    pub profiles_rev: u64,
    /// Saves started, so an older save's answer doesn't replace a newer edit.
    edits: u64,
    pub tree: Option<TreeCache>,
    pub menu: Option<ContextMenu>,
    menu_seq: u64,
    pub search: Entity<TextInput>,
    pub dialogs: crate::dialogs::DialogViews,
    views: HashMap<u64, AnyView>,
    /// The window's bounds the last time they changed, in dp, for the session.
    bounds: Option<WindowBounds>,
    last_saved: Option<Session>,
    _subscriptions: Vec<Subscription>,
}

/// The OS user name, the SSH user a new tunnel suggests.
pub fn os_user() -> String {
    std::env::var("USER")
        .or_else(|_| std::env::var("USERNAME"))
        .unwrap_or_default()
}

/// A fresh profile id, as Kotlin's `UUID.randomUUID()` names them.
pub fn new_id() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut x = nanos ^ (std::process::id() as u128) << 64;
    let mut hex = String::new();
    for _ in 0..32 {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        hex.push(char::from_digit((x & 0xF) as u32, 16).unwrap());
    }
    format!(
        "{}-{}-4{}-a{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[13..16],
        &hex[17..20],
        &hex[20..32]
    )
}

impl Shell {
    #[allow(
        clippy::too_many_arguments,
        reason = "the services main builds, handed over once"
    )]
    pub fn new(
        model: AppModel,
        registry: Registry,
        store: ConnectionStore,
        paths: TableGripPaths,
        settings_store: Arc<SettingsStore>,
        keymap: Keymap,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let search =
            cx.new(|cx| TextInput::field("", cx).placeholder("Search connections and tables"));
        let mut subscriptions = vec![
            cx.observe(&search, |this: &mut Shell, search, cx| {
                let text = search.read(cx).text().to_owned();
                if this.model.explorer.search != text {
                    this.model.explorer.set_search(&text);
                    let profiles = this.model.connections.profiles.clone();
                    let open = this.model.explorer.search_match_ancestors(&profiles);
                    this.model.explorer.reveal_keys(open);
                    cx.notify();
                }
            }),
            cx.observe_window_appearance(window, |this: &mut Shell, window, cx| {
                this.apply_theme(window, cx)
            }),
            cx.observe_window_bounds(window, |this: &mut Shell, window, _| {
                this.bounds = Some(bounds_of(window))
            }),
            cx.on_app_quit(|this: &mut Shell, _| {
                this.save_session_now();
                this.registry.close_all();
                async {}
            }),
        ];
        // Redraw whenever a connection's status changes.
        let mut revisions = registry.revisions();
        subscriptions.push(cx.observe_release(&search, |_, _, _| {}));
        cx.spawn(async move |this, cx| {
            while revisions.changed().await.is_ok() {
                if this.update(cx, |_, cx| cx.notify()).is_err() {
                    break;
                }
            }
        })
        .detach();
        // Autosave every few seconds while something changed, so a crash loses little.
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(5)).await;
                if this.update(cx, |s, _| s.save_session_if_changed()).is_err() {
                    break;
                }
            }
        })
        .detach();
        let session_store = SessionStore::new(paths.session());
        Self {
            model,
            registry,
            store: Arc::new(Mutex::new(store)),
            paths,
            settings_store,
            session_store,
            keymap,
            regions: Rc::new(RefCell::new(Default::default())),
            focus: cx.focus_handle(),
            profiles_rev: 0,
            edits: 0,
            tree: None,
            menu: None,
            menu_seq: 0,
            search,
            dialogs: Default::default(),
            views: HashMap::new(),
            bounds: Some(bounds_of(window)),
            last_saved: None,
            _subscriptions: subscriptions,
        }
    }

    // The session.

    /// The session as restored counts as saved, so autosave writes only once something changes.
    pub fn mark_saved(&mut self, window: Option<WindowBounds>) {
        self.last_saved = Some(session::snapshot(&self.model, window));
    }

    fn snapshot(&self) -> Session {
        session::snapshot(&self.model, self.bounds.clone())
    }

    pub fn save_session_now(&mut self) {
        let snapshot = self.snapshot();
        if let Err(e) = self.session_store.save(&snapshot) {
            eprintln!("Failed to save the session: {e}");
        }
        self.last_saved = Some(snapshot);
    }

    fn save_session_if_changed(&mut self) {
        if self.last_saved.as_ref() != Some(&self.snapshot()) {
            self.save_session_now();
        }
    }

    // Theme and zoom.

    /// Applies the appearance settings (theme for the window's light or dark, accent, font sizes, zoom).
    pub fn apply_theme(&mut self, window: &mut Window, cx: &mut App) {
        let settings = appearance::appearance(&self.settings_store);
        let mut registry = tg_theme::Registry::new();
        registry.reload_user_themes(&self.paths.themes());
        let dark = matches!(
            window.appearance(),
            gpui::WindowAppearance::Dark | gpui::WindowAppearance::VibrantDark
        );
        tg_ui::set_theme(cx, appearance::theme(&settings, &registry, dark));
    }

    fn zoom(&mut self, percent: i32, cx: &mut Context<Self>) {
        let store = self.settings_store.clone();
        let handle = io::handle(cx);
        cx.spawn(async move |this, cx| {
            let saved = io::blocking(&handle, move || appearance::save_zoom(&store, percent)).await;
            this.update(cx, |s, cx| {
                match saved {
                    Ok(Some(settings)) => {
                        s.model.status = format!("Zoom {}%", settings.zoom);
                        s.model.settings = settings;
                    }
                    Ok(None) => {
                        s.model.status =
                            "Not saving settings: settings.json has an error to fix first".into()
                    }
                    Err(e) => s.model.status = format!("Failed to save settings: {e}"),
                }
                cx.notify();
            })
            .ok();
            cx.update(|cx| {
                if let Some(window) = cx.active_window() {
                    window
                        .update(cx, |_, window, cx| {
                            if let Some(shell) = this.upgrade() {
                                shell.update(cx, |s, cx| s.apply_theme(window, cx));
                            }
                        })
                        .ok();
                }
            })
            .ok();
        })
        .detach();
    }

    // Effects.

    pub fn run(&mut self, effects: Vec<Effect>, cx: &mut Context<Self>) {
        for effect in effects {
            match effect {
                Effect::Load(job) => self.load(job, cx),
                Effect::SaveProfiles(configs) => {
                    self.profiles_rev += 1;
                    self.save_profiles(configs, cx);
                }
                Effect::DeleteProfile(id) => {
                    self.profiles_rev += 1;
                    self.delete_profile(id, cx);
                }
                Effect::Disconnect(id) => self.registry.disconnect(&id),
                Effect::Zoom(percent) => self.zoom(percent, cx),
                Effect::Copy(text) => cx.write_to_clipboard(ClipboardItem::new_string(text)),
                Effect::CopyDdl {
                    connection_id,
                    table,
                } => self.copy_ddl(&connection_id, table, cx),
            }
        }
        cx.notify();
    }

    fn load(&mut self, job: LoadJob, cx: &mut Context<Self>) {
        let Some(config) = self.model.connections.by_id(&job.connection_id).cloned() else {
            return;
        };
        let attempt = self.registry.connection(&config);
        let handle = io::handle(cx);
        cx.spawn(async move |this, cx| {
            let result = match attempt.await {
                Ok(live) => {
                    let job = job.clone();
                    io::spawn(&handle, async move { load(&live, &job).await }).await
                }
                Err(e) => Err(e),
            };
            this.update(cx, |s, cx| {
                if let Err(e) = &result {
                    s.model.status = format!("{}: {}", config.name, tg_core::json::first_line(e));
                }
                let profiles = s.model.connections.profiles.clone();
                let next = s.model.explorer.loaded(&job, result, &profiles);
                s.run(next.into_iter().map(Effect::Load).collect(), cx);
            })
            .ok();
        })
        .detach();
    }

    fn save_profiles(
        &mut self,
        configs: Vec<tg_core::connection::ConnectionConfig>,
        cx: &mut Context<Self>,
    ) {
        self.edits += 1;
        let edit = self.edits;
        let store = self.store.clone();
        let handle = io::handle(cx);
        cx.spawn(async move |this, cx| {
            let saved = io::blocking(&handle, move || {
                let store = store.lock().unwrap();
                let mut last = store.load();
                for c in &configs {
                    last = store.save(c, None, None);
                }
                last
            })
            .await;
            this.update(cx, |s, cx| {
                match saved {
                    Ok(list) if edit == s.edits => {
                        s.model.connections.profiles = list;
                        s.profiles_rev += 1;
                    }
                    Ok(_) => {}
                    Err(e) => s.model.status = format!("Failed to save: {e}"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn delete_profile(&mut self, id: String, cx: &mut Context<Self>) {
        let store = self.store.clone();
        let handle = io::handle(cx);
        cx.spawn(async move |this, cx| {
            let result = io::blocking(&handle, move || store.lock().unwrap().delete(&id)).await;
            this.update(cx, |s, cx| {
                match result {
                    Ok(list) => {
                        s.model.connections.profiles = list;
                        s.profiles_rev += 1;
                    }
                    Err(e) => s.model.status = format!("Failed to delete: {e}"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn copy_ddl(&mut self, id: &str, table: tg_sql::model::TableRef, cx: &mut Context<Self>) {
        let Some(config) = self.model.connections.by_id(id).cloned() else {
            return;
        };
        let attempt = self.registry.connection(&config);
        let handle = io::handle(cx);
        cx.spawn(async move |this, cx| {
            let name = table.name.clone();
            let ddl = match attempt.await {
                Ok(live) => {
                    io::spawn(&handle, async move {
                        match live.relational() {
                            Some(db) => db
                                .table_structure(&table)
                                .await
                                .map(|s| s.ddl)
                                .map_err(|e| e.to_string()),
                            None => Err("This connection has no tables".to_owned()),
                        }
                    })
                    .await
                }
                Err(e) => Err(e),
            };
            this.update(cx, |s, cx| {
                match ddl {
                    Ok(ddl) => {
                        cx.write_to_clipboard(ClipboardItem::new_string(ddl));
                        s.model.status = format!("Copied DDL of {name}");
                    }
                    Err(e) => s.model.status = format!("Couldn't read DDL of {name}: {e}"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // Commands and menus.

    /// A keymap command at window level; true when the window took it.
    pub fn command(&mut self, id: &str, cx: &mut Context<Self>) -> bool {
        match self.model.window_command(id) {
            Some(effects) => {
                self.run(effects, cx);
                true
            }
            None => false,
        }
    }

    pub fn open_menu(
        &mut self,
        node: Option<Node>,
        position: Point<Pixels>,
        cx: &mut Context<Self>,
    ) {
        let entries = match &node {
            Some(n) => {
                let connected = tg_app_logic::explorer::connection_of(&n.key)
                    .is_some_and(|id| self.registry.status(id) == Status::Connected);
                self.model.menu(n, connected)
            }
            None => self.model.sidebar_menu(
                self.model.explorer.open_keys().next().is_some(),
                !self.registry.active().is_empty(),
            ),
        };
        self.menu = (!entries.is_empty()).then(|| {
            self.menu_seq += 1;
            ContextMenu {
                id: self.menu_seq,
                node,
                entries,
                position,
            }
        });
        cx.notify();
    }

    pub fn choose_menu(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(menu) = self.menu.take() else {
            return;
        };
        let Some(entry) = menu.entries.get(index) else {
            return;
        };
        if entry.action == MenuAction::CollapseAll {
            self.dialogs.collapse_all = true;
        }
        let effects =
            self.model
                .menu_action(menu.node.as_ref(), &entry.action, &new_id(), &os_user());
        self.after_dialog_change(window, cx);
        self.run(effects, cx);
    }

    /// Opens a new connection dialog (from the stripe, the title bar, the empty explorer).
    pub fn new_connection(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.model.open_new_connection("", &new_id(), &os_user());
        self.after_dialog_change(window, cx);
    }

    pub fn import_connections(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.model.dialog = Some(Dialog::Import(tg_app_logic::imports::ImportState::new()));
        self.after_dialog_change(window, cx);
    }

    /// Builds the views a newly opened dialog needs (its text fields), and starts what it loads.
    pub fn after_dialog_change(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.model.dialog.is_some() {
            self.menu = None;
        }
        crate::dialogs::prepare(self, window, cx);
        cx.notify();
    }

    pub fn pick_connection(&mut self, id: &str, cx: &mut Context<Self>) {
        let effects = self.model.pick_connection(id);
        self.run(effects, cx);
    }

    // Views of tabs.

    fn tab_view(
        &mut self,
        key: u64,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyView> {
        if let Some(v) = self.views.get(&key) {
            return Some(v.clone());
        }
        let tab = self.model.workspace.tab(key)?.clone();
        let shell = cx.entity().downgrade();
        let view = TabViews::build(&tab, shell, window, cx);
        self.views.insert(key, view.clone());
        Some(view)
    }

    fn drop_closed_views(&mut self) {
        let open: Vec<u64> = self.model.workspace.tabs.iter().map(|t| t.key).collect();
        self.views.retain(|k, _| open.contains(k));
    }

    pub fn tree_cache(&mut self) -> &TreeCache {
        let profiles = self.model.connections.profiles.clone();
        self.model.explorer.open_new_groups(&profiles);
        let key = (self.model.explorer.revision(), self.profiles_rev);
        if self.tree.as_ref().is_none_or(|t| t.key != key) {
            let built = self.model.explorer.build(&self.model.connections.profiles);
            self.tree = Some(TreeCache {
                key,
                items: Rc::new(convert(&built.items)),
                built: Rc::new(built),
            });
        }
        self.tree.as_ref().unwrap()
    }
}

fn convert(items: &[tg_ui_logic::Item<String>]) -> Vec<tg_ui_logic::Item<SharedString>> {
    items
        .iter()
        .map(|i| tg_ui_logic::Item {
            key: SharedString::from(i.key.clone()),
            node: i.node,
            children: convert(&i.children),
        })
        .collect()
}

fn bounds_of(window: &Window) -> WindowBounds {
    let (b, maximized) = match window.window_bounds() {
        gpui::WindowBounds::Windowed(b) => (b, false),
        gpui::WindowBounds::Maximized(b) | gpui::WindowBounds::Fullscreen(b) => (b, true),
    };
    WindowBounds {
        x: b.origin.x.into(),
        y: b.origin.y.into(),
        width: b.size.width.into(),
        height: b.size.height.into(),
        maximized,
    }
}

/// The colour of a connection's status dot; None draws a hollow one.
pub fn status_color(status: &Status) -> Option<tg_theme::Rgba> {
    match status {
        Status::Connected => Some(CONNECTED),
        Status::Connecting { .. } => Some(CONNECTING),
        Status::Failed(_) => Some(FAILED),
        Status::Disconnected => None,
    }
}

impl Render for Shell {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        let entity = cx.entity();
        self.drop_closed_views();

        let title = if self.model.settings_open {
            "Settings".to_owned()
        } else {
            self.model
                .workspace
                .selected()
                .map_or_else(|| "TableGrip".to_owned(), |t| t.title.clone())
        };
        let current = self.model.current_connection().cloned();
        let bar = {
            let left = match &current {
                Some(c) => div()
                    .flex()
                    .items_center()
                    .gap(t.dp(8.0))
                    .pl(t.dp(4.0))
                    .child(status_dot(cx, status_color(&self.registry.status(&c.id))))
                    .child(text(c.name.clone()).bold())
                    .child(text(c.db_type.display_name).color(TextColor::Info)),
                None => div()
                    .pl(t.dp(4.0))
                    .child(text("No connection").color(TextColor::Info)),
            };
            let (e1, e2) = (entity.clone(), entity.clone());
            let right = div()
                .flex()
                .items_center()
                .gap(t.dp(2.0))
                .pr(t.dp(6.0))
                .child(
                    icon_button("title.new-console", "Nodes.Console")
                        .size(26.0)
                        .icon_size(16.0)
                        .on_click(move |_, cx| {
                            e1.update(cx, |s, cx| {
                                if s.model.new_console() {
                                    cx.notify();
                                }
                            })
                        }),
                )
                .child(
                    icon_button("title.settings", "General.Settings")
                        .size(26.0)
                        .icon_size(16.0)
                        .on_click(move |_, cx| {
                            e2.update(cx, |s, cx| {
                                s.model.settings_open = !s.model.settings_open;
                                cx.notify();
                            })
                        }),
                );
            title_bar(Os::current(), TITLE_BAR_HEIGHT, &self.regions)
                .left(left)
                .title(bar_title(title))
                .right(right)
        };

        let stripe = {
            let tool =
                |id: &'static str, icon: &'static str, tip: &'static str, which: LeftTool| {
                    let e = entity.clone();
                    stripe_button(format!("stripe.{id}"), icon)
                        .tooltip(tip)
                        .selected(self.model.left_tool == Some(which) && !self.model.settings_open)
                        .on_click(move |_, cx| {
                            e.update(cx, |s, cx| {
                                s.model.toggle_tool(which);
                                cx.notify();
                            })
                        })
                };
            let (e_new, e_import, e_keys, e_settings) = (
                entity.clone(),
                entity.clone(),
                entity.clone(),
                entity.clone(),
            );
            tool_stripe(vec![
                tool(
                    "explorer",
                    "Nodes.DataSchema",
                    "Connections",
                    LeftTool::Explorer,
                ),
                tool("history", "Vcs.History", "Query History", LeftTool::History),
                tool(
                    "queries",
                    "Nodes.Bookmark",
                    "Saved Queries",
                    LeftTool::Queries,
                ),
            ])
            .actions(vec![
                stripe_button("stripe.new", "General.Add")
                    .tooltip("New Connection")
                    .on_click(move |window, cx| {
                        e_new.update(cx, |s, cx| s.new_connection(window, cx))
                    }),
                stripe_button("stripe.import", "ToolbarDecorator.Import")
                    .tooltip("Import Connections")
                    .on_click(move |window, cx| {
                        e_import.update(cx, |s, cx| s.import_connections(window, cx))
                    }),
            ])
            .bottom(vec![
                stripe_button("stripe.shortcuts", "General.Keyboard")
                    .tooltip("Keyboard Shortcuts")
                    .on_click(move |_, cx| {
                        e_keys.update(cx, |s, cx| {
                            s.model.dialog = Some(Dialog::Shortcuts);
                            cx.notify();
                        })
                    }),
                stripe_button("stripe.settings", "General.Settings")
                    .tooltip("Settings")
                    .selected(self.model.settings_open)
                    .on_click(move |_, cx| {
                        e_settings.update(cx, |s, cx| {
                            s.model.settings_open = !s.model.settings_open;
                            cx.notify();
                        })
                    }),
            ])
        };

        let center = self.center(window, cx);
        let center = if self.model.agent_open {
            let e = entity.clone();
            split_layout(
                "main.agent-split",
                self.model.agent_split as f64,
                center,
                placeholder_island(
                    cx,
                    "Agent",
                    "The agent panel is ported with the remaining screens (S10).",
                ),
            )
            .min_widths(480.0, 300.0)
            .on_ratio(move |r, _, cx| e.update(cx, |s, _| s.model.agent_split = r as f32))
            .into_any_element()
        } else {
            center
        };

        let status = {
            let mut bar =
                status_bar(self.model.status.clone()).breadcrumbs(self.model.breadcrumbs());
            if let Some(c) = &current {
                let id = c.id.clone();
                let e = entity.clone();
                bar = bar.widget(status_divider(cx)).widget(
                    status_widget("status.connection")
                        .clickable()
                        .child(status_dot(cx, status_color(&self.registry.status(&c.id))))
                        .child(text(c.name.clone()))
                        .on_click(move |_, cx| e.update(cx, |s, cx| s.pick_connection(&id, cx))),
                );
            }
            bar
        };

        let e_cmd = entity.clone();
        let e_key = entity.clone();
        let root = div()
            .id("main")
            .track_focus(&self.focus)
            .size_full()
            .flex()
            .flex_col()
            .bg(hsla(t.theme.colors.islands.frame))
            .font_family(t.ui_font.clone())
            .on_key_down(move |e: &KeyDownEvent, window, cx| {
                if e.keystroke.key == "escape" {
                    let used = e_key.update(cx, |s, cx| {
                        let used = s.model.escape();
                        if used {
                            s.after_dialog_change(window, cx);
                        }
                        used
                    });
                    if used {
                        cx.stop_propagation();
                    }
                }
            })
            .child(bar)
            .child(
                div()
                    .flex()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(stripe)
                    .child(div().flex_1().h_full().min_w_0().child(center))
                    .child(div().flex_none().w(t.dp(6.0))),
            )
            .child(status)
            .children(crate::explorer_view::context_menu(self, cx))
            .children(crate::dialogs::render(self, window, cx));
        tg_ui::keymap::preview_commands(root, move |id, _, cx| {
            e_cmd.update(cx, |s, cx| s.command(id, cx))
        })
    }
}

impl Shell {
    /// The left tool window and the editor tabs, or the settings screen in their place.
    fn center(&mut self, window: &mut Window, cx: &mut Context<Self>) -> gpui::AnyElement {
        if self.model.settings_open {
            return placeholder_island(
                cx,
                "Settings",
                "Settings pages are ported with the remaining screens (S10). Until then, edit settings.json: the window follows its theme and zoom.",
            );
        }
        let workspace = crate::workspace_view::workspace(self, window, cx);
        let Some(tool) = self.model.left_tool else {
            return workspace;
        };
        let panel = match tool {
            LeftTool::Explorer => crate::explorer_view::explorer(self, window, cx),
            LeftTool::History => placeholder_island(
                cx,
                "Query History",
                "Query history comes with the SQL console (S8).",
            ),
            LeftTool::Queries => placeholder_island(
                cx,
                "Saved Queries",
                "Saved queries come with the SQL console (S8).",
            ),
        };
        let e = cx.entity();
        split_layout(
            "main.sidebar-split",
            self.model.sidebar_split as f64,
            panel,
            workspace,
        )
        .min_widths(220.0, 420.0)
        .on_ratio(move |r, _, cx| e.update(cx, |s, _| s.model.sidebar_split = r as f32))
        .into_any_element()
    }

    pub fn view_for(
        &mut self,
        key: u64,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyView> {
        self.tab_view(key, window, cx)
    }
}

/// An island with a heading and a line of text, for screens later sessions port.
pub fn placeholder_island(cx: &App, title: &str, body: &str) -> gpui::AnyElement {
    let t = cx.theme();
    island()
        .size_full()
        .child(
            div()
                .size_full()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .gap(t.dp(8.0))
                .p(t.dp(24.0))
                .child(text(title.to_owned()).bold())
                .child(text(body.to_owned()).color(TextColor::Info)),
        )
        .into_any_element()
}
