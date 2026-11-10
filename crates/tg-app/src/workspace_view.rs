//! The workspace island (`workspace/WorkspaceView.kt`): the pill tabs along the top and the selected tab's view
//! under them, or a hint of what to open when there are none.

use gpui::{AnyElement, Context, IntoElement, ParentElement, Styled, Window, div};
use tg_app_logic::workspace::TabSpec;
use tg_ui::{TextColor, ThemeExt, WorkspaceTab, divider, island, text, workspace_tabs};
use tg_ui_logic::keymap::Platform;

use crate::shell::Shell;

pub fn workspace(shell: &mut Shell, window: &mut Window, cx: &mut Context<Shell>) -> AnyElement {
    let t = cx.theme().clone();
    let tabs = &shell.model.workspace.tabs;
    if tabs.is_empty() {
        let platform = Platform::current();
        let shortcut = |id: &str| {
            shell
                .keymap
                .binding(id)
                .map(|s| s.label(platform))
                .unwrap_or_default()
        };
        let hint = |label: &str, id: &str| {
            div()
                .flex()
                .gap(t.dp(12.0))
                .child(
                    div()
                        .w(t.dp(160.0))
                        .child(text(label.to_owned()).color(TextColor::Info)),
                )
                .child(text(shortcut(id)).color(TextColor::Info))
        };
        return island()
            .size_full()
            .child(
                div()
                    .size_full()
                    .flex()
                    .flex_col()
                    .items_center()
                    .justify_center()
                    .gap(t.dp(6.0))
                    .child(hint("New console", "NewConsole"))
                    .child(hint("Toggle sidebar", "ToggleSidebar"))
                    .child(hint("Keyboard shortcuts", "ShowShortcuts"))
                    .child(div().h(t.dp(8.0)))
                    .child(
                        text("Double-click a table in the explorer to open it.")
                            .color(TextColor::Info),
                    ),
            )
            .into_any_element();
    }
    let pills: Vec<WorkspaceTab> = tabs
        .iter()
        .map(|tab| {
            let pill = WorkspaceTab::new(tab.title.clone());
            if matches!(tab.spec, TabSpec::Console { .. }) {
                pill.console()
            } else {
                pill
            }
        })
        .collect();
    let keys: Vec<u64> = tabs.iter().map(|t| t.key).collect();
    let selected = shell.model.workspace.selected_index().unwrap_or(0);
    let selected_key = keys.get(selected).copied();
    let entity = cx.entity();
    let (e_select, e_close, e_move) = (entity.clone(), entity.clone(), entity.clone());
    let (k_select, k_close) = (keys.clone(), keys);
    let strip = workspace_tabs("workspace.tabs", pills, selected)
        .on_select(move |i, _, cx| {
            if let Some(key) = k_select.get(i).copied() {
                e_select.update(cx, |s, cx| {
                    s.model.select_tab(key);
                    cx.notify();
                });
            }
        })
        .on_close(move |i, _, cx| {
            if let Some(key) = k_close.get(i).copied() {
                e_close.update(cx, |s, cx| {
                    s.model.close_tab(key);
                    cx.notify();
                });
            }
        })
        .on_move(move |from, to, _, cx| {
            e_move.update(cx, |s, cx| {
                s.model.workspace.move_tab(from, to);
                cx.notify();
            })
        });
    let content = selected_key.and_then(|key| shell.view_for(key, window, cx));
    island()
        .size_full()
        .child(
            div()
                .size_full()
                .flex()
                .flex_col()
                .child(div().flex_none().child(strip))
                .child(divider())
                .child(div().flex_1().min_h_0().children(content)),
        )
        .into_any_element()
}
