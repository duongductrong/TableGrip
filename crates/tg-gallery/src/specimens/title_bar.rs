//! Twin of `TitleBarSpecimen.kt`: the bar's content without the OS's window controls: the connection on the left,
//! the screen's name centred, the header buttons on the right.

use std::{cell::RefCell, rc::Rc};

use gpui::{App, IntoElement, ParentElement, Styled, Window, div};
use tg_ui::window_chrome::{Os, bar_title, title_bar};
use tg_ui::{TextColor, ThemeExt, icon_button, text};

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "title-bar",
        width: 480.0,
        height: 40.0,
        render,
    }
}

/// The header buttons: 32dp with 3dp padding is a 26dp button around a 16dp icon.
pub fn header_buttons(cx: &App, prefix: &str) -> impl IntoElement {
    let t = cx.theme();
    div().flex().items_center().gap(t.dp(2.0)).children(
        [
            ("console", "Nodes.Console"),
            ("run", "Actions.Execute"),
            ("settings", "General.Settings"),
        ]
        .map(|(id, key)| {
            // The button is 26dp (32dp minus the padding) but the padding still takes room in the row.
            div()
                .flex()
                .items_center()
                .justify_center()
                .flex_none()
                .size(cx.theme().dp(32.0))
                .child(icon_button(format!("{prefix}.{id}"), key).size(26.0).icon_size(16.0))
        }),
    )
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let regions = Rc::new(RefCell::new(Default::default()));
    canvas(cx, 480.0, 40.0)
        .child(
            div().w(t.dp(480.0)).child(
                title_bar(Os::current(), 40.0, &regions)
                    .bare()
                    .left(div().pl(t.dp(8.0)).child(text("No connection").color(TextColor::Info)))
                    .title(bar_title("users"))
                    .right(header_buttons(cx, "title-bar")),
            ),
        )
        .into_any_element()
}
