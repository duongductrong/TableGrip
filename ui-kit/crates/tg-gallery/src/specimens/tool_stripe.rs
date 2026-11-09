//! Twin of `ToolStripeSpecimen.kt`: the 44dp stripe with tools, a rule, actions and the bottom buttons.

use gpui::{App, IntoElement, ParentElement, Window};
use tg_ui::{StripeButton, stripe_button, tool_stripe};

use crate::{Specimen, canvas};

pub fn specimen() -> Specimen {
    Specimen {
        name: "tool-stripe",
        width: 44.0,
        height: 320.0,
        render,
    }
}

/// The stripe's buttons as the Kotlin specimen and the main screen use them.
pub fn stripe(prefix: &str) -> tg_ui::ToolStripe {
    let b = |id: &str, key: &'static str, tip: &str| -> StripeButton {
        stripe_button(format!("{prefix}.{id}"), key).tooltip(tip.to_owned())
    };
    tool_stripe(vec![
        b("explorer", "Nodes.DataSchema", "Connections").selected(true),
        b("history", "Vcs.History", "Query History"),
        b("queries", "Nodes.Bookmark", "Saved Queries"),
    ])
    .actions(vec![
        b("goto", "Actions.Search", "Go to Anything"),
        b("new", "General.Add", "New Connection"),
    ])
    .bottom(vec![
        b("shortcuts", "General.Keyboard", "Keyboard Shortcuts"),
        b("settings", "General.Settings", "Settings"),
    ])
}

fn render(_: &mut Window, cx: &mut App) -> gpui::AnyElement {
    canvas(cx, 44.0, 320.0)
        .child(stripe("tool-stripe"))
        .into_any_element()
}
