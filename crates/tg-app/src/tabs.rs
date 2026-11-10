//! How a tab kind gets its view. The shell asks [`TabViews::build`] for the view of a tab the first time it shows,
//! keeps it while the tab is open, and drops it when the tab closes. Later sessions register their kind in `main`:
//!
//! ```text
//! TabViews::register(cx, "table", |tab, shell, window, cx| cx.new(|cx| TableView::new(tab, shell, window, cx)).into());
//! ```
//!
//! A view reads and updates its tab's [`TabSpec`] through the shell (`shell.model.workspace.tab_mut(key)`), so the
//! session saves what is on screen. Until a kind is registered its tabs show a placeholder naming what they will be.

use std::collections::HashMap;
use std::rc::Rc;

use gpui::{
    AnyView, App, AppContext, Context, Global, IntoElement, ParentElement, Render, Styled,
    WeakEntity, Window, div,
};
use tg_app_logic::workspace::{Tab, TabSpec};
use tg_ui::{TextColor, ThemeExt, icon, text};

use crate::shell::Shell;

pub type TabFactory = Rc<dyn Fn(&Tab, WeakEntity<Shell>, &mut Window, &mut App) -> AnyView>;

#[derive(Default)]
pub struct TabViews {
    factories: HashMap<&'static str, TabFactory>,
}

impl Global for TabViews {}

impl TabViews {
    /// Draws tabs of `kind` (`console`, `table`, `keys`, `collection`) with `factory`.
    #[allow(dead_code, reason = "S7 and S8 register the first views")]
    pub fn register(
        cx: &mut App,
        kind: &'static str,
        factory: impl Fn(&Tab, WeakEntity<Shell>, &mut Window, &mut App) -> AnyView + 'static,
    ) {
        cx.default_global::<TabViews>()
            .factories
            .insert(kind, Rc::new(factory));
    }

    pub fn build(
        tab: &Tab,
        shell: WeakEntity<Shell>,
        window: &mut Window,
        cx: &mut App,
    ) -> AnyView {
        let factory = cx
            .try_global::<TabViews>()
            .and_then(|v| v.factories.get(tab.spec.kind()).cloned());
        match factory {
            Some(f) => f(tab, shell, window, cx),
            None => cx
                .new(|_| Placeholder {
                    spec: tab.spec.clone(),
                })
                .into(),
        }
    }
}

/// What a tab will show once its screen is ported.
struct Placeholder {
    spec: TabSpec,
}

impl Render for Placeholder {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        let (icon_key, title, detail, by) = match &self.spec {
            TabSpec::Console { text, .. } => (
                "Nodes.Console",
                "SQL console".to_owned(),
                if text.trim().is_empty() {
                    "Empty console".to_owned()
                } else {
                    text.clone()
                },
                "The SQL editor and console are ported in S8.",
            ),
            TabSpec::Table {
                table,
                structure,
                filter,
                ..
            } => (
                "Nodes.DataTables",
                format!(
                    "{} — {}",
                    table.name,
                    if *structure { "structure" } else { "data" }
                ),
                filter
                    .as_ref()
                    .map_or_else(String::new, |f| format!("WHERE {f}")),
                "The data grid and table view are ported in S7 and S9.",
            ),
            TabSpec::Keys { keyspace, .. } => (
                "Nodes.DataSchema",
                format!("Keys of db{keyspace}"),
                String::new(),
                "The key browser is ported in S9.",
            ),
            TabSpec::Collection {
                database,
                collection,
                ..
            } => (
                "Nodes.DataTables",
                format!("{database}.{collection}"),
                String::new(),
                "The document browser is ported in S9.",
            ),
        };
        div()
            .size_full()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(t.dp(8.0))
            .p(t.dp(24.0))
            .child(icon(icon_key).size(16.0))
            .child(text(title).bold())
            .child(text(detail).editor())
            .child(text(by).color(TextColor::Info))
    }
}
