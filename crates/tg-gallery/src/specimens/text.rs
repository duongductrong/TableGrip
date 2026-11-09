//! Twin of `TextSpecimen.kt`: the default style, the four colours the app passes, the editor style, a smaller and a
//! bold label, and an ellipsised one.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{TextColor, ThemeExt, island, text};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "text",
        width: 320.0,
        height: 200.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 320.0, 200.0)
        .child(
            island()
                .w(t.dp(320.0))
                .h(t.dp(200.0))
                .child(at(cx, 16.0, 12.0).child(text("Connections")))
                .child(at(cx, 16.0, 36.0).child(text("12 rows · 4 ms").color(TextColor::Info)))
                .child(at(cx, 16.0, 60.0).child(text("Connection refused").color(TextColor::Error)))
                .child(at(cx, 16.0, 84.0).child(text("Unsaved changes").color(TextColor::Warning)))
                .child(at(cx, 16.0, 108.0).child(text("Read-only").color(TextColor::Disabled)))
                .child(at(cx, 16.0, 132.0).child(text("SELECT * FROM users").editor()))
                .child(at(cx, 16.0, 160.0).child(text("PRIMARY KEY").size(11.0).color(TextColor::Info)))
                .child(at(cx, 176.0, 12.0).child(text("Tables").bold()))
                .child(
                    at(cx, 176.0, 36.0)
                        .w(t.dp(96.0))
                        .h(t.dp(20.0))
                        .child(text("customer_addresses_archive").ellipsis()),
                ),
        )
        .into_any_element()
}
