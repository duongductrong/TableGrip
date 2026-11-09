//! Twin of `IconButtonSpecimen.kt`: icon buttons at rest, hovered, focused and disabled.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, icon_button, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "icon-button",
        width: 160.0,
        height: 48.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 160.0, 48.0)
        .child(
            island()
                .w(t.dp(160.0))
                .h(t.dp(48.0))
                .child(at(cx, 16.0, 12.0).child(icon_button("icon-button.rest", "Actions.Execute")))
                .child(at(cx, 52.0, 12.0).child(icon_button("icon-button.hovered", "Actions.Execute").look(Look::HOVERED)))
                .child(at(cx, 88.0, 12.0).child(icon_button("icon-button.focused", "Actions.Execute").look(Look::FOCUSED)))
                .child(at(cx, 124.0, 12.0).child(icon_button("icon-button.disabled", "Actions.Execute").enabled(false)))
        )
        .into_any_element()
}
