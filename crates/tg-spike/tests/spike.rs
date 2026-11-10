//! Headless GPUI tests: real clicks and keystrokes go through GPUI's dispatch into the spike view.

use gpui::{Modifiers, TestAppContext, VisualTestContext};
use tg_spike::{Spike, bind_keys};

fn open(cx: &mut TestAppContext) -> (gpui::Entity<Spike>, &mut VisualTestContext) {
    cx.update(bind_keys);
    cx.add_window_view(|_, cx| Spike::new(cx))
}

fn click(cx: &mut VisualTestContext, selector: &'static str) {
    let bounds = cx
        .debug_bounds(selector)
        .unwrap_or_else(|| panic!("no element '{selector}'"));
    cx.simulate_click(bounds.center(), Modifiers::none());
}

#[gpui::test]
fn clicking_the_checkbox_toggles_and_focuses_it(cx: &mut TestAppContext) {
    let (view, cx) = open(cx);
    click(cx, "checkbox");
    let focused = cx.update(|window, app| view.read(app).checkbox_focus.is_focused(window));
    assert!(view.read_with(cx, |v, _| v.read_only.checked));
    assert!(focused);
}

#[gpui::test]
fn space_toggles_the_focused_checkbox(cx: &mut TestAppContext) {
    let (view, cx) = open(cx);
    cx.update(|window, app| window.focus(&view.read(app).checkbox_focus));
    cx.simulate_keystrokes("space");
    assert!(view.read_with(cx, |v, _| v.read_only.checked));
}

#[gpui::test]
fn tab_moves_focus_to_the_button_and_enter_presses_it(cx: &mut TestAppContext) {
    let (view, cx) = open(cx);
    cx.update(|window, app| window.focus(&view.read(app).checkbox_focus));
    cx.simulate_keystrokes("tab enter");
    let on_button = cx.update(|window, app| view.read(app).button_focus.is_focused(window));
    assert!(on_button);
    assert_eq!(view.read_with(cx, |v, _| v.saves), 1);
}

#[gpui::test]
fn the_shortcut_toggles_through_the_keymap(cx: &mut TestAppContext) {
    let (view, cx) = open(cx);
    cx.update(|window, app| window.focus(&view.read(app).checkbox_focus));
    cx.simulate_keystrokes(if cfg!(target_os = "macos") {
        "cmd-k"
    } else {
        "ctrl-k"
    });
    assert!(view.read_with(cx, |v, _| v.read_only.checked));
}

#[gpui::test]
fn clicking_the_button_counts_once(cx: &mut TestAppContext) {
    let (view, cx) = open(cx);
    click(cx, "save");
    assert_eq!(view.read_with(cx, |v, _| v.saves), 1);
}
