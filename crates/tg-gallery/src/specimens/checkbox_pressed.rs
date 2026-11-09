//! Twin of `CheckboxPressedSpecimen.kt`: a checkbox row pressed.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, checkbox, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "checkbox-pressed",
        width: 144.0,
        height: 44.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 144.0, 44.0)
        .child(
            island()
                .w(t.dp(144.0))
                .h(t.dp(44.0))
                .child(at(cx, 16.0, 12.0).child(checkbox("checkbox.pressed", "Read-only").look(Look::PRESSED)))
        )
        .into_any_element()
}
