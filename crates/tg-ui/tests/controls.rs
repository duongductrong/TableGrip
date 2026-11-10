//! Headless GPUI tests for the Phase 2 controls: real clicks and keystrokes through GPUI's dispatch.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use gpui::{
    Context, InteractiveElement, IntoElement, Modifiers, ParentElement, Render, Styled,
    TestAppContext, VisualTestContext, Window, div, point, px,
};
use tg_theme::{AppearanceSettings, Registry};
use tg_ui::{
    ThemeExt, ToggleState, button, checkbox, icon_button, island, radio_button, set_theme,
};
use tg_ui_logic::tooltip::SHOW_DELAY_MS;

/// Counters the controls bump, so a test can read what fired.
#[derive(Default)]
struct Log {
    saves: Cell<u32>,
    cancels: Cell<u32>,
    toggles: RefCell<Vec<bool>>,
    header_clicks: Cell<u32>,
    chosen: Cell<Option<usize>>,
    runs: Cell<u32>,
}

struct Controls {
    log: Rc<Log>,
    enabled: bool,
    checked: bool,
    selected: usize,
}

impl Render for Controls {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let log = self.log.clone();
        let t = cx.theme().clone();
        let (save, cancel, toggle, header, run) = (
            log.clone(),
            log.clone(),
            log.clone(),
            log.clone(),
            log.clone(),
        );
        let rows: Vec<_> = ["True", "False", "NULL"]
            .into_iter()
            .enumerate()
            .map(|(i, label)| {
                let log = log.clone();
                radio_button(format!("radio.{i}"), label, self.selected == i)
                    .on_click(move |_, _| log.chosen.set(Some(i)))
            })
            .collect();
        div().id("root").child(
            island()
                .w(t.dp(400.0))
                .h(t.dp(300.0))
                .flex()
                .flex_col()
                .gap(t.dp(8.0))
                .child(
                    div()
                        .flex()
                        .gap(t.dp(8.0))
                        .child(
                            button("save", "Save")
                                .enabled(self.enabled)
                                .on_click(move |_, _| save.saves.set(save.saves.get() + 1)),
                        )
                        .child(
                            button("cancel", "Cancel")
                                .outlined()
                                .on_click(move |_, _| cancel.cancels.set(cancel.cancels.get() + 1)),
                        ),
                )
                .child(
                    checkbox("read-only", "Read-only")
                        .checked(self.checked)
                        .on_toggle(move |v, _, _| toggle.toggles.borrow_mut().push(v)),
                )
                .child(
                    checkbox("header", "All")
                        .state(ToggleState::Indeterminate)
                        .on_click(move |_, _| {
                            header.header_clicks.set(header.header_clicks.get() + 1)
                        }),
                )
                .child(checkbox("disabled", "Locked").enabled(false).on_toggle({
                    let log = log.clone();
                    move |v, _, _| log.toggles.borrow_mut().push(v)
                }))
                .child(div().flex().flex_col().children(rows))
                .child(
                    icon_button("run", "Actions.Execute")
                        .tooltip("Run")
                        .on_click(move |_, _| run.runs.set(run.runs.get() + 1)),
                ),
        )
    }
}

fn open(cx: &mut TestAppContext, enabled: bool) -> (Rc<Log>, &mut VisualTestContext) {
    cx.update(|cx| {
        set_theme(
            cx,
            AppearanceSettings::default().theme(&Registry::new(), true),
        );
    });
    let log = Rc::new(Log::default());
    let l = log.clone();
    let (_, cx) = cx.add_window_view(move |_, _| Controls {
        log: l,
        enabled,
        checked: false,
        selected: 0,
    });
    (log, cx)
}

fn center(cx: &mut VisualTestContext, selector: &'static str) -> gpui::Point<gpui::Pixels> {
    cx.debug_bounds(selector)
        .unwrap_or_else(|| panic!("no element '{selector}'"))
        .center()
}

fn click(cx: &mut VisualTestContext, selector: &'static str) {
    let at = center(cx, selector);
    cx.simulate_click(at, Modifiers::none());
}

fn focused(cx: &mut VisualTestContext) -> bool {
    cx.update(|window, app| window.focused(app).is_some())
}

#[gpui::test]
fn a_click_fires_a_button_once_and_focuses_it(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "save");
    click(cx, "cancel");
    assert_eq!((log.saves.get(), log.cancels.get()), (1, 1));
    assert!(focused(cx));
}

#[gpui::test]
fn space_and_enter_activate_a_focused_button_but_a_chord_does_not(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "save");
    cx.simulate_keystrokes("space enter");
    assert_eq!(log.saves.get(), 3);
    let chord = if cfg!(target_os = "macos") {
        "cmd-enter"
    } else {
        "ctrl-enter"
    };
    cx.simulate_keystrokes(chord);
    assert_eq!(log.saves.get(), 3, "a modified Enter is not activation");
}

#[gpui::test]
fn a_disabled_button_ignores_clicks_and_keys_and_is_skipped_by_tab(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, false);
    click(cx, "save");
    assert_eq!(log.saves.get(), 0);
    assert!(!focused(cx));
    cx.update(|window, _| window.focus_next());
    cx.simulate_keystrokes("enter");
    assert_eq!(
        (log.saves.get(), log.cancels.get()),
        (0, 1),
        "Tab lands on Cancel"
    );
}

#[gpui::test]
fn the_focus_ring_takes_no_room_in_the_layout(cx: &mut TestAppContext) {
    let (_, cx) = open(cx, true);
    let before = [
        cx.debug_bounds("save").unwrap(),
        cx.debug_bounds("cancel").unwrap(),
        cx.debug_bounds("read-only").unwrap(),
        cx.debug_bounds("radio.0").unwrap(),
    ];
    for target in ["save", "cancel", "read-only", "radio.0"] {
        click(cx, target);
        assert!(focused(cx));
        let after = [
            cx.debug_bounds("save").unwrap(),
            cx.debug_bounds("cancel").unwrap(),
            cx.debug_bounds("read-only").unwrap(),
            cx.debug_bounds("radio.0").unwrap(),
        ];
        assert_eq!(before, after, "focusing {target} moved something");
    }
}

#[gpui::test]
fn a_click_toggles_a_checkbox_and_reports_the_new_value(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "read-only");
    assert_eq!(*log.toggles.borrow(), vec![true]);
    cx.simulate_keystrokes("space");
    assert_eq!(
        *log.toggles.borrow(),
        vec![true, true],
        "the host owns the value, so Space asks again"
    );
}

#[gpui::test]
fn a_disabled_checkbox_neither_toggles_nor_takes_focus(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "disabled");
    assert!(log.toggles.borrow().is_empty());
    assert!(!focused(cx));
}

#[gpui::test]
fn a_tri_state_header_only_reports_activation(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "header");
    cx.simulate_keystrokes("enter");
    assert_eq!(log.header_clicks.get(), 2);
}

#[gpui::test]
fn radio_rows_are_each_a_tab_stop_and_arrows_do_nothing(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "radio.0");
    assert_eq!(log.chosen.get(), Some(0));
    log.chosen.set(None);
    cx.simulate_keystrokes("down right");
    assert_eq!(log.chosen.get(), None);
    cx.update(|window, _| window.focus_next());
    cx.simulate_keystrokes("space");
    assert_eq!(
        log.chosen.get(),
        Some(1),
        "Tab moves to the next row, Space chooses it"
    );
    cx.update(|window, _| window.focus_next());
    cx.simulate_keystrokes("enter");
    assert_eq!(log.chosen.get(), Some(2));
}

#[gpui::test]
fn a_click_runs_an_icon_button(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, true);
    click(cx, "run");
    assert_eq!(log.runs.get(), 1);
    cx.simulate_keystrokes("space");
    assert_eq!(log.runs.get(), 2);
}

fn hover(cx: &mut VisualTestContext, at: gpui::Point<gpui::Pixels>) {
    cx.simulate_mouse_move(at, None, Modifiers::none());
}

fn wait(cx: &mut VisualTestContext, ms: u64) {
    cx.executor().advance_clock(Duration::from_millis(ms));
    cx.run_until_parked();
}

/// Lets the window draw what the last input changed. The test window keeps two frames and can answer from the older
/// one, so it is drawn twice.
fn settle(cx: &mut VisualTestContext) {
    for _ in 0..2 {
        cx.update(|window, _| window.refresh());
        wait(cx, 1);
    }
}

fn tooltip_shown(cx: &mut VisualTestContext) -> bool {
    cx.debug_bounds("tooltip").is_some()
}

#[gpui::test]
fn the_tooltip_shows_after_the_delay_and_not_before(cx: &mut TestAppContext) {
    let (_, cx) = open(cx, true);
    let at = center(cx, "run");
    hover(cx, at);
    wait(cx, SHOW_DELAY_MS - 50);
    assert!(!tooltip_shown(cx), "too early");
    wait(cx, 100);
    assert!(tooltip_shown(cx));
}

#[gpui::test]
fn the_tooltip_goes_away_when_the_pointer_leaves(cx: &mut TestAppContext) {
    let (_, cx) = open(cx, true);
    let at = center(cx, "run");
    hover(cx, at);
    wait(cx, SHOW_DELAY_MS + 50);
    assert!(tooltip_shown(cx));
    hover(cx, point(px(390.0), px(290.0)));
    wait(cx, 10);
    assert!(!tooltip_shown(cx));
}

#[gpui::test]
fn a_press_hides_the_tooltip_and_it_stays_hidden(cx: &mut TestAppContext) {
    let (_, cx) = open(cx, true);
    let at = center(cx, "run");
    hover(cx, at);
    wait(cx, SHOW_DELAY_MS + 50);
    assert!(tooltip_shown(cx));
    cx.simulate_mouse_down(at, gpui::MouseButton::Left, Modifiers::none());
    settle(cx);
    assert!(!tooltip_shown(cx), "pressed");
    // Holding the button down, and resting on it, does not bring the tooltip back.
    wait(cx, SHOW_DELAY_MS * 2);
    settle(cx);
    assert!(!tooltip_shown(cx), "a press does not start the wait over");
}

#[gpui::test]
fn keyboard_focus_never_shows_the_tooltip(cx: &mut TestAppContext) {
    let (_, cx) = open(cx, true);
    // Tab to the icon button, the last control.
    for _ in 0..8 {
        cx.update(|window, _| window.focus_next());
    }
    wait(cx, SHOW_DELAY_MS * 4);
    assert!(!tooltip_shown(cx));
}
