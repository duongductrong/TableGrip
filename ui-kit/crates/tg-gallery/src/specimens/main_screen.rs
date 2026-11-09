//! Twin of `MainScreenSpecimen.kt`: `MainScreen`'s empty frame: tool stripe, two islands split 30/70, status bar.

use gpui::{App, IntoElement, ParentElement, Styled, Window, div};
use tg_theme::Rgba;
use tg_ui::{TextColor, ThemeExt, island, split_layout, status_bar, status_divider, status_dot, status_widget, text};

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "main-screen",
        width: 480.0,
        height: 320.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let center = split_layout("main-screen.split", 0.3, island().size_full(), island().size_full());
    canvas(cx, 480.0, 320.0)
        .child(
            div().flex().flex_col().w(t.dp(480.0)).h(t.dp(320.0)).child(
                div()
                    .flex()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(super::tool_stripe::stripe("main-screen"))
                    .child(div().flex_1().h_full().child(center))
                    .child(div().flex_none().w(t.dp(6.0))),
            )
            .child(status(cx)),
        )
        .into_any_element()
}

const PENDING: Rgba = Rgba::rgb(0xD9822B);
const CONNECTED: Rgba = Rgba::rgb(0x5FB865);

/// The status bar `MainScreenSpecimen.kt` draws: breadcrumbs, the message, a pending widget, then the connection.
fn status(cx: &App) -> tg_ui::StatusBar {
    status_bar("Ready")
        .breadcrumbs(["prod-db", "public", "users"])
        .widget(
            status_widget("main-screen.pending")
                .clickable()
                .child(status_dot(cx, Some(PENDING)))
                .child(text("3 pending").color(TextColor::Custom(PENDING))),
        )
        .widget(status_divider(cx))
        .widget(
            status_widget("main-screen.connection")
                .clickable()
                .child(status_dot(cx, Some(CONNECTED)))
                .child(text("prod-db")),
        )
}
