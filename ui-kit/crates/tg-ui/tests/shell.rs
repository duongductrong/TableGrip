//! Headless tests for the Phase 6 shell: the keymap wired to GPUI actions and to GPUI's key phases, the title bar's
//! click regions in window pixels, the per-OS window chrome, the dialog's Esc and the tool stripe.
//!
//! macOS-only chrome (traffic lights, fullscreen) and Windows/Linux caption buttons cannot all be captured on one
//! machine; what they decide (insets, options, which elements exist and where) is checked here for every OS.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use gpui::{
    App, Context, FocusHandle, InteractiveElement, IntoElement, KeyDownEvent, Keystroke, Modifiers,
    ParentElement, Render, Stateful, Styled, TestAppContext, VisualTestContext, Window,
    WindowDecorations, div, point, px,
};
use tg_theme::{AppearanceSettings, Registry};
use tg_ui::keymap::{
    RunCommand, bind_keymap, key_event, keystroke_string, preview_commands, set_recording,
};
use tg_ui::window_chrome::{
    CAPTION_WIDTH, MAC_LIGHTS, Os, Press, PxRect, SharedRegions, TitleBarRegions, insets,
    interactive, press_on_bar, title_bar, window_options,
};
use tg_ui::{ThemeExt, app_dialog, set_theme, stripe_button};
use tg_ui_logic::key_dispatch::{Phase, dispatch};
use tg_ui_logic::keymap::{Keymap, Platform, Preset, defaults};

fn theme(cx: &mut TestAppContext) {
    cx.update(|cx| {
        set_theme(
            cx,
            AppearanceSettings::default().theme(&Registry::new(), true),
        )
    });
}

// ----- keymap -----------------------------------------------------------------------------------------------------

#[test]
fn every_default_shortcut_round_trips_through_a_gpui_keystroke() {
    for platform in [Platform::Mac, Platform::Other] {
        let keymap = Keymap::new(platform, Preset::Default);
        let mut named = 0;
        for (id, shortcut) in defaults(platform) {
            let Some(chord) = keystroke_string(&shortcut, platform) else {
                continue;
            };
            named += 1;
            let keystroke = Keystroke::parse(&chord).unwrap_or_else(|e| panic!("{chord}: {e}"));
            let event = key_event(&keystroke, true);
            assert!(
                keymap.matching(&event).contains(&id),
                "{id} ({chord}) on {platform:?} is not matched by its own keystroke"
            );
        }
        assert!(named > 40, "only {named} shortcuts have a GPUI name");
    }
}

#[test]
fn a_chord_with_different_modifiers_is_a_different_shortcut() {
    let keymap = Keymap::new(Platform::Mac, Preset::Default);
    let plain = key_event(&Keystroke::parse("cmd-t").unwrap(), true);
    let shifted = key_event(&Keystroke::parse("cmd-shift-t").unwrap(), true);
    assert_eq!(keymap.matching(&plain), ["NewConsole"]);
    assert!(!keymap.matching(&shifted).contains(&"NewConsole"));
    // A key release never runs a shortcut.
    assert!(
        keymap
            .matching(&key_event(&Keystroke::parse("cmd-t").unwrap(), false))
            .is_empty()
    );
}

struct Commands {
    focus: FocusHandle,
    ran: Rc<RefCell<Vec<String>>>,
    preview: Rc<RefCell<Vec<String>>>,
    focused_ran: Rc<RefCell<Vec<String>>>,
    take: Rc<Cell<bool>>,
}

impl Render for Commands {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let ran = self.ran.clone();
        let preview = self.preview.clone();
        let take = self.take.clone();
        let focused_ran = self.focused_ran.clone();
        let root: Stateful<_> =
            div()
                .id("root")
                .size_full()
                .on_action(move |a: &RunCommand, _, _| {
                    ran.borrow_mut().push(a.id.to_string());
                });
        preview_commands(root, move |id, _, _| {
            preview.borrow_mut().push(id.to_owned());
            take.get()
        })
        .child(
            div()
                .id("focused")
                .track_focus(&self.focus)
                .size_full()
                .on_action(move |a: &RunCommand, _, cx| {
                    focused_ran.borrow_mut().push(a.id.to_string());
                    cx.propagate();
                }),
        )
    }
}

struct Window_ {
    ran: Rc<RefCell<Vec<String>>>,
    preview: Rc<RefCell<Vec<String>>>,
    focused_ran: Rc<RefCell<Vec<String>>>,
    take: Rc<Cell<bool>>,
    keymap: Keymap,
}

fn commands(cx: &mut TestAppContext) -> (Window_, &mut VisualTestContext) {
    theme(cx);
    // The test platform is not the Mac, whatever this machine is: bind what this build understands.
    let platform = if cfg!(target_os = "macos") {
        Platform::Mac
    } else {
        Platform::Other
    };
    let keymap = Keymap::new(platform, Preset::Default);
    cx.update(|cx| bind_keymap(cx, &keymap));
    let w = Window_ {
        ran: Rc::default(),
        preview: Rc::default(),
        focused_ran: Rc::default(),
        take: Rc::default(),
        keymap,
    };
    let (r, p, f, t) = (
        w.ran.clone(),
        w.preview.clone(),
        w.focused_ran.clone(),
        w.take.clone(),
    );
    let (view, cx) = cx.add_window_view(move |_, cx| Commands {
        focus: cx.focus_handle(),
        ran: r,
        preview: p,
        focused_ran: f,
        take: t,
    });
    cx.update(|window, cx| window.focus(&view.read(cx).focus));
    cx.run_until_parked();
    (w, cx)
}

fn chord(km: &Keymap, id: &str) -> String {
    keystroke_string(&km.binding(id).unwrap(), km.platform()).unwrap()
}

#[gpui::test]
fn a_bound_shortcut_runs_its_command_by_id(cx: &mut TestAppContext) {
    let (w, cx) = commands(cx);
    cx.simulate_keystrokes(&chord(&w.keymap, "NewConsole"));
    cx.simulate_keystrokes(&chord(&w.keymap, "CloseTab"));
    // The window's preview handler saw both, then the focused element, then the window's own handler; an unbound
    // key reaches none of them.
    assert_eq!(*w.preview.borrow(), ["NewConsole", "CloseTab"]);
    assert_eq!(*w.focused_ran.borrow(), ["NewConsole", "CloseTab"]);
    assert_eq!(*w.ran.borrow(), ["NewConsole", "CloseTab"]);
    cx.simulate_keystrokes("x");
    assert_eq!(w.ran.borrow().len(), 2);
}

#[gpui::test]
fn a_command_the_window_takes_in_preview_never_reaches_the_focused_element(
    cx: &mut TestAppContext,
) {
    let (w, cx) = commands(cx);
    w.take.set(true);
    cx.simulate_keystrokes(&chord(&w.keymap, "NewConsole"));
    assert_eq!(*w.preview.borrow(), ["NewConsole"]);
    assert!(w.focused_ran.borrow().is_empty());
    assert!(w.ran.borrow().is_empty());
}

#[gpui::test]
fn recording_a_key_silences_the_window_shortcuts_until_it_stops(cx: &mut TestAppContext) {
    let (mut w, cx) = commands(cx);
    let chord = chord(&w.keymap, "NewConsole");
    cx.update(|_, cx| set_recording(cx, &mut w.keymap, true));
    cx.simulate_keystrokes(&chord);
    assert!(w.preview.borrow().is_empty());
    assert!(
        w.keymap
            .window_matching(&key_event(&Keystroke::parse(&chord).unwrap(), true))
            .is_empty()
    );
    cx.update(|_, cx| set_recording(cx, &mut w.keymap, false));
    cx.simulate_keystrokes(&chord);
    assert_eq!(*w.ran.borrow(), ["NewConsole"]);
}

// ----- GPUI's key phases are the modelled ones --------------------------------------------------------------------

struct KeyTree {
    focus: FocusHandle,
    trace: Rc<RefCell<Vec<(usize, Phase)>>>,
    taker: Rc<Cell<Option<(usize, Phase)>>>,
}

impl KeyTree {
    fn layer(&self, id: &'static str, layer: usize) -> Stateful<gpui::Div> {
        let (t1, k1) = (self.trace.clone(), self.taker.clone());
        let (t2, k2) = (self.trace.clone(), self.taker.clone());
        div()
            .id(id)
            .capture_key_down(move |_: &KeyDownEvent, _, cx: &mut App| {
                t1.borrow_mut().push((layer, Phase::Preview));
                if k1.get() == Some((layer, Phase::Preview)) {
                    cx.stop_propagation();
                }
            })
            .on_key_down(move |_: &KeyDownEvent, _, cx: &mut App| {
                t2.borrow_mut().push((layer, Phase::Bubble));
                if k2.get() == Some((layer, Phase::Bubble)) {
                    cx.stop_propagation();
                }
            })
    }
}

impl Render for KeyTree {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        self.layer("l0", 0).size_full().child(
            self.layer("l1", 1)
                .size_full()
                .child(self.layer("l2", 2).track_focus(&self.focus).size_full()),
        )
    }
}

#[gpui::test]
fn gpui_runs_key_handlers_in_the_order_the_dispatch_model_describes(cx: &mut TestAppContext) {
    theme(cx);
    let trace = Rc::new(RefCell::new(Vec::new()));
    let taker = Rc::new(Cell::new(None));
    let (t, k) = (trace.clone(), taker.clone());
    let (view, cx) = cx.add_window_view(move |_, cx| KeyTree {
        focus: cx.focus_handle(),
        trace: t,
        taker: k,
    });
    cx.update(|window, cx| window.focus(&view.read(cx).focus));
    cx.run_until_parked();
    let mut takers = vec![None];
    for layer in 0..3 {
        takers.push(Some((layer, Phase::Preview)));
        takers.push(Some((layer, Phase::Bubble)));
    }
    for who in takers {
        taker.set(who);
        trace.borrow_mut().clear();
        cx.simulate_keystrokes("x");
        let model = dispatch(3, |layer, phase| who == Some((layer, phase)));
        let expected: Vec<_> = model.trace.iter().map(|v| (v.layer, v.phase)).collect();
        assert_eq!(*trace.borrow(), expected, "taken by {who:?}");
    }
}

// ----- window chrome ----------------------------------------------------------------------------------------------

#[test]
fn each_os_leaves_room_for_its_own_window_controls() {
    let mac = insets(Os::Mac, false);
    assert_eq!((mac.left, mac.right), (MAC_LIGHTS, 0.0));
    // Fullscreen hides the traffic lights, and the room for them.
    assert_eq!(insets(Os::Mac, true).left, 0.0);
    for os in [Os::Windows, Os::Linux] {
        let i = insets(os, false);
        assert_eq!((i.left, i.right), (0.0, 3.0 * CAPTION_WIDTH), "{os:?}");
    }
    assert!(Os::Windows.draws_caption_buttons() && Os::Linux.draws_caption_buttons());
    assert!(!Os::Mac.draws_caption_buttons());
}

#[test]
fn window_options_hand_the_title_bar_to_us_the_way_each_os_needs() {
    let mac = window_options(Os::Mac, "TableGrip", 40.0);
    let bar = mac.titlebar.as_ref().unwrap();
    assert!(bar.appears_transparent);
    assert_eq!(bar.traffic_light_position, Some(point(px(13.0), px(13.0))));
    assert!(mac.window_decorations.is_none());

    let windows = window_options(Os::Windows, "TableGrip", 40.0);
    assert!(windows.titlebar.as_ref().unwrap().appears_transparent);
    assert!(
        windows
            .titlebar
            .as_ref()
            .unwrap()
            .traffic_light_position
            .is_none()
    );

    let linux = window_options(Os::Linux, "TableGrip", 40.0);
    assert_eq!(linux.window_decorations, Some(WindowDecorations::Client));
}

#[test]
fn a_dialog_island_is_concentric_with_the_window_corner() {
    assert_eq!(tg_ui::app_dialog::island_radius(4.0), 10.0);
    assert_eq!(tg_ui::app_dialog::island_radius(0.0), 14.0);
    assert_eq!(
        tg_ui::app_dialog::island_radius(12.0),
        4.0,
        "never sharper than 4dp"
    );
}

#[test]
fn a_press_on_the_bar_is_a_click_a_drag_or_a_zoom_by_window_pixel() {
    let mut regions = TitleBarRegions::default();
    regions.set(
        "connection",
        PxRect {
            x: 100.0,
            y: 4.0,
            w: 80.0,
            h: 30.0,
        },
    );
    assert_eq!(press_on_bar(&regions, 120.0, 10.0, 1), Press::Click);
    assert_eq!(
        press_on_bar(&regions, 120.0, 10.0, 2),
        Press::Click,
        "an interactive element keeps its double click"
    );
    assert_eq!(press_on_bar(&regions, 99.9, 10.0, 1), Press::Drag);
    assert_eq!(
        press_on_bar(&regions, 180.0, 10.0, 1),
        Press::Drag,
        "the far edge is outside"
    );
    assert_eq!(press_on_bar(&regions, 300.0, 10.0, 2), Press::ZoomToggle);
    regions.remove("connection");
    assert_eq!(press_on_bar(&regions, 120.0, 10.0, 1), Press::Drag);
}

struct Bar {
    os: Os,
    regions: SharedRegions,
}

impl Render for Bar {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let t = cx.theme().clone();
        div().w(t.dp(600.0)).child(
            title_bar(self.os, 40.0, &self.regions)
                .left(interactive(
                    &self.regions,
                    "connection",
                    div()
                        .id("connection")
                        .debug_selector(|| "connection".into())
                        .w(t.dp(80.0))
                        .h(t.dp(30.0)),
                ))
                .title(div().debug_selector(|| "title".into()).child("users")),
        )
    }
}

fn bar(cx: &mut TestAppContext, os: Os) -> (SharedRegions, &mut VisualTestContext) {
    theme(cx);
    let regions: SharedRegions = Rc::new(RefCell::new(TitleBarRegions::default()));
    let r = regions.clone();
    let (_, cx) = cx.add_window_view(move |_, _| Bar { os, regions: r });
    cx.run_until_parked();
    cx.update(|window, _| window.refresh());
    cx.run_until_parked();
    (regions, cx)
}

#[gpui::test]
fn an_interactive_element_registers_its_window_pixels_and_the_bare_bar_does_not(
    cx: &mut TestAppContext,
) {
    let (regions, cx) = bar(cx, Os::Mac);
    let b = cx.debug_bounds("connection").expect("connection drawn");
    let (cx_, cy_) = (f32::from(b.center().x), f32::from(b.center().y));
    assert!(regions.borrow().is_interactive(cx_, cy_));
    assert!(
        !regions
            .borrow()
            .is_interactive(f32::from(b.right()) + 40.0, cy_)
    );
    // The traffic lights' gap is kept free on the left: the connection starts after it.
    assert!(
        f32::from(b.left()) >= MAC_LIGHTS,
        "starts at {:?}",
        b.left()
    );
}

#[gpui::test]
fn windows_and_linux_draw_caption_buttons_at_the_right_end_and_the_mac_does_not(
    cx: &mut TestAppContext,
) {
    for os in [Os::Windows, Os::Linux] {
        let (_, cx2) = bar(cx, os);
        let close = cx2
            .debug_bounds("caption.close")
            .unwrap_or_else(|| panic!("{os:?}: no close button"));
        let min = cx2.debug_bounds("caption.minimize").unwrap();
        let max = cx2.debug_bounds("caption.maximize").unwrap();
        assert_eq!(f32::from(close.size.width), CAPTION_WIDTH);
        assert!(
            min.right() <= max.left() + px(0.5) && max.right() <= close.left() + px(0.5),
            "order min, max, close"
        );
        assert_eq!(
            f32::from(close.right()),
            600.0,
            "{os:?}: flush with the window's right edge"
        );
        assert_eq!(f32::from(close.size.height), 40.0);
    }
    let (_, cx2) = bar(cx, Os::Mac);
    assert!(cx2.debug_bounds("caption.close").is_none());
}

// ----- dialog and stripe ------------------------------------------------------------------------------------------

struct Dialog {
    focus: FocusHandle,
    regions: SharedRegions,
    closed: Rc<Cell<u32>>,
    takes_escape: Rc<Cell<bool>>,
    close_on_escape: bool,
}

impl Render for Dialog {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let closed = self.closed.clone();
        let takes = self.takes_escape.clone();
        let t = cx.theme().clone();
        div().w(t.dp(300.0)).h(t.dp(200.0)).child(
            app_dialog(Os::Mac, "Export data", &self.regions, move |_, _| {
                closed.set(closed.get() + 1)
            })
            .close_on_escape(self.close_on_escape)
            .child(
                div()
                    .id("content")
                    .track_focus(&self.focus)
                    .size_full()
                    .on_key_down(move |e: &KeyDownEvent, _, cx| {
                        if e.keystroke.key == "escape" && takes.get() {
                            cx.stop_propagation();
                        }
                    }),
            ),
        )
    }
}

fn dialog(
    cx: &mut TestAppContext,
    close_on_escape: bool,
) -> (Rc<Cell<u32>>, Rc<Cell<bool>>, &mut VisualTestContext) {
    theme(cx);
    let closed = Rc::new(Cell::new(0));
    let takes = Rc::new(Cell::new(false));
    let (c, k) = (closed.clone(), takes.clone());
    let (view, cx) = cx.add_window_view(move |_, cx| Dialog {
        focus: cx.focus_handle(),
        regions: Rc::new(RefCell::new(TitleBarRegions::default())),
        closed: c,
        takes_escape: k,
        close_on_escape,
    });
    cx.update(|window, cx| window.focus(&view.read(cx).focus));
    cx.run_until_parked();
    (closed, takes, cx)
}

#[gpui::test]
fn only_a_bare_escape_nothing_inside_took_closes_the_dialog(cx: &mut TestAppContext) {
    let (closed, takes, cx) = dialog(cx, true);
    cx.simulate_keystrokes("enter");
    cx.simulate_keystrokes("shift-escape");
    assert_eq!(closed.get(), 0);
    takes.set(true);
    cx.simulate_keystrokes("escape");
    assert_eq!(closed.get(), 0, "a popup or editor inside took the key");
    takes.set(false);
    cx.simulate_keystrokes("escape");
    assert_eq!(closed.get(), 1);
}

#[gpui::test]
fn a_dialog_that_does_not_close_on_escape_stays_open(cx: &mut TestAppContext) {
    let (closed, _, cx) = dialog(cx, false);
    cx.simulate_keystrokes("escape");
    assert_eq!(closed.get(), 0);
}

struct Stripe {
    clicks: Rc<Cell<u32>>,
    focus_probe: Rc<Cell<bool>>,
}

impl Render for Stripe {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        let c = self.clicks.clone();
        let _ = &self.focus_probe;
        div().child(
            stripe_button("stripe.explorer", "Nodes.DataSchema")
                .tooltip("Connections")
                .on_click(move |_, _| c.set(c.get() + 1)),
        )
    }
}

#[gpui::test]
fn a_stripe_button_is_32dp_and_a_click_or_enter_activates_it(cx: &mut TestAppContext) {
    theme(cx);
    let clicks = Rc::new(Cell::new(0));
    let c = clicks.clone();
    let (_, cx) = cx.add_window_view(move |_, _| Stripe {
        clicks: c,
        focus_probe: Rc::new(Cell::new(false)),
    });
    cx.run_until_parked();
    let b = cx.debug_bounds("stripe.explorer").unwrap();
    assert_eq!(
        (f32::from(b.size.width), f32::from(b.size.height)),
        (32.0, 32.0)
    );
    cx.simulate_click(b.center(), Modifiers::none());
    assert_eq!(clicks.get(), 1);
    cx.simulate_keystrokes("enter");
    assert_eq!(
        clicks.get(),
        2,
        "the click focused it, so Enter activates it too"
    );
}
