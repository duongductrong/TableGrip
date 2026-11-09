//! Headless GPUI tests for the Phase 4 popups: real clicks and keystrokes through GPUI's dispatch.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use gpui::{
    Bounds, Context, InteractiveElement, IntoElement, Modifiers, ParentElement, Render, Styled,
    TestAppContext, VisualTestContext, Window, div, point, px, size,
};
use tg_theme::{AppearanceSettings, Registry};
use tg_ui::{
    ThemeExt, island, list_combo_box, menu_item, popup_list, popup_menu, scrollbar, set_theme,
};

#[derive(Default)]
struct Log {
    open: Cell<bool>,
    chosen: Cell<Option<usize>>,
    selected: Cell<usize>,
    scrolled: RefCell<Vec<f64>>,
}

struct Host {
    log: Rc<Log>,
    /// Where the menu's trigger is, so a test can put it near an edge.
    trigger: Bounds<gpui::Pixels>,
    /// Put the trigger at the bottom of the window instead.
    at_bottom: bool,
}

impl Render for Host {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        if self.at_bottom {
            self.trigger.origin.y = window.viewport_size().height - px(30.0);
        }
        let log = self.log.clone();
        let (choose, dismiss, combo_log, pick_log, scroll_log) = (
            log.clone(),
            log.clone(),
            log.clone(),
            log.clone(),
            log.clone(),
        );
        let items = vec![
            menu_item("Auto-commit"),
            menu_item("Manual commit").separator_after(),
            menu_item("Commit").disabled(),
            menu_item("Roll Back"),
        ];
        let mut root = div().id("root").size_full().child(
            island()
                .w(t.dp(400.0))
                .h(t.dp(300.0))
                .child(
                    div()
                        .absolute()
                        .left(self.trigger.origin.x)
                        .top(self.trigger.origin.y)
                        .w(self.trigger.size.width)
                        .h(self.trigger.size.height)
                        .debug_selector(|| "trigger".into()),
                )
                .child(
                    div()
                        .absolute()
                        .left(t.dp(200.0))
                        .top(t.dp(20.0))
                        .w(t.dp(160.0))
                        .child(
                            list_combo_box(
                                "combo",
                                vec!["MySQL".into(), "PostgreSQL".into(), "SQLite".into()],
                                log.selected.get(),
                            )
                            .on_change(move |i, _, _| combo_log.selected.set(i)),
                        ),
                )
                .child(
                    div()
                        .absolute()
                        .left(t.dp(200.0))
                        .top(t.dp(120.0))
                        .w(t.dp(144.0))
                        .child(
                            popup_list(
                                "list",
                                vec!["Row 0".into(), "Row 1".into(), "Row 2".into()],
                            )
                            .on_pick(move |i, _, _| pick_log.chosen.set(Some(100 + i))),
                        ),
                )
                .child(
                    div()
                        .absolute()
                        .left(t.dp(380.0))
                        .top(t.dp(100.0))
                        .w(t.dp(8.0))
                        .h(t.dp(160.0))
                        .child(
                            scrollbar("bar", 160.0, 640.0, 0.0)
                                .on_scroll(move |v, _, _| scroll_log.scrolled.borrow_mut().push(v)),
                        ),
                ),
        );
        if log.open.get() {
            root = root.child(
                popup_menu("menu", items)
                    .below(self.trigger)
                    .on_choose(move |i, _, _| choose.chosen.set(Some(i)))
                    .on_dismiss(move |_, _| dismiss.open.set(false)),
            );
        }
        root
    }
}

fn open(
    cx: &mut TestAppContext,
    trigger: Bounds<gpui::Pixels>,
) -> (Rc<Log>, &mut VisualTestContext) {
    open_at(cx, trigger, false)
}

fn open_at(
    cx: &mut TestAppContext,
    trigger: Bounds<gpui::Pixels>,
    at_bottom: bool,
) -> (Rc<Log>, &mut VisualTestContext) {
    cx.update(|cx| {
        set_theme(
            cx,
            AppearanceSettings::default().theme(&Registry::new(), true),
        )
    });
    let log = Rc::new(Log::default());
    let l = log.clone();
    let (_, cx) = cx.add_window_view(move |_, _| Host {
        log: l,
        trigger,
        at_bottom,
    });
    (log, cx)
}

fn at(x: f32, y: f32) -> Bounds<gpui::Pixels> {
    Bounds::new(point(px(x), px(y)), size(px(60.0), px(20.0)))
}

fn show_menu(log: &Rc<Log>, cx: &mut VisualTestContext) {
    log.open.set(true);
    cx.update(|window, _| window.refresh());
    cx.run_until_parked();
}

fn center(cx: &mut VisualTestContext, selector: &'static str) -> gpui::Point<gpui::Pixels> {
    cx.debug_bounds(selector)
        .unwrap_or_else(|| panic!("no element '{selector}'"))
        .center()
}

#[gpui::test]
fn a_click_on_an_enabled_item_chooses_it_and_closes_the_menu(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    show_menu(&log, cx);
    let p = center(cx, "popup-menu.item.3");
    cx.simulate_click(p, Modifiers::none());
    assert_eq!(log.chosen.get(), Some(3));
    assert!(!log.open.get());
}

#[gpui::test]
fn a_click_on_a_disabled_item_does_nothing_and_a_click_outside_closes(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    show_menu(&log, cx);
    let p = center(cx, "popup-menu.item.2");
    cx.simulate_click(p, Modifiers::none());
    assert_eq!(log.chosen.get(), None);
    assert!(log.open.get());
    cx.simulate_click(point(px(390.0), px(290.0)), Modifiers::none());
    assert!(!log.open.get());
    assert_eq!(log.chosen.get(), None);
}

#[gpui::test]
fn keys_move_over_enabled_items_and_enter_chooses(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    show_menu(&log, cx);
    // Opens on the first item; Down skips the disabled third one.
    cx.simulate_keystrokes("down down enter");
    assert_eq!(log.chosen.get(), Some(3));
    assert!(!log.open.get());
}

#[gpui::test]
fn escape_closes_without_choosing(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    show_menu(&log, cx);
    cx.simulate_keystrokes("escape");
    assert!(!log.open.get());
    assert_eq!(log.chosen.get(), None);
}

#[gpui::test]
fn the_menu_opens_below_its_trigger(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    show_menu(&log, cx);
    let menu = cx.debug_bounds("popup-menu").unwrap();
    let trigger = cx.debug_bounds("trigger").unwrap();
    assert!(menu.top() >= trigger.bottom());
}

#[gpui::test]
fn the_menu_flips_above_its_trigger_at_the_window_bottom(cx: &mut TestAppContext) {
    let (log, cx) = open_at(cx, at(20.0, 0.0), true);
    show_menu(&log, cx);
    let menu = cx.debug_bounds("popup-menu").unwrap();
    let trigger = cx.debug_bounds("trigger").unwrap();
    assert!(
        menu.bottom() <= trigger.top(),
        "menu {menu:?} trigger {trigger:?}"
    );
}

#[gpui::test]
fn a_combo_box_opens_on_click_and_a_row_changes_the_selection(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    let p = center(cx, "combo");
    cx.simulate_click(p, Modifiers::none());
    assert!(cx.debug_bounds("list-combo-box.list").is_some());
    let row = cx.debug_bounds("list-combo-box.list").unwrap();
    // The second row of three: below the first by one row's height.
    cx.simulate_click(
        point(row.center().x, row.top() + px(4.0 + 24.0 + 12.0)),
        Modifiers::none(),
    );
    assert_eq!(log.selected.get(), 1);
    // Choosing a row closed the list: the next click on the box opens it again rather than closing it.
    let p = center(cx, "combo");
    cx.simulate_click(p, Modifiers::none());
    assert!(cx.debug_bounds("list-combo-box.list").is_some());
}

#[gpui::test]
fn a_popup_list_picks_on_click_and_on_enter(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    let p = center(cx, "popup-list.row.2");
    cx.simulate_click(p, Modifiers::none());
    assert_eq!(log.chosen.get(), Some(102));
    cx.simulate_keystrokes("down enter");
    assert_eq!(log.chosen.get(), Some(100));
}

#[gpui::test]
fn a_click_on_the_scrollbar_track_jumps_the_thumb_to_the_spot(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, at(20.0, 20.0));
    let track = cx.debug_bounds("bar").unwrap();
    // Halfway down the 160dp track: the thumb (40dp of 160) lands with its top there, clamped to the end.
    cx.simulate_click(
        point(track.center().x, track.top() + px(100.0)),
        Modifiers::none(),
    );
    let scrolled = log.scrolled.borrow();
    assert_eq!(scrolled.len(), 1);
    let expected = (100.0 / 120.0 * 480.0f64).min(480.0);
    assert!((scrolled[0] - expected).abs() < 1.0, "{scrolled:?}");
}
