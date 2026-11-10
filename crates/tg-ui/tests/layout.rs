//! Headless GPUI tests for the Phase 5 layout components: real pointer and key events through GPUI's dispatch, plus the
//! scrolling cost of a 10 000-row tree (run with `--nocapture` to see the numbers; `--release` for realistic ones).

use std::cell::RefCell;
use std::rc::Rc;
use std::time::{Duration, Instant};

use gpui::{
    Context, IntoElement, Modifiers, MouseButton, ParentElement, Render, ScrollDelta,
    ScrollWheelEvent, SharedString, Styled, TestAppContext, TouchPhase, VisualTestContext, Window,
    div, point, px,
};
use tg_theme::{AppearanceSettings, Registry};
use tg_ui::tree::{Item, TreeEvent};
use tg_ui::{
    ThemeExt, WorkspaceTab, island, set_theme, split_layout, tab_strip, tree, workspace_tabs,
};

#[derive(Default)]
struct Log {
    strip_selected: usize,
    strip_closed: Vec<usize>,
    tabs: Vec<WorkspaceTab>,
    tab_selected: usize,
    tab_closed: Vec<usize>,
    moves: Vec<(usize, usize)>,
    ratio: Option<f64>,
    events: Vec<TreeEvent<SharedString>>,
}

struct Host {
    log: Rc<RefCell<Log>>,
    items: Rc<Vec<Item<SharedString>>>,
    open: Vec<SharedString>,
}

impl Render for Host {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        let log = self.log.borrow();
        let (l1, l2, l3, l4, l5, l6, l7) = (
            self.log.clone(),
            self.log.clone(),
            self.log.clone(),
            self.log.clone(),
            self.log.clone(),
            self.log.clone(),
            self.log.clone(),
        );
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(
                div().w(t.dp(300.0)).child(
                    tab_strip(
                        "strip",
                        vec!["One".into(), "Two".into(), "Three".into()],
                        log.strip_selected,
                    )
                    .on_select(move |i, _, _| l1.borrow_mut().strip_selected = i)
                    .on_close(move |i, _, _| l2.borrow_mut().strip_closed.push(i)),
                ),
            )
            .child(
                div().w(t.dp(600.0)).child(
                    workspace_tabs("tabs", log.tabs.clone(), log.tab_selected)
                        .on_select(move |i, _, _| l3.borrow_mut().tab_selected = i)
                        .on_close(move |i, _, _| l4.borrow_mut().tab_closed.push(i))
                        .on_move(move |from, to, _, _| {
                            let mut l = l5.borrow_mut();
                            l.moves.push((from, to));
                            let tab = l.tabs.remove(from);
                            l.tabs.insert(to, tab);
                        }),
                ),
            )
            .child(
                div().w(t.dp(600.0)).h(t.dp(100.0)).child(
                    split_layout("split", 0.3, island().size_full(), island().size_full())
                        .min_widths(120.0, 200.0)
                        .on_ratio(move |r, _, _| l6.borrow_mut().ratio = Some(r)),
                ),
            )
            .child(
                div().w(t.dp(300.0)).h(t.dp(520.0)).child(
                    tree("tree", self.items.clone())
                        .initially_open(self.open.clone())
                        .on_event(move |e, _, _| l7.borrow_mut().events.push(e)),
                ),
            )
    }
}

fn small_tree() -> Rc<Vec<Item<SharedString>>> {
    Rc::new(vec![
        Item::node(
            "a".into(),
            vec![Item::leaf("a1".into()), Item::leaf("a2".into())],
        ),
        Item::node("b".into(), vec![Item::leaf("b1".into())]),
        Item::leaf("c".into()),
    ])
}

/// 100 nodes of 99 leaves each: 10 000 rows once every node is open.
fn big_tree() -> (Rc<Vec<Item<SharedString>>>, Vec<SharedString>) {
    let items: Vec<_> = (0..100)
        .map(|i| {
            Item::node(
                format!("n{i}").into(),
                (0..99)
                    .map(|j| Item::leaf(SharedString::from(format!("n{i}.{j}"))))
                    .collect(),
            )
        })
        .collect();
    let keys = items.iter().map(|i| i.key.clone()).collect();
    (Rc::new(items), keys)
}

fn open(
    cx: &mut TestAppContext,
    items: Rc<Vec<Item<SharedString>>>,
) -> (Rc<RefCell<Log>>, &mut VisualTestContext) {
    open_with(cx, items, Vec::new())
}

fn open_with(
    cx: &mut TestAppContext,
    items: Rc<Vec<Item<SharedString>>>,
    open: Vec<SharedString>,
) -> (Rc<RefCell<Log>>, &mut VisualTestContext) {
    cx.update(|cx| {
        set_theme(
            cx,
            AppearanceSettings::default().theme(&Registry::new(), true),
        )
    });
    let log = Rc::new(RefCell::new(Log {
        tabs: vec![
            WorkspaceTab::new("users"),
            WorkspaceTab::new("Console 1").console(),
            WorkspaceTab::new("orders"),
        ],
        ..Log::default()
    }));
    let l = log.clone();
    let (_, cx) = cx.add_window_view(move |_, _| Host {
        log: l,
        items,
        open,
    });
    cx.run_until_parked();
    (log, cx)
}

fn center(cx: &mut VisualTestContext, selector: &str) -> gpui::Point<gpui::Pixels> {
    cx.debug_bounds(Box::leak(selector.to_owned().into_boxed_str()))
        .unwrap_or_else(|| panic!("no element '{selector}'"))
        .center()
}

fn shown(cx: &mut VisualTestContext, selector: &str) -> bool {
    cx.debug_bounds(Box::leak(selector.to_owned().into_boxed_str()))
        .is_some()
}

fn redraw(cx: &mut VisualTestContext) {
    cx.update(|window, _| window.refresh());
    cx.run_until_parked();
}

#[gpui::test]
fn a_tab_strip_click_selects_and_a_middle_click_closes_any_tab(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    let p = center(cx, "tab-strip.tab.2");
    cx.simulate_click(p, Modifiers::none());
    assert_eq!(log.borrow().strip_selected, 2);
    // Not closable, yet the middle button still asks to close it, as Jewel does.
    let p = center(cx, "tab-strip.tab.0");
    cx.simulate_mouse_down(p, MouseButton::Middle, Modifiers::none());
    cx.simulate_mouse_up(p, MouseButton::Middle, Modifiers::none());
    assert_eq!(log.borrow().strip_closed, [0]);
    assert_eq!(log.borrow().strip_selected, 2);
}

#[gpui::test]
fn a_focused_tab_strip_moves_with_the_arrows_and_wraps(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    // GPUI binds no Tab key by default: move focus as a Tab would.
    cx.update(|window, _| window.focus_next());
    cx.simulate_keystrokes("left");
    assert_eq!(log.borrow().strip_selected, 2);
    cx.simulate_keystrokes("right right home");
    assert_eq!(log.borrow().strip_selected, 0);
}

#[gpui::test]
fn a_workspace_tab_press_selects_and_a_middle_click_closes(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    let p = center(cx, "workspace-tabs.tab.2");
    cx.simulate_click(p, Modifiers::none());
    assert_eq!(log.borrow().tab_selected, 2);
    let p = center(cx, "workspace-tabs.tab.1");
    cx.simulate_mouse_down(p, MouseButton::Middle, Modifiers::none());
    assert_eq!(log.borrow().tab_closed, [1]);
    assert_eq!(log.borrow().tab_selected, 2);
    redraw(cx);
    let p = center(cx, "workspace-tabs.close.2");
    cx.simulate_click(p, Modifiers::none());
    assert_eq!(log.borrow().tab_closed, [1, 2]);
}

#[gpui::test]
fn dragging_a_workspace_tab_past_its_neighbour_reorders(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    let from = center(cx, "workspace-tabs.tab.0");
    let next = cx.debug_bounds("workspace-tabs.tab.1").unwrap();
    cx.simulate_mouse_down(from, MouseButton::Left, Modifiers::none());
    redraw(cx);
    // In small steps, past the middle of the second tab.
    let target = f32::from(next.center().x - from.x) + 10.0;
    for k in 1..=10 {
        let x = from.x + px(target * k as f32 / 10.0);
        cx.simulate_mouse_move(point(x, from.y), MouseButton::Left, Modifiers::none());
    }
    cx.simulate_mouse_up(
        point(from.x + px(target), from.y),
        MouseButton::Left,
        Modifiers::none(),
    );
    let l = log.borrow();
    assert_eq!(l.moves, [(0, 1)]);
    let titles: Vec<_> = l.tabs.iter().map(|t| t.title.to_string()).collect();
    assert_eq!(titles, ["Console 1", "users", "orders"]);
    assert_eq!(l.tab_selected, 0, "the press selected the dragged tab");
}

#[gpui::test]
fn dragging_the_gap_moves_the_split_within_the_minimums(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    let handle = center(cx, "split.handle");
    cx.simulate_mouse_down(handle, MouseButton::Left, Modifiers::none());
    redraw(cx);
    cx.simulate_mouse_move(
        point(handle.x + px(120.0), handle.y),
        MouseButton::Left,
        Modifiers::none(),
    );
    let ratio = log.borrow().ratio.expect("moved");
    assert!((ratio - 0.5).abs() < 0.01, "{ratio}");
    // Far right: the second pane keeps its 200 of 600.
    cx.simulate_mouse_move(
        point(px(590.0), handle.y),
        MouseButton::Left,
        Modifiers::none(),
    );
    let ratio = log.borrow().ratio.unwrap();
    assert!((ratio - 400.0 / 600.0).abs() < 0.01, "{ratio}");
    cx.simulate_mouse_up(
        point(px(590.0), handle.y),
        MouseButton::Left,
        Modifiers::none(),
    );
    cx.simulate_mouse_move(point(px(100.0), handle.y), None, Modifiers::none());
    assert!(
        (log.borrow().ratio.unwrap() - 400.0 / 600.0).abs() < 0.01,
        "released"
    );
}

#[gpui::test]
fn tree_clicks_with_modifiers_extend_and_toggle(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    let a = center(cx, "tree.row.a");
    cx.simulate_click(a, Modifiers::none());
    // A double click opens `a`.
    cx.simulate_event(gpui::MouseDownEvent {
        position: a,
        modifiers: Modifiers::none(),
        button: MouseButton::Left,
        click_count: 2,
        first_mouse: false,
    });
    redraw(cx);
    assert_eq!(
        log.borrow().events,
        [
            TreeEvent::Opened("a".into()),
            TreeEvent::Activated("a".into())
        ]
    );
    assert!(shown(cx, "tree.row.a2"));
    let c = center(cx, "tree.row.c");
    cx.simulate_click(c, Modifiers::shift());
    let primary = if cfg!(target_os = "macos") {
        Modifiers::command()
    } else {
        Modifiers::control()
    };
    let a1 = center(cx, "tree.row.a1");
    cx.simulate_click(a1, primary);
    // Keys move from the last clicked row: Down from a1 lands on a2 alone.
    cx.simulate_keystrokes("down");
    redraw(cx);
    cx.simulate_keystrokes("left");
    redraw(cx);
    assert_eq!(log.borrow().events.len(), 2, "Left on a leaf only moves up");
}

#[gpui::test]
fn ten_thousand_rows_build_only_what_is_in_view_and_scroll_cheaply(cx: &mut TestAppContext) {
    let (items, keys) = big_tree();
    let started = Instant::now();
    let (_, cx) = open_with(cx, items, keys);
    let first = started.elapsed();
    // Closing and reopening the first node rebuilds the 10 000 rows.
    let n0 = center(cx, "tree.row.n0");
    cx.simulate_click(n0, Modifiers::none());
    let toggled = Instant::now();
    cx.simulate_keystrokes("left");
    redraw(cx);
    cx.simulate_keystrokes("right");
    redraw(cx);
    let toggle = toggled.elapsed() / 2;
    cx.simulate_keystrokes("home");
    redraw(cx);
    assert!(shown(cx, "tree.row.n0.0"));
    assert!(
        !shown(cx, "tree.row.n99.98"),
        "rows out of view are never built"
    );

    let bounds = cx.debug_bounds("tree").unwrap();
    let at = bounds.center();
    let steps = 400;
    let mut frames = Vec::with_capacity(steps);
    for _ in 0..steps {
        let t0 = Instant::now();
        cx.simulate_event(ScrollWheelEvent {
            position: at,
            delta: ScrollDelta::Pixels(point(px(0.0), px(-78.0))),
            modifiers: Modifiers::none(),
            touch_phase: TouchPhase::Moved,
        });
        redraw(cx);
        frames.push(t0.elapsed());
    }
    let jump = Instant::now();
    cx.simulate_keystrokes("end");
    redraw(cx);
    let jump = jump.elapsed();
    assert!(shown(cx, "tree.row.n99.98"), "End reveals the last row");
    frames.sort();
    let mean = frames.iter().sum::<Duration>() / steps as u32;
    let p95 = frames[steps * 95 / 100];
    let max = *frames.last().unwrap();
    println!(
        "tree 10000 rows: first frame {:.1} ms, close/open a node {:.2} ms; scroll frame mean {:.2} ms, p95 {:.2} ms, max {:.2} ms over {steps} \
         wheel steps; End {:.2} ms",
        first.as_secs_f64() * 1e3,
        toggle.as_secs_f64() * 1e3,
        mean.as_secs_f64() * 1e3,
        p95.as_secs_f64() * 1e3,
        max.as_secs_f64() * 1e3,
        jump.as_secs_f64() * 1e3,
    );
    // A loose bound that holds even in a debug build; the release numbers are what the report quotes.
    assert!(
        p95 < Duration::from_millis(50),
        "scrolling is too slow: p95 {p95:?}"
    );
}

/// Presses tab `index`, moves the pointer by `dx` in small steps (redrawing between them), and releases.
fn drag_tab(cx: &mut VisualTestContext, index: usize, dx: f32, hold_frames: usize) {
    let from = center(cx, &format!("workspace-tabs.tab.{index}"));
    cx.simulate_mouse_down(from, MouseButton::Left, Modifiers::none());
    redraw(cx);
    for k in 1..=10 {
        let x = from.x + px(dx * k as f32 / 10.0);
        cx.simulate_mouse_move(point(x, from.y), MouseButton::Left, Modifiers::none());
        redraw(cx);
    }
    // Held still against an edge, the row keeps scrolling one step a frame.
    for _ in 0..hold_frames {
        redraw(cx);
    }
    cx.simulate_mouse_up(
        point(from.x + px(dx), from.y),
        MouseButton::Left,
        Modifiers::none(),
    );
    redraw(cx);
}

fn titles(log: &Rc<RefCell<Log>>) -> Vec<String> {
    log.borrow()
        .tabs
        .iter()
        .map(|t| t.title.to_string())
        .collect()
}

fn db_tabs(log: &Rc<RefCell<Log>>, cx: &mut VisualTestContext, n: usize) {
    log.borrow_mut().tabs = (1..=n)
        .map(|i| {
            WorkspaceTab::new(if i == 1 {
                "DB".to_owned()
            } else {
                format!("DB ({i})")
            })
        })
        .collect();
    redraw(cx);
}

#[gpui::test]
fn dragging_a_workspace_tab_left_past_its_neighbour_reorders(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    db_tabs(&log, cx, 3);
    drag_tab(cx, 2, -90.0, 0);
    assert_eq!(titles(&log), ["DB", "DB (3)", "DB (2)"]);
}

#[gpui::test]
fn a_short_workspace_tab_drag_leaves_the_order_alone(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    db_tabs(&log, cx, 3);
    drag_tab(cx, 1, 15.0, 0);
    assert_eq!(titles(&log), ["DB", "DB (2)", "DB (3)"]);
    assert_eq!(log.borrow().tab_selected, 1);
}

#[gpui::test]
fn holding_a_workspace_tab_against_the_edge_scrolls_and_carries_it(cx: &mut TestAppContext) {
    let (log, cx) = open(cx, small_tree());
    db_tabs(&log, cx, 10);
    drag_tab(cx, 0, 600.0, 300);
    let t = titles(&log);
    assert_eq!(t.last().map(String::as_str), Some("DB"), "{t:?}");
    assert_eq!(
        t[..9],
        (2..=10).map(|i| format!("DB ({i})")).collect::<Vec<_>>()[..]
    );
}
