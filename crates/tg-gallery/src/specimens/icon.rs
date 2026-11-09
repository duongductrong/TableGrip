//! Twin of `IconSpecimen.kt`: monochrome and coloured IntelliJ icons, TableGrip's agent icon, and two icons tinted in
//! the info colour.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{ThemeExt, icon, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "icon",
        width: 240.0,
        height: 56.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let info = t.theme.ui.text_info;
    let mut panel = island().w(t.dp(240.0)).h(t.dp(56.0));
    for (i, key) in [
        "General.Add",
        "General.Warning",
        "General.Error",
        "Nodes.DataTables",
        "Actions.Refresh",
        "TableGrip.Agent",
    ]
    .into_iter()
    .enumerate()
    {
        panel = panel.child(at(cx, 16.0 + 28.0 * i as f32, 20.0).child(icon(key)));
    }
    canvas(cx, 240.0, 56.0)
        .child(
            panel
                .child(at(cx, 184.0, 22.0).child(icon("General.ChevronRight").size(12.0).tint(info)))
                .child(at(cx, 204.0, 21.0).child(icon("Actions.Search").size(14.0).tint(info))),
        )
        .into_any_element()
}
