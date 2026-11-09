//! Plumbing the controls share.

use gpui::{App, ElementId, FocusHandle, Window};

/// The focus handle kept under [id] across frames, a Tab stop only while [tab_stop].
pub(crate) fn focus_handle(
    window: &mut Window,
    cx: &mut App,
    id: &gpui::SharedString,
    tab_stop: bool,
) -> FocusHandle {
    window
        .use_keyed_state(ElementId::Name(id.clone()), cx, |_, cx| cx.focus_handle())
        .read(cx)
        .clone()
        .tab_stop(tab_stop)
}
