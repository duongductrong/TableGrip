//! The keymap the settings ask for (`Shortcuts.configure`): a preset, then the user's own bindings, which
//! `settings.json` stores with AWT key codes.

use tg_core::settings::{KeyBinding, Settings};
use tg_ui_logic::Key;
use tg_ui_logic::keymap::{Code, Keymap, Platform, Preset, Shortcut};

/// The key an AWT key code stands for; None for keys the shortcut table never binds.
pub fn code_of_awt(key: i32) -> Option<Code> {
    Some(match key {
        10 => Code::Key(Key::Enter),
        8 => Code::Key(Key::Backspace),
        127 => Code::Key(Key::Delete),
        9 => Code::Key(Key::Tab),
        27 => Code::Key(Key::Escape),
        32 => Code::Key(Key::Space),
        33 => Code::Key(Key::PageUp),
        34 => Code::Key(Key::PageDown),
        35 => Code::Key(Key::End),
        36 => Code::Key(Key::Home),
        37 => Code::Key(Key::Left),
        38 => Code::Key(Key::Up),
        39 => Code::Key(Key::Right),
        40 => Code::Key(Key::Down),
        113 => Code::Key(Key::F2),
        44 => Code::Comma,
        45 => Code::Minus,
        46 => Code::Period,
        47 => Code::Slash,
        61 => Code::Equals,
        91 => Code::LeftBracket,
        93 => Code::RightBracket,
        107 => Code::NumPadAdd,
        109 => Code::NumPadSubtract,
        48..=57 => Code::Digit((key - 48) as u8),
        65..=90 => Code::Key(Key::Letter(char::from(key as u8))),
        _ => return None,
    })
}

pub fn shortcut_of(binding: &KeyBinding) -> Option<Shortcut> {
    Some(Shortcut {
        code: code_of_awt(binding.key)?,
        primary: binding.primary,
        shift: binding.shift,
        alt: binding.alt,
        ctrl: binding.ctrl,
    })
}

/// The keymap for `settings` on `platform`.
pub fn keymap(settings: &Settings, platform: Platform) -> Keymap {
    let mut keymap = Keymap::new(platform, Preset::of(&settings.keymap));
    for (id, binding) in &settings.key_bindings {
        if let Some(shortcut) = shortcut_of(binding) {
            keymap.set(id, shortcut);
        }
    }
    keymap
}
