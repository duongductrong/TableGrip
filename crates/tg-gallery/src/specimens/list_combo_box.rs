//! Twin of `ListComboBoxSpecimen.kt`: closed combo boxes at rest, hovered, focused and disabled.

use gpui::{App, IntoElement, ParentElement, Styled, Window};
use tg_ui::{Look, ThemeExt, island, list_combo_box};

use crate::{Specimen, at, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "list-combo-box",
        width: 240.0,
        height: 144.0,
        render,
    }
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    let t = cx.theme().clone();
    let items = || vec!["PostgreSQL".into(), "MySQL".into(), "SQLite".into()];
    let mut content = island().w(t.dp(240.0)).h(t.dp(144.0));
    for (i, state) in ["rest", "hovered", "focused", "disabled"].into_iter().enumerate() {
        let mut combo = list_combo_box(format!("combo.{state}"), items(), 0).width(200.0);
        combo = match state {
            "hovered" => combo.look(Look::HOVERED),
            "focused" => combo.look(Look::FOCUSED),
            "disabled" => combo.enabled(false),
            _ => combo,
        };
        content = content.child(at(cx, 16.0, 12.0 + 32.0 * i as f32).child(combo));
    }
    canvas(cx, 240.0, 144.0).child(content).into_any_element()
}
