//! Twin of `RadioSpecimen.kt`: radio rows at rest, selected, hovered, focused, disabled and in error.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, radio_button, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "radio",
        width: 464.0,
        height: 76.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 464.0, 76.0)
        .child(
            island()
                .w(t.dp(464.0))
                .h(t.dp(76.0))
                .child(at(cx, 16.0, 12.0).child(radio_button("radio.rest", "Read-only", false)))
                .child(at(cx, 128.0, 12.0).child(radio_button("radio.selected", "Read-only", true)))
                .child(at(cx, 240.0, 12.0).child(radio_button("radio.hovered", "Read-only", false).look(Look::HOVERED)))
                .child(at(cx, 352.0, 12.0).child(radio_button("radio.focused", "Read-only", true).look(Look::FOCUSED)))
                .child(at(cx, 16.0, 44.0).child(radio_button("radio.disabled", "Read-only", false).enabled(false)))
                .child(at(cx, 128.0, 44.0).child(radio_button("radio.disabled.selected", "Read-only", true).enabled(false)))
                .child(at(cx, 240.0, 44.0).child(radio_button("radio.error", "Read-only", false).error(true)))
                .child(at(cx, 352.0, 44.0).child(radio_button("radio.error.selected", "Read-only", true).error(true)))
        )
        .into_any_element()
}
