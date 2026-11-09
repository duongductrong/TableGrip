//! Editing behaviour the conformance scenarios can't hold portably: word movement differs by platform (the scenario
//! runners have no "word" modifier), the shared harness takes Tab before a TextArea sees it, and password display is
//! drawn, not reported. Expectations for macOS were observed on the Kotlin app.

use tg_text::{Editor, KeyResult, MemoryClipboard, Options, Platform};
use tg_ui_logic::{Key, Modifiers};

const NONE: Modifiers = Modifiers {
    primary: false,
    shift: false,
    alt: false,
};
const PRIMARY: Modifiers = Modifiers {
    primary: true,
    ..NONE
};
const ALT: Modifiers = Modifiers { alt: true, ..NONE };
const SHIFT_ALT: Modifiers = Modifiers {
    shift: true,
    alt: true,
    ..NONE
};

fn editor(text: &str, platform: Platform) -> Editor {
    Editor::new(
        text,
        Options {
            platform,
            ..Options::default()
        },
    )
}

fn press(editor: &mut Editor, key: Key, mods: Modifiers) -> KeyResult {
    editor.handle_key(key, mods, &mut MemoryClipboard::default())
}

fn caret(editor: &Editor) -> usize {
    editor.selection().head
}

#[test]
fn option_arrows_move_by_word_on_mac() {
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Left, ALT);
    assert_eq!(caret(&e), 6);
    press(&mut e, Key::Left, ALT);
    assert_eq!(caret(&e), 0);
    // At the start it stays in the field rather than going to the parent.
    assert_eq!(press(&mut e, Key::Left, ALT), KeyResult::Handled);
    press(&mut e, Key::Right, ALT);
    assert_eq!(caret(&e), 5);
    press(&mut e, Key::Right, ALT);
    assert_eq!(caret(&e), 11);
}

#[test]
fn ctrl_arrows_move_by_word_elsewhere() {
    let mut e = editor("hello world", Platform::Other);
    press(&mut e, Key::Left, PRIMARY);
    assert_eq!(caret(&e), 6);
    press(&mut e, Key::Right, PRIMARY);
    assert_eq!(caret(&e), 11);
    // Alt is no word modifier there.
    press(&mut e, Key::Left, ALT);
    assert_eq!(caret(&e), 10);
}

#[test]
fn cmd_arrows_go_to_the_line_ends_on_mac() {
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Left, PRIMARY);
    assert_eq!(caret(&e), 0);
    press(
        &mut e,
        Key::Right,
        Modifiers {
            shift: true,
            ..PRIMARY
        },
    );
    assert_eq!(e.selected_text(), "hello world");
}

#[test]
fn punctuation_stops_a_word_move() {
    let mut e = editor("a.b foo-bar", Platform::Mac);
    press(&mut e, Key::Home, NONE);
    press(&mut e, Key::Right, ALT);
    press(&mut e, Key::Right, ALT);
    assert_eq!(caret(&e), 2);
}

#[test]
fn shift_word_move_selects_the_word() {
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Left, SHIFT_ALT);
    assert_eq!(e.selected_text(), "world");
}

#[test]
fn word_deletion() {
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Backspace, ALT);
    assert_eq!(e.text(), "hello ");
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Home, NONE);
    press(&mut e, Key::Delete, ALT);
    assert_eq!(e.text(), " world");
    // Cmd+Backspace deletes to the start of the line on macOS.
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Left, NONE);
    press(&mut e, Key::Backspace, PRIMARY);
    assert_eq!(e.text(), "d");
    // Ctrl+Backspace deletes a word elsewhere.
    let mut e = editor("hello world", Platform::Other);
    press(&mut e, Key::Backspace, PRIMARY);
    assert_eq!(e.text(), "hello ");
}

#[test]
fn backspace_and_word_backspace_undo_together() {
    let mut e = editor("hello world", Platform::Mac);
    press(&mut e, Key::Backspace, NONE);
    press(&mut e, Key::Backspace, ALT);
    assert_eq!(e.text(), "hello ");
    e.undo();
    assert_eq!(e.text(), "hello world");
    assert_eq!(caret(&e), 11);
}

#[test]
fn a_word_move_crosses_line_breaks() {
    let mut e = Editor::new(
        "one\ntwo",
        Options {
            multi_line: true,
            platform: Platform::Mac,
            ..Options::default()
        },
    );
    press(&mut e, Key::Home, NONE);
    press(&mut e, Key::Left, ALT);
    assert_eq!(caret(&e), 0);
}

#[test]
fn mac_vertical_shortcuts_in_a_text_area() {
    let options = Options {
        multi_line: true,
        platform: Platform::Mac,
        ..Options::default()
    };
    let mut e = Editor::new("ab\ncd", options);
    press(&mut e, Key::Up, ALT);
    assert_eq!(caret(&e), 3, "Option+Up goes to the start of the line");
    press(&mut e, Key::Up, PRIMARY);
    assert_eq!(caret(&e), 0, "Cmd+Up goes to the start of the text");
    press(&mut e, Key::Down, PRIMARY);
    assert_eq!(caret(&e), 5);
}

#[test]
fn tab_inserts_in_a_text_area_but_not_in_a_field() {
    let mut area = Editor::new(
        "ab",
        Options {
            multi_line: true,
            ..Options::default()
        },
    );
    assert_eq!(press(&mut area, Key::Tab, NONE), KeyResult::Handled);
    assert_eq!(area.text(), "ab\t");
    let shift = Modifiers {
        shift: true,
        ..NONE
    };
    assert_eq!(press(&mut area, Key::Tab, shift), KeyResult::Bubble);
    assert_eq!(press(&mut area, Key::Tab, PRIMARY), KeyResult::Bubble);
    let mut field = editor("ab", Platform::current());
    assert_eq!(press(&mut field, Key::Tab, NONE), KeyResult::Bubble);
    assert_eq!(field.text(), "ab");
}

#[test]
fn a_read_only_text_area_passes_tab_and_enter_on() {
    let mut area = Editor::new(
        "ab",
        Options {
            multi_line: true,
            read_only: true,
            ..Options::default()
        },
    );
    assert_eq!(press(&mut area, Key::Tab, NONE), KeyResult::Bubble);
    assert_eq!(press(&mut area, Key::Enter, NONE), KeyResult::Bubble);
    assert_eq!(area.text(), "ab");
}

#[test]
fn a_password_field_shows_bullets_and_moves_by_the_whole_text() {
    let mut e = Editor::new(
        "ab cđ👍🏽",
        Options {
            password: true,
            platform: Platform::Mac,
            ..Options::default()
        },
    );
    assert_eq!(e.display_text(), "••••••");
    assert_eq!(e.display_offset("ab".len()), 2 * '•'.len_utf8());
    press(&mut e, Key::Left, ALT);
    assert_eq!(caret(&e), 0);
    e.select_word_at(1);
    assert_eq!(e.selected_text(), "ab cđ👍🏽");
}

#[test]
fn a_disabled_field_bubbles_everything() {
    let mut e = Editor::new(
        "ab",
        Options {
            enabled: false,
            ..Options::default()
        },
    );
    assert_eq!(press(&mut e, Key::Left, NONE), KeyResult::Bubble);
    assert_eq!(press(&mut e, Key::Letter('A'), PRIMARY), KeyResult::Bubble);
    assert!(!e.insert_text("x"));
    assert_eq!(e.text(), "ab");
}

#[test]
fn set_text_is_one_undo_step() {
    let mut e = editor("old", Platform::current());
    e.set_text("new value");
    assert_eq!(e.text(), "new value");
    assert_eq!(caret(&e), 9);
    e.undo();
    assert_eq!(e.text(), "old");
}

#[test]
fn break_undo_group_splits_typing() {
    let mut e = editor("", Platform::current());
    e.insert_text("ab");
    e.break_undo_group();
    e.insert_text("cd");
    e.undo();
    assert_eq!(e.text(), "ab");
}

#[test]
fn click_and_shift_click() {
    let mut e = editor("hello world", Platform::current());
    e.place_caret(2, false);
    e.place_caret(7, true);
    assert_eq!(e.selected_text(), "llo w");
    assert!(!e.selection().reversed());
    // A position inside a character snaps back to its start.
    let mut e = editor("tiếng", Platform::current());
    e.place_caret(3, false);
    assert_eq!(caret(&e), 2);
}
