//! Twin of `PopupListSpecimen.kt`: four rows, the first the keyboard highlight and the third hovered.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{ThemeExt, island, popup_list};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "popup-list",
        width: 176.0,
        height: 144.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let rows = (0..4).map(|i| format!("Row {i}").into()).collect();
    canvas(cx, 176.0, 144.0)
        .child(
            island()
                .w(t.dp(176.0))
                .h(t.dp(144.0))
                .child(at(cx, 16.0, 12.0).w(t.dp(144.0)).child(popup_list("list", rows).hovered(2))),
        )
        .into_any_element()
}
