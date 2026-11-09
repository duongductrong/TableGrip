//! Twin of `ScrollbarSpecimen.kt`: three 64 × 160 dp panes of twenty 20 dp rows with the always-visible scrollbar, at
//! the top, scrolled to the middle with the pointer on the thumb, and at the end.

use gpui::{App, IntoElement, ParentElement, Styled, Window, div, px};
use tg_ui::{TextColor, ThemeExt, island, scrollbar, text};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "scrollbar",
        width: 240.0,
        height: 184.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let mut content = island().w(t.dp(240.0)).h(t.dp(184.0));
    for (i, (name, offset)) in [("top", 0.0), ("middle", 120.0), ("end", 240.0)].into_iter().enumerate() {
        let rows = div().absolute().left_0().top(-t.dp(offset)).w(t.dp(54.0)).flex().flex_col().children(
            (0..20).map(|n| div().flex_none().h(t.dp(20.0)).child(text(format!("Row {n}")).color(TextColor::Normal))),
        );
        let pane = at(cx, 12.0 + 76.0 * i as f32, 12.0)
            .w(t.dp(64.0))
            .h(t.dp(160.0))
            .overflow_hidden()
            .child(rows);
        let pane = pane.child(scrollbar(format!("track.{name}"), 160.0, 400.0, offset as f64).hovered(name == "middle"));
        content = content.child(pane);
    }
    let _ = px(0.0);
    canvas(cx, 240.0, 184.0).child(content).into_any_element()
}
