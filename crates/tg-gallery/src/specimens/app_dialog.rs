//! Twin of `AppDialogSpecimen.kt`: the dialog frame inside the window: title bar, then the content island.

use std::{cell::RefCell, rc::Rc};

use gpui::{App, IntoElement, ParentElement, Styled, Window, div};
use tg_ui::window_chrome::Os;
use tg_ui::{ThemeExt, app_dialog, text};

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "app-dialog",
        width: 320.0,
        height: 200.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let regions = Rc::new(RefCell::new(Default::default()));
    canvas(cx, 320.0, 200.0)
        .child(
            div().w(t.dp(320.0)).h(t.dp(200.0)).child(
                // A bare bar: the traffic lights or caption buttons are the OS's and not part of the specimen.
                app_dialog(Os::current(), "Export data", &regions, |_, _| {})
                    .bare()
                    .child(
                        div()
                            .size_full()
                            .p(t.dp(12.0))
                            .child(text("Choose where to save the file.")),
                    ),
            ),
        )
        .into_any_element()
}
