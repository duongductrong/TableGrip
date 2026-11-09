//! Twin of `CheckboxSpecimen.kt`: checkbox and tri-state rows at rest, checked, indeterminate, hovered, focused, disabled and in error.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, ToggleState, checkbox, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "checkbox",
        width: 464.0,
        height: 108.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 464.0, 108.0)
        .child(
            island()
                .w(t.dp(464.0))
                .h(t.dp(108.0))
                .child(at(cx, 16.0, 12.0).child(checkbox("checkbox.rest", "Read-only")))
                .child(at(cx, 128.0, 12.0).child(checkbox("checkbox.checked", "Read-only").checked(true)))
                .child(at(cx, 240.0, 12.0).child(checkbox("checkbox.hovered", "Read-only").look(Look::HOVERED)))
                .child(at(cx, 352.0, 12.0).child(checkbox("checkbox.focused", "Read-only").checked(true).look(Look::FOCUSED)))
                .child(at(cx, 16.0, 44.0).child(checkbox("checkbox.disabled", "Read-only").enabled(false)))
                .child(at(cx, 128.0, 44.0).child(checkbox("checkbox.disabled.checked", "Read-only").checked(true).enabled(false)))
                .child(at(cx, 240.0, 44.0).child(checkbox("checkbox.error", "Read-only").error(true)))
                .child(at(cx, 352.0, 44.0).child(checkbox("checkbox.error.checked", "Read-only").checked(true).error(true)))
                .child(at(cx, 16.0, 76.0).child(checkbox("tri.off", "Read-only").state(ToggleState::Off)))
                .child(at(cx, 128.0, 76.0).child(checkbox("tri.on", "Read-only").state(ToggleState::On)))
                .child(at(cx, 240.0, 76.0).child(checkbox("tri.indeterminate", "Read-only").state(ToggleState::Indeterminate)))
                .child(at(cx, 352.0, 76.0).child(checkbox("tri.indeterminate.disabled", "Read-only").state(ToggleState::Indeterminate).enabled(false)))
        )
        .into_any_element()
}
