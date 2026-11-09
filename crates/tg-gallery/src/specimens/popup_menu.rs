//! Twin of `PopupMenuSpecimen.kt`: an open menu at (16, 8) with the first item selected, the third hovered and the
//! last disabled, a separator after the second.

use gpui::{App, IntoElement, ParentElement, Window};
use tg_ui::{menu_item, popup_menu};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "popup-menu",
        width: 240.0,
        height: 160.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let items = vec![
        menu_item("Auto-commit"),
        menu_item("Manual commit").separator_after(),
        menu_item("Commit"),
        menu_item("Roll Back").disabled(),
    ];
    canvas(cx, 240.0, 160.0)
        .child(at(cx, 16.0, 8.0).child(popup_menu("menu", items).selected(Some(0)).hovered(2)))
        .into_any_element()
}
