//! Twin of `WorkspaceTabsSpecimen.kt`: the selected tab, a console with unsaved work, a hovered tab.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{ThemeExt, WorkspaceTab, island, workspace_tabs};

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "workspace-tabs",
        width: 360.0,
        height: 42.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let tabs = vec![
        WorkspaceTab::new("users"),
        WorkspaceTab::new("Console 1").console().dirty(),
        WorkspaceTab::new("orders"),
    ];
    canvas(cx, 360.0, 42.0)
        .child(
            island()
                .w(t.dp(360.0))
                .h(t.dp(42.0))
                .child(workspace_tabs("tabs", tabs, 0).hovered(2)),
        )
        .into_any_element()
}
