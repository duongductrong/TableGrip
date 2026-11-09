//! Wires `tg_ui_logic::keymap` into GPUI. The logic crate decides what a key press means (exact modifiers, `primary`
//! being Cmd on macOS and Ctrl elsewhere, one chord for several actions); this module only translates:
//!
//! * a GPUI key-down into a [`KeyEvent`] ([`key_event`]);
//! * each binding of a [`Keymap`] into a GPUI `KeyBinding` that runs [`RunCommand`] with the action's id
//!   ([`bind_keymap`]), so a shortcut reaches whichever element handles `RunCommand` (`.on_action(...)`);
//! * a window-level preview handler ([`preview_commands`]) for the commands that must win over a focused text field,
//!   as the Kotlin window's `onPreviewKeyEvent` does; and [`set_recording`] for Settings → Keymap.
//!
//! GPUI already runs handlers in the order `tg_ui_logic::key_dispatch` models: capture (preview) handlers parents
//! first, then bubble handlers children first, stopping where one takes the key. `tests/shell.rs` checks that.

use gpui::{Action, App, InteractiveElement, KeyBinding, Keystroke, SharedString, Window};
use tg_ui_logic::keymap::{Code, KeyEvent, Keymap, Platform, Shortcut};
use tg_ui_logic::{Key, Modifiers};

/// Runs the keymap action `id` (such as `NewConsole`). One GPUI action for every id, since the ids and their shortcuts
/// are data (the user rebinds them in Settings → Keymap), not types.
#[derive(Clone, PartialEq, Debug, Action)]
#[action(namespace = tablegrip, no_json)]
pub struct RunCommand {
    pub id: SharedString,
}

/// The GPUI key name of `code`, None for a key GPUI names differently on each platform (the keypad, a lone modifier).
pub fn key_name(code: Code) -> Option<String> {
    Some(match code {
        Code::Key(key) => match key {
            Key::Space => "space".into(),
            Key::Enter => "enter".into(),
            Key::Tab => "tab".into(),
            Key::Escape => "escape".into(),
            Key::Backspace => "backspace".into(),
            Key::Delete => "delete".into(),
            Key::Home => "home".into(),
            Key::End => "end".into(),
            Key::Left => "left".into(),
            Key::Right => "right".into(),
            Key::Up => "up".into(),
            Key::Down => "down".into(),
            Key::PageUp => "pageup".into(),
            Key::PageDown => "pagedown".into(),
            Key::F2 => "f2".into(),
            Key::Letter(c) => c.to_ascii_lowercase().to_string(),
        },
        Code::Digit(n) => n.to_string(),
        Code::Equals => "=".into(),
        Code::Minus => "-".into(),
        Code::Slash => "/".into(),
        Code::Comma => ",".into(),
        Code::Period => ".".into(),
        Code::LeftBracket => "[".into(),
        Code::RightBracket => "]".into(),
        Code::NumPadEnter | Code::NumPadAdd | Code::NumPadSubtract | Code::Modifier => {
            return None;
        }
    })
}

/// The key a GPUI key name stands for; None for keys the shortcut table never binds.
pub fn code_of(name: &str) -> Option<Code> {
    Some(match name {
        "space" => Code::Key(Key::Space),
        "enter" => Code::Key(Key::Enter),
        "tab" => Code::Key(Key::Tab),
        "escape" => Code::Key(Key::Escape),
        "backspace" => Code::Key(Key::Backspace),
        "delete" => Code::Key(Key::Delete),
        "home" => Code::Key(Key::Home),
        "end" => Code::Key(Key::End),
        "left" => Code::Key(Key::Left),
        "right" => Code::Key(Key::Right),
        "up" => Code::Key(Key::Up),
        "down" => Code::Key(Key::Down),
        "pageup" => Code::Key(Key::PageUp),
        "pagedown" => Code::Key(Key::PageDown),
        "f2" => Code::Key(Key::F2),
        "=" => Code::Equals,
        "-" => Code::Minus,
        "/" => Code::Slash,
        "," => Code::Comma,
        "." => Code::Period,
        "[" => Code::LeftBracket,
        "]" => Code::RightBracket,
        n if n.len() == 1 => {
            let c = n.chars().next()?;
            if c.is_ascii_digit() {
                Code::Digit(c as u8 - b'0')
            } else if c.is_ascii_alphabetic() {
                Code::Key(Key::Letter(c.to_ascii_uppercase()))
            } else {
                return None;
            }
        }
        _ => return None,
    })
}

/// A GPUI key-down as the keymap sees it: the physical key and Control, Cmd (GPUI's `platform`), Shift and Alt.
pub fn key_event(keystroke: &Keystroke, down: bool) -> KeyEvent {
    let m = &keystroke.modifiers;
    KeyEvent {
        code: code_of(&keystroke.key).unwrap_or(Code::Modifier),
        down,
        ctrl: m.control,
        meta: m.platform,
        shift: m.shift,
        alt: m.alt,
    }
}

/// The GPUI keystroke string (`cmd-shift-d`) for `shortcut`, None when its key has no portable name.
pub fn keystroke_string(shortcut: &Shortcut, platform: Platform) -> Option<String> {
    let key = key_name(shortcut.code)?;
    let mut out = String::new();
    let mac = platform == Platform::Mac;
    // `ctrl` is the physical Control key on macOS; elsewhere it is the same as `primary`.
    if shortcut.ctrl || (shortcut.primary && !mac) {
        out.push_str("ctrl-");
    }
    if shortcut.alt {
        out.push_str("alt-");
    }
    if shortcut.shift {
        out.push_str("shift-");
    }
    if shortcut.primary && mac {
        out.push_str("cmd-");
    }
    out.push_str(&key);
    Some(out)
}

/// Replaces the app's key bindings with `keymap`'s: each action's shortcut runs [`RunCommand`] with its id. A chord
/// shared by several actions (primary+F is Find and Focus filter) is bound once, to the first in declaration order;
/// the handler of that action decides which of them applies, as the window does in the Kotlin app.
pub fn bind_keymap(cx: &mut App, keymap: &Keymap) {
    let mut seen = Vec::new();
    let mut bindings = Vec::new();
    for (id, _) in tg_ui_logic::keymap::defaults(keymap.platform()) {
        let Some(shortcut) = keymap.binding(id) else {
            continue;
        };
        let Some(chord) = keystroke_string(&shortcut, keymap.platform()) else {
            continue;
        };
        if seen.contains(&chord) {
            continue;
        }
        bindings.push(KeyBinding::new(&chord, RunCommand { id: id.into() }, None));
        seen.push(chord);
    }
    cx.clear_key_bindings();
    cx.bind_keys(bindings);
}

/// Makes `el` see every [`RunCommand`] before the focused element's own handlers do (GPUI's capture phase for
/// actions), as the Kotlin window's preview handler does for tab switching and zoom, so a focused text field cannot
/// swallow them. Return true from `on_command` to take the command; otherwise it goes on to the focused element.
///
/// A key bound to an action never reaches `on_key_down` handlers, so this is the window's preview phase for every
/// key in the keymap.
pub fn preview_commands<E: InteractiveElement>(
    el: E,
    on_command: impl Fn(&str, &mut Window, &mut App) -> bool + 'static,
) -> E {
    el.capture_action(move |action: &RunCommand, window, cx| {
        if on_command(&action.id, window, cx) {
            cx.stop_propagation();
        }
    })
}

/// Settings → Keymap starts or stops recording a key: while it records, the window's shortcuts stand aside (no key
/// is bound, so the recorder sees every key), and they come back when it stops.
pub fn set_recording(cx: &mut App, keymap: &mut Keymap, recording: bool) {
    keymap.recording = recording;
    if recording {
        cx.clear_key_bindings();
    } else {
        bind_keymap(cx, keymap);
    }
}

/// The modifiers of a scenario-style press, for building a [`KeyEvent`] in tests.
pub fn press(key: Key, mods: Modifiers, platform: Platform) -> KeyEvent {
    KeyEvent::from_input(key, mods, platform)
}
