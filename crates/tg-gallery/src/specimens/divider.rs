//! Twin of `DividerSpecimen.kt`: a full-width rule, a full-height one and the 18dp toolbar separator.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{ThemeExt, divider, island, vertical_divider};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "divider",
        width: 240.0,
        height: 96.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 240.0, 96.0)
        .child(
            island()
                .w(t.dp(240.0))
                .h(t.dp(96.0))
                .child(at(cx, 0.0, 24.0).child(divider().w(t.dp(240.0))))
                .child(at(cx, 120.0, 25.0).child(vertical_divider().h(t.dp(71.0))))
                .child(at(cx, 180.0, 51.0).child(vertical_divider().h(t.dp(18.0)))),
        )
        .into_any_element()
}
