//! Twin of `SplitSpecimen.kt`: two islands split 40/60 with the 6dp gap between them, inside a 6dp frame margin.

use gpui::{App, IntoElement, ParentElement, Styled, Window, div};
use tg_ui::{ThemeExt, island, split_layout};

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "split",
        width: 300.0,
        height: 120.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    canvas(cx, 300.0, 120.0)
        .child(
            div().w(t.dp(300.0)).h(t.dp(120.0)).p(t.dp(6.0)).child(split_layout(
                "split",
                0.4,
                island().size_full(),
                island().size_full(),
            )),
        )
        .into_any_element()
}
