//! Twin of `ButtonPressedOutlinedSpecimen.kt`: outlined button pressed, default at rest.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, button, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "button-pressed-outlined",
        width: 184.0,
        height: 92.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 184.0, 92.0)
        .child(
            island()
                .w(t.dp(184.0))
                .h(t.dp(92.0))
                .child(at(cx, 16.0, 12.0).child(button("button.default.rest", "Save")))
                .child(at(cx, 16.0, 52.0).child(button("button.outlined.pressed", "Cancel").outlined().look(Look::PRESSED)))
        )
        .into_any_element()
}
