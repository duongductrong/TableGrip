//! Headless GPUI tests of TextField and TextArea: keys, typed text and input-method calls through GPUI's dispatch and
//! input handler into `tg-text`, plus what only the drawing layer knows (pixel rows, scrolling, the caret's blink).
//!
//! GPUI's test text system gives every character the same advance, so x positions here are character columns.

use std::cell::RefCell;
use std::ops::Range;
use std::rc::Rc;
use std::time::Duration;

use gpui::{
    AppContext, Bounds, Context, Entity, EntityInputHandler, Focusable, InteractiveElement,
    IntoElement, Modifiers, ParentElement, Pixels, Render, Styled, TestAppContext,
    VisualTestContext, Window, div, point, px,
};
use tg_theme::{AppearanceSettings, Registry};
use tg_ui::{TextInput, ThemeExt, set_theme};

/// A field or area inside a parent that records the keys reaching it.
struct Host {
    input: Entity<TextInput>,
    bubbled: Rc<RefCell<Vec<String>>>,
    width: f32,
    height: f32,
}

impl Render for Host {
    fn render(&mut self, _: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let bubbled = self.bubbled.clone();
        let t = cx.theme().clone();
        div()
            .id("host")
            .on_key_down(move |e, _, _| bubbled.borrow_mut().push(e.keystroke.unparse()))
            .child(
                div()
                    .w(t.dp(self.width))
                    .h(t.dp(self.height))
                    .child(self.input.clone()),
            )
    }
}

const PRIMARY: &str = if cfg!(target_os = "macos") {
    "cmd"
} else {
    "ctrl"
};

fn open(
    cx: &mut TestAppContext,
    make: impl FnOnce(&mut Context<TextInput>) -> TextInput + 'static,
    width: f32,
    height: f32,
) -> (
    Entity<TextInput>,
    Rc<RefCell<Vec<String>>>,
    &mut VisualTestContext,
) {
    cx.update(|cx| {
        set_theme(
            cx,
            AppearanceSettings::default().theme(&Registry::new(), true),
        )
    });
    let bubbled = Rc::new(RefCell::new(Vec::new()));
    let b = bubbled.clone();
    let (host, cx) = cx.add_window_view(move |_, cx| Host {
        input: cx.new(make),
        bubbled: b,
        width,
        height,
    });
    let input = host.read_with(cx, |h, _| h.input.clone());
    cx.update(|window, cx| window.focus(&input.focus_handle(cx)));
    cx.run_until_parked();
    (input, bubbled, cx)
}

fn field(
    cx: &mut TestAppContext,
    make: impl FnOnce(&mut Context<TextInput>) -> TextInput + 'static,
) -> (
    Entity<TextInput>,
    Rc<RefCell<Vec<String>>>,
    &mut VisualTestContext,
) {
    open(cx, make, 120.0, 28.0)
}

fn text(input: &Entity<TextInput>, cx: &mut VisualTestContext) -> String {
    input.read_with(cx, |i, _| i.text().to_owned())
}

fn selection(input: &Entity<TextInput>, cx: &mut VisualTestContext) -> Range<usize> {
    input.read_with(cx, |i, _| i.editor().selection().range())
}

fn caret(input: &Entity<TextInput>, cx: &mut VisualTestContext) -> usize {
    input.read_with(cx, |i, _| i.editor().selection().head)
}

/// Where the text between two UTF-16 offsets is drawn, as the input method asks (to place its candidate window).
fn bounds_of(
    input: &Entity<TextInput>,
    range: Range<usize>,
    cx: &mut VisualTestContext,
) -> Bounds<Pixels> {
    input
        .update_in(cx, |i, window, cx| {
            i.bounds_for_range(range, Bounds::default(), window, cx)
        })
        .expect("laid out")
}

fn clipboard(cx: &mut VisualTestContext) -> Option<String> {
    cx.update(|_, cx| cx.read_from_clipboard().and_then(|i| i.text()))
}

#[gpui::test]
fn typing_and_editing_keys_reach_the_editor(cx: &mut TestAppContext) {
    let (input, bubbled, cx) = field(cx, |cx| TextInput::field("", cx));
    cx.simulate_input("users");
    // A printable key's key-down reaches the parent before the text arrives through the input handler: that is
    // GPUI's order on macOS, where the input method only sees keys nothing handled.
    bubbled.borrow_mut().clear();
    cx.simulate_keystrokes("backspace left left shift-left");
    assert_eq!(text(&input, cx), "user");
    assert_eq!(selection(&input, cx), 1..2);
    assert!(bubbled.borrow().is_empty(), "{:?}", bubbled.borrow());
}

#[gpui::test]
fn keys_the_field_does_not_use_bubble_to_the_parent(cx: &mut TestAppContext) {
    let (input, bubbled, cx) = field(cx, |cx| TextInput::field("ab", cx));
    // Enter and Escape belong to the parent; so do Right and Down at the end, where they can't move.
    cx.simulate_keystrokes("enter escape right down up");
    assert_eq!(*bubbled.borrow(), ["enter", "escape", "right", "down"]);
    assert_eq!(caret(&input, cx), 0, "Up in a field goes to the start");
}

#[gpui::test]
fn tab_and_enter_insert_in_a_text_area(cx: &mut TestAppContext) {
    let (input, bubbled, cx) = open(cx, |cx| TextInput::area("a", cx), 200.0, 80.0);
    cx.simulate_keystrokes("tab enter");
    assert_eq!(text(&input, cx), "a\t\n");
    assert!(bubbled.borrow().is_empty());
}

#[gpui::test]
fn telex_composition_from_the_input_method(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("", cx));
    // What macOS's Vietnamese Telex sends for "tieengs": the word marked as it changes, then committed.
    for marked in ["t", "ti", "tie", "tiê", "tiên", "tiêng", "tiếng"] {
        input.update_in(cx, |i, window, cx| {
            i.replace_and_mark_text_in_range(None, marked, None, window, cx)
        });
        let n = marked.encode_utf16().count();
        let range = input.update_in(cx, |i, window, cx| i.marked_text_range(window, cx));
        assert_eq!(range, Some(0..n), "while composing {marked}");
    }
    input.update_in(cx, |i, window, cx| {
        i.replace_text_in_range(None, "tiếng ", window, cx)
    });
    assert_eq!(text(&input, cx), "tiếng ");
    assert_eq!(
        input.update_in(cx, |i, w, cx| i.marked_text_range(w, cx)),
        None
    );
    // The input method asks where the text is to place its candidate window.
    let first = bounds_of(&input, 0..1, cx);
    let word = bounds_of(&input, 0..5, cx);
    assert!(word.size.width > first.size.width);
    // One undo step for the composed word.
    cx.simulate_keystrokes(&format!("{PRIMARY}-z"));
    assert_eq!(text(&input, cx), "");
}

#[gpui::test]
fn marked_text_with_a_selection_inside_and_cancel(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("x", cx));
    // A selection in UTF-16 units of the marked text: after "Việ" (3 units), before "t".
    input.update_in(cx, |i, window, cx| {
        i.replace_and_mark_text_in_range(None, "Việt", Some(3..3), window, cx)
    });
    assert_eq!(text(&input, cx), "xViệt");
    assert_eq!(caret(&input, cx), "xViệ".len());
    let sel = input.update_in(cx, |i, window, cx| i.selected_text_range(false, window, cx));
    assert_eq!(sel.unwrap().range, 4..4);
    input.update_in(cx, |i, window, cx| i.unmark_text(window, cx));
    assert_eq!(text(&input, cx), "xViệt");
}

#[gpui::test]
fn a_password_field_puts_bullets_on_the_clipboard(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("secret", cx).password());
    cx.simulate_keystrokes(&format!("{PRIMARY}-a {PRIMARY}-c"));
    assert_eq!(clipboard(cx).as_deref(), Some("••••••"));
    cx.simulate_keystrokes(&format!("{PRIMARY}-x"));
    assert_eq!(clipboard(cx).as_deref(), Some("••••••"));
    assert_eq!(text(&input, cx), "");
}

#[gpui::test]
fn copy_and_paste_use_the_system_clipboard(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("users", cx));
    cx.simulate_keystrokes(&format!(
        "shift-left shift-left {PRIMARY}-c right {PRIMARY}-v"
    ));
    assert_eq!(clipboard(cx).as_deref(), Some("rs"));
    assert_eq!(text(&input, cx), "usersrs");
}

#[gpui::test]
fn a_long_field_scrolls_to_keep_the_caret_in_view(cx: &mut TestAppContext) {
    let long = "select * from users where id = 1";
    let (input, _, cx) = field(cx, move |cx| TextInput::field(long, cx));
    let scrolled = input.read_with(cx, |i, _| i.scroll().x);
    assert!(scrolled > px(0.0), "caret at the end scrolls the text left");
    // The caret's drawn x stays inside the field.
    let host = cx.update(|window, _| window.bounds());
    let end = bounds_of(&input, long.len()..long.len(), cx);
    assert!(end.origin.x < host.origin.x + px(120.0));
    cx.simulate_keystrokes("home");
    assert_eq!(input.read_with(cx, |i, _| i.scroll().x), px(0.0));
}

#[gpui::test]
fn up_and_down_in_a_text_area_keep_the_caret_x_in_pixels(cx: &mut TestAppContext) {
    let (input, _, cx) = open(
        cx,
        |cx| TextInput::area("abcdef\nab\nabcdef", cx),
        200.0,
        80.0,
    );
    input.update(cx, |i, cx| i.update_editor(cx, |e| e.place_caret(5, false)));
    cx.run_until_parked();
    cx.simulate_keystrokes("down");
    assert_eq!(caret(&input, cx), 9, "end of the short line");
    cx.simulate_keystrokes("down");
    assert_eq!(
        caret(&input, cx),
        15,
        "back to column 5: the x is remembered"
    );
    cx.simulate_keystrokes("up up");
    assert_eq!(caret(&input, cx), 5);
    cx.simulate_keystrokes("up");
    assert_eq!(
        caret(&input, cx),
        0,
        "Up on the first row goes to the start"
    );
}

#[gpui::test]
fn up_and_down_move_between_wrapped_rows(cx: &mut TestAppContext) {
    // One hard line wrapped over several rows: moving by columns would jump to the text's ends.
    let words = "aaaa bbbb cccc dddd eeee ffff gggg hhhh";
    let (input, bubbled, cx) = open(cx, move |cx| TextInput::area(words, cx), 60.0, 120.0);
    input.update(cx, |i, cx| i.update_editor(cx, |e| e.place_caret(1, false)));
    cx.run_until_parked();
    let rows = |input: &Entity<TextInput>, cx: &mut VisualTestContext| {
        let pos = caret(input, cx);
        bounds_of(input, pos..pos, cx).origin.y
    };
    let y0 = rows(&input, cx);
    cx.simulate_keystrokes("down");
    let (pos, y1) = (caret(&input, cx), rows(&input, cx));
    assert!(
        pos > 1 && pos < words.len(),
        "moved within the line, to {pos}"
    );
    assert!(y1 > y0);
    cx.simulate_keystrokes("up");
    assert_eq!(caret(&input, cx), 1);
    assert!(bubbled.borrow().is_empty());
}

#[gpui::test]
fn a_click_places_the_caret_and_a_double_click_selects_a_word(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("select users", cx));
    let at = bounds_of(&input, 2..2, cx).origin + point(px(1.0), px(4.0));
    cx.simulate_click(at, Modifiers::none());
    assert_eq!(caret(&input, cx), 2);
    let word = bounds_of(&input, 8..8, cx).origin + point(px(1.0), px(4.0));
    cx.simulate_event(gpui::MouseDownEvent {
        button: gpui::MouseButton::Left,
        position: word,
        modifiers: Modifiers::none(),
        click_count: 2,
        first_mouse: false,
    });
    assert_eq!(selection(&input, cx), 7..12);
}

#[gpui::test]
fn the_caret_blinks_while_focused(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("a", cx));
    assert!(input.read_with(cx, |i, _| i.caret_visible()));
    cx.executor().advance_clock(Duration::from_millis(510));
    assert!(!input.read_with(cx, |i, _| i.caret_visible()));
    cx.executor().advance_clock(Duration::from_millis(500));
    assert!(input.read_with(cx, |i, _| i.caret_visible()));
    // Typing shows it at once.
    cx.executor().advance_clock(Duration::from_millis(500));
    cx.simulate_input("b");
    assert!(input.read_with(cx, |i, _| i.caret_visible()));
}

#[gpui::test]
fn a_disabled_field_takes_no_input(cx: &mut TestAppContext) {
    let (input, _, cx) = field(cx, |cx| TextInput::field("a", cx).disabled());
    cx.simulate_input("b");
    cx.update(|window, _| window.blur());
    let at = bounds_of(&input, 0..0, cx).origin + point(px(1.0), px(4.0));
    cx.simulate_click(at, Modifiers::none());
    assert_eq!(text(&input, cx), "a");
    assert!(cx.update(|window, cx| !input.focus_handle(cx).is_focused(window)));
}
