//! Twin of `ButtonSpecimen.kt`: DefaultButton and OutlinedButton at rest, hovered, focused and disabled.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, button, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "button",
        width: 344.0,
        height: 92.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 344.0, 92.0)
        .child(
            island()
                .w(t.dp(344.0))
                .h(t.dp(92.0))
                .child(at(cx, 16.0, 12.0).child(button("button.default.rest", "Save")))
                .child(at(cx, 96.0, 12.0).child(button("button.default.hovered", "Save").look(Look::HOVERED)))
                .child(at(cx, 176.0, 12.0).child(button("button.default.disabled", "Save").enabled(false)))
                .child(at(cx, 16.0, 52.0).child(button("button.outlined.rest", "Cancel").outlined()))
                .child(at(cx, 96.0, 52.0).child(button("button.outlined.focused", "Cancel").outlined().look(Look::FOCUSED)))
                .child(at(cx, 176.0, 52.0).child(button("button.outlined.disabled", "Cancel").outlined().enabled(false)))
        )
        .into_any_element()
}
