//! Twin of `LazyTreeSpecimen.kt`: `public` open with `users` selected in an unfocused tree, `audit` closed, `readme` a
//! leaf, with the explorer's leaf inset.

use std::rc::Rc;

use gpui::{App, IntoElement, ParentElement, Styled, Window, div};
use tg_ui::{ThemeExt, island, tree};
use tg_ui::tree::Item;

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "lazy-tree",
        width: 220.0,
        height: 140.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let items = Rc::new(vec![
        Item::node("public".into(), vec![Item::leaf("users".into()), Item::leaf("orders".into())]),
        Item::node("audit".into(), vec![Item::leaf("log".into())]),
        Item::leaf("readme".into()),
    ]);
    canvas(cx, 220.0, 140.0)
        .child(
            island().w(t.dp(220.0)).h(t.dp(140.0)).child(
                div().size_full().py(t.dp(4.0)).child(
                    tree("tree", items)
                        .leaf_inset()
                        .initially_open(["public".into()])
                        .initially_selected("users")
                        .focused_look(false),
                ),
            ),
        )
        .into_any_element()
}
