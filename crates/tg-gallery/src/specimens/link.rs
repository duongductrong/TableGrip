//! Twin of `LinkSpecimen.kt`: links at rest, disabled, hovered and focused (the last two drawn in that look).

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{LinkLook, ThemeExt, island, link};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "link",
        width: 320.0,
        height: 72.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 320.0, 72.0)
        .child(
            island()
                .w(t.dp(320.0))
                .h(t.dp(72.0))
                .child(at(cx, 16.0, 12.0).child(link("link", "Edit as SQL")))
                .child(at(cx, 168.0, 12.0).child(link("disabled", "Edit as SQL").enabled(false)))
                .child(at(cx, 16.0, 40.0).child(link("hovered", "Edit as SQL").look(LinkLook::Hovered)))
                .child(at(cx, 168.0, 40.0).child(link("focused", "Edit as SQL").look(LinkLook::Focused))),
        )
        .into_any_element()
}
