//! Headless GPUI tests: real clicks and keystrokes through GPUI's dispatch into the foundation components.

use std::cell::Cell;
use std::rc::Rc;

use gpui::{
    Context, InteractiveElement, IntoElement, Modifiers, ParentElement, Render, Styled,
    TestAppContext, VisualTestContext, Window, div,
};
use tg_theme::{AppearanceSettings, Registry};
use tg_ui::{ThemeExt, divider, island, link, set_theme, text};

struct Links {
    first: Rc<Cell<u32>>,
    second: Rc<Cell<u32>>,
    first_enabled: bool,
}

impl Render for Links {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let (first, second) = (self.first.clone(), self.second.clone());
        let t = cx.theme().clone();
        let content = island()
            .w(t.dp(200.0))
            .h(t.dp(80.0))
            .flex()
            .flex_col()
            .child(
                link("first", "Edit as SQL")
                    .enabled(self.first_enabled)
                    .on_click(move |_, _| first.set(first.get() + 1)),
            )
            .child(divider().w(t.dp(200.0)))
            .child(
                link("second", "Reset to defaults")
                    .on_click(move |_, _| second.set(second.get() + 1)),
            )
            .child(text("12 rows"));
        div()
            .id("root")
            .debug_selector(|| "island".into())
            .flex()
            .child(content)
    }
}

fn open(
    cx: &mut TestAppContext,
    zoom: i32,
    first_enabled: bool,
) -> (Rc<Cell<u32>>, Rc<Cell<u32>>, &mut VisualTestContext) {
    cx.update(|cx| {
        let settings = AppearanceSettings {
            zoom,
            ..AppearanceSettings::default()
        };
        set_theme(cx, settings.theme(&Registry::new(), true));
    });
    let (first, second) = (Rc::new(Cell::new(0)), Rc::new(Cell::new(0)));
    let (a, b) = (first.clone(), second.clone());
    let (_, cx) = cx.add_window_view(move |_, _| Links {
        first: a,
        second: b,
        first_enabled,
    });
    (first, second, cx)
}

fn click(cx: &mut VisualTestContext, selector: &'static str) {
    let bounds = cx
        .debug_bounds(selector)
        .unwrap_or_else(|| panic!("no element '{selector}'"));
    cx.simulate_click(bounds.center(), Modifiers::none());
}

fn focused(cx: &mut VisualTestContext) -> bool {
    cx.update(|window, app| window.focused(app).is_some())
}

#[gpui::test]
fn a_click_fires_the_link_once_and_focuses_it(cx: &mut TestAppContext) {
    let (first, _, cx) = open(cx, 100, true);
    click(cx, "first");
    assert_eq!(first.get(), 1);
    assert!(focused(cx));
}

#[gpui::test]
fn enter_and_space_fire_the_focused_link_even_with_modifiers(cx: &mut TestAppContext) {
    let (first, _, cx) = open(cx, 100, true);
    click(cx, "first");
    let primary = if cfg!(target_os = "macos") {
        "cmd-enter"
    } else {
        "ctrl-enter"
    };
    cx.simulate_keystrokes(&format!("enter space shift-enter {primary}"));
    assert_eq!(first.get(), 5);
}

#[gpui::test]
fn a_disabled_link_ignores_clicks_and_takes_no_focus(cx: &mut TestAppContext) {
    let (first, _, cx) = open(cx, 100, false);
    click(cx, "first");
    assert_eq!(first.get(), 0);
    assert!(!focused(cx));
}

#[gpui::test]
fn tab_skips_a_disabled_link(cx: &mut TestAppContext) {
    let (first, second, cx) = open(cx, 100, false);
    cx.update(|window, _| window.focus_next());
    cx.simulate_keystrokes("enter");
    assert_eq!((first.get(), second.get()), (0, 1));
}

#[gpui::test]
fn zoom_multiplies_every_size(cx: &mut TestAppContext) {
    let (_, _, cx) = open(cx, 200, true);
    let bounds = cx.debug_bounds("island").unwrap();
    assert_eq!(
        (f32::from(bounds.size.width), f32::from(bounds.size.height)),
        (400.0, 160.0)
    );
}

#[gpui::test]
fn zoom_snaps_values_typed_by_hand(cx: &mut TestAppContext) {
    let (_, _, cx) = open(cx, 104, true);
    let bounds = cx.debug_bounds("island").unwrap();
    assert_eq!(f32::from(bounds.size.width), 200.0);
}
