//! The explorer panel (`explorer/ExplorerPanel.kt`): its header, the search field, the tree with an icon, label,
//! detail and status dot per row, the context menus, and the empty state that offers a first connection.

use gpui::{
    AnyElement, Bounds, Context, IntoElement, MouseButton, MouseDownEvent, ParentElement,
    SharedString, Styled, Window, div, prelude::*,
};
use tg_app_logic::Effect;
use tg_app_logic::explorer::{NodeKind, loading_label};
use tg_ui::{
    TextColor, ThemeExt, button, icon, icon_button, island, menu_item, popup_menu, status_dot,
    text, tree,
};
use tg_ui_logic::TreeEvent;

use crate::shell::{Shell, status_color};

pub fn explorer(shell: &mut Shell, _window: &mut Window, cx: &mut Context<Shell>) -> AnyElement {
    let t = cx.theme().clone();
    let entity = cx.entity();
    let (e_add, e_space) = (entity.clone(), entity.clone());
    let header = div()
        .flex()
        .items_center()
        .h(t.dp(36.0))
        .pl(t.dp(12.0))
        .pr(t.dp(6.0))
        .child(div().flex_1().child(text("Connections").bold()))
        .child(
            icon_button("explorer.add", "General.Add")
                .size(26.0)
                .icon_size(16.0)
                .on_click(move |window, cx| {
                    let position = window.mouse_position();
                    e_add.update(cx, |s, cx| s.open_menu(None, position, cx))
                }),
        );
    let search = div()
        .px(t.dp(8.0))
        .pb(t.dp(6.0))
        .child(shell.search.clone());

    let body: AnyElement = if shell.model.connections.profiles.is_empty() {
        empty_state(shell, cx)
    } else {
        let (open, close) = shell.model.explorer.take_pending();
        let close_all = std::mem::take(&mut shell.dialogs.collapse_all);
        let cache = shell.tree_cache();
        let (items, built) = (cache.items.clone(), cache.built.clone());
        let mut close: Vec<SharedString> = close.into_iter().map(SharedString::from).collect();
        if close_all {
            close.extend(items.iter().map(|i| i.key.clone()));
            close.extend(built.nodes.keys().map(|k| SharedString::from(k.clone())));
        }
        let profiles = std::rc::Rc::new(shell.model.connections.profiles.clone());
        let registry = shell.registry.clone();
        let (label_nodes, menu_nodes, event_nodes) = (built.clone(), built.clone(), built.clone());
        let (e_menu, e_event) = (entity.clone(), entity.clone());
        tree("explorer.tree", items)
            .leaf_inset()
            .open_close(open.into_iter().map(SharedString::from).collect(), close)
            .label(move |key, cx| {
                let t = cx.theme().clone();
                let Some(node) = label_nodes.nodes.get(key.as_ref()) else {
                    return text(key.clone()).into_any_element();
                };
                let mut row = div().flex().items_center().gap(t.dp(6.0)).min_w_0();
                if !matches!(node.kind, NodeKind::Loading) {
                    row = row.child(div().flex_none().child(icon(node.icon()).size(16.0)));
                }
                let label = match &node.kind {
                    NodeKind::Loading => {
                        let parent = key
                            .rsplit_once(tg_app_logic::explorer::SEP)
                            .map(|(p, _)| p)
                            .unwrap_or("");
                        let paradigm = tg_app_logic::explorer::connection_of(parent)
                            .and_then(|id| profiles.iter().find(|c| c.id == id))
                            .map(|c| c.db_type.paradigm);
                        let parent_kind = label_nodes.nodes.get(parent).map(|n| n.kind.clone());
                        text(loading_label(
                            parent_kind.as_ref().unwrap_or(&NodeKind::Loading),
                            paradigm,
                        ))
                        .color(TextColor::Info)
                    }
                    NodeKind::Failure { .. } => text(node.label(&profiles)).color(TextColor::Error),
                    NodeKind::Group { .. } | NodeKind::Folder { .. } => {
                        text(node.label(&profiles)).color(TextColor::Info)
                    }
                    _ => text(node.label(&profiles)),
                };
                row = row.child(label);
                if let NodeKind::Connection { id } = &node.kind {
                    let status = registry.status(id);
                    if status != tg_app_logic::Status::Disconnected {
                        row = row.child(status_dot(cx, status_color(&status)));
                    }
                    if let Some(c) = profiles.iter().find(|c| &c.id == id) {
                        row = row.child(text(c.db_type.display_name).color(TextColor::Info));
                    }
                }
                if let Some(detail) = node.detail() {
                    row = row.child(text(detail).color(TextColor::Info));
                }
                row.into_any_element()
            })
            .on_secondary(move |key, position, _, cx| {
                let node = menu_nodes.nodes.get(key.as_ref()).cloned();
                e_menu.update(cx, |s, cx| s.open_menu(node, position, cx));
            })
            .on_event(move |event, window, cx| {
                e_event.update(cx, |s, cx| {
                    let profiles = s.model.connections.profiles.clone();
                    let effects: Vec<Effect> = match event {
                        TreeEvent::Opened(key) => s
                            .model
                            .explorer
                            .opened(&key, &profiles)
                            .map(Effect::Load)
                            .into_iter()
                            .collect(),
                        TreeEvent::Closed(key) => {
                            s.model.explorer.closed(&key);
                            Vec::new()
                        }
                        TreeEvent::Activated(key) => {
                            match event_nodes.nodes.get(key.as_ref()).cloned() {
                                Some(node) if matches!(node.kind, NodeKind::Failure { .. }) => {
                                    // A failure row retries its parent.
                                    let parent = key
                                        .rsplit_once(tg_app_logic::explorer::SEP)
                                        .map(|(p, _)| p.to_owned());
                                    parent
                                        .and_then(|p| s.model.explorer.refresh(&p, &profiles))
                                        .map(Effect::Load)
                                        .into_iter()
                                        .collect()
                                }
                                Some(node) => s.model.activate(&node),
                                None => Vec::new(),
                            }
                        }
                    };
                    let _ = window;
                    s.run(effects, cx);
                })
            })
            .into_any_element()
    };

    island()
        .size_full()
        .child(
            div()
                .id("explorer")
                .size_full()
                .flex()
                .flex_col()
                .on_mouse_down(MouseButton::Right, move |e: &MouseDownEvent, _, cx| {
                    let position = e.position;
                    e_space.update(cx, |s, cx| s.open_menu(None, position, cx));
                })
                .child(header)
                .child(search)
                .child(div().flex_1().min_h_0().child(body)),
        )
        .into_any_element()
}

/// No connections yet: offer a new one, or importing from another tool.
fn empty_state(_shell: &mut Shell, cx: &mut Context<Shell>) -> AnyElement {
    let t = cx.theme().clone();
    let (e_new, e_import) = (cx.entity(), cx.entity());
    div()
        .size_full()
        .flex()
        .flex_col()
        .items_center()
        .justify_center()
        .gap(t.dp(10.0))
        .p(t.dp(16.0))
        .child(text("No connections yet").color(TextColor::Info))
        .child(
            button("explorer.empty.new", "New Connection…")
                .on_click(move |window, cx| e_new.update(cx, |s, cx| s.new_connection(window, cx))),
        )
        .child(
            button("explorer.empty.import", "Import from Other Tools…")
                .outlined()
                .on_click(move |window, cx| {
                    e_import.update(cx, |s, cx| s.import_connections(window, cx))
                }),
        )
        .into_any_element()
}

/// The open context menu, at the pointer.
pub fn context_menu(shell: &mut Shell, cx: &mut Context<Shell>) -> Option<AnyElement> {
    let menu = shell.menu.as_ref()?;
    let (e_choose, e_dismiss) = (cx.entity(), cx.entity());
    let items = menu
        .entries
        .iter()
        .map(|e| menu_item(e.label.clone()))
        .collect();
    Some(
        popup_menu(format!("explorer.menu.{}", menu.id), items)
            .below(Bounds {
                origin: menu.position,
                size: gpui::size(gpui::px(0.0), gpui::px(0.0)),
            })
            .on_choose(move |i, window, cx| {
                e_choose.update(cx, |s, cx| s.choose_menu(i, window, cx))
            })
            .on_dismiss(move |_, cx| {
                e_dismiss.update(cx, |s, cx| {
                    s.menu = None;
                    cx.notify();
                })
            })
            .into_any_element(),
    )
}
