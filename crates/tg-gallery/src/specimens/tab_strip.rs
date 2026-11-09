//! Twin of `TabStripSpecimen.kt`: three sections, the second selected, the third hovered.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{ThemeExt, island, tab_strip};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "tab-strip",
        width: 300.0,
        height: 56.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let labels = vec!["Columns 4".into(), "Indexes 2".into(), "Keys".into()];
    canvas(cx, 300.0, 56.0)
        .child(
            island()
                .w(t.dp(300.0))
                .h(t.dp(56.0))
                .child(at(cx, 8.0, 8.0).w(t.dp(284.0)).child(tab_strip("strip", labels, 1).hovered(2))),
        )
        .into_any_element()
}
