//! Twin of `IconButtonPressedSpecimen.kt`: an icon button pressed.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, icon_button, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "icon-button-pressed",
        width: 56.0,
        height: 48.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 56.0, 48.0)
        .child(
            island()
                .w(t.dp(56.0))
                .h(t.dp(48.0))
                .child(at(cx, 16.0, 12.0).child(icon_button("icon-button.pressed", "Actions.Execute").look(Look::PRESSED)))
        )
        .into_any_element()
}
