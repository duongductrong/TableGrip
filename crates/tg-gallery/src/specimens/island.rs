//! Twin of `IslandSpecimen.kt`: the default island, the 6dp gap between islands, and a dialog's 4dp shape.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "island",
        width: 280.0,
        height: 120.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 280.0, 120.0)
        .child(at(cx, 12.0, 12.0).child(island().w(t.dp(120.0)).h(t.dp(96.0))))
        .child(at(cx, 138.0, 12.0).child(island().w(t.dp(60.0)).h(t.dp(96.0))))
        .child(at(cx, 212.0, 12.0).child(island().radius(4.0).w(t.dp(56.0)).h(t.dp(96.0))))
        .into_any_element()
}
