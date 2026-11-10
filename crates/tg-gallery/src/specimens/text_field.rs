//! Twin of `TextFieldSpecimen.kt`: text fields at rest, with a placeholder, disabled, with the error outline, masked
//! as a password and focused with everything selected (drawn in that look), plus a two-line TextArea.

use gpui::{App, Entity, IntoElement, ParentElement, Styled, Window};
use tg_ui::{TextInput, TextInputLook, ThemeExt, island};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "text-field",
        width: 416.0,
        height: 176.0,
        render,
    }
}

fn field(
    window: &mut Window,
    cx: &mut App,
    id: &'static str,
    make: impl FnOnce(&mut gpui::Context<TextInput>) -> TextInput,
) -> Entity<TextInput> {
    window.use_keyed_state(id, cx, |_, cx| make(cx))
}

fn render(window: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let w = t.dp(120.0);
    let fields = [
        (16.0, 16.0, field(window, cx, "field", |cx| TextInput::field("users", cx))),
        (148.0, 16.0, field(window, cx, "placeholder", |cx| TextInput::field("", cx).placeholder("Filter"))),
        (280.0, 16.0, field(window, cx, "disabled", |cx| TextInput::field("users", cx).disabled())),
        (16.0, 56.0, field(window, cx, "error", |cx| TextInput::field("users", cx).error(true))),
        (148.0, 56.0, field(window, cx, "password", |cx| TextInput::field("secret", cx).password())),
        (
            280.0,
            56.0,
            field(window, cx, "focused", |cx| {
                let mut f = TextInput::field("users", cx).look(TextInputLook::Focused);
                f.editor_mut().select_all();
                f
            }),
        ),
    ];
    let area = field(window, cx, "area", |cx| TextInput::area("SELECT *\nFROM users", cx));
    let mut content = island().w(t.dp(416.0)).h(t.dp(176.0));
    for (x, y, f) in fields {
        content = content.child(at(cx, x, y).w(w).child(f));
    }
    content = content.child(at(cx, 16.0, 96.0).w(t.dp(252.0)).h(t.dp(64.0)).child(area));
    canvas(cx, 416.0, 176.0).child(content).into_any_element()
}
