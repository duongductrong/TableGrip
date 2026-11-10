use crate::keymap::{Code, KeyEvent};

/// A bare Escape key press: no Ctrl, Cmd, Alt or Shift (with modifiers it is left for shortcuts).
pub fn is_close_key(event: &KeyEvent) -> bool {
    event.down
        && event.code == Code::Key(crate::Key::Escape)
        && !event.ctrl
        && !event.meta
        && !event.alt
        && !event.shift
}

/// Whether the dialog closes on this key. The dialog's own handler runs after the content inside it and the caller's
/// handler, so `taken` is true when either already used the key; then (or with `close_on_escape` off) it stays open.
pub fn closes(event: &KeyEvent, taken: bool, close_on_escape: bool) -> bool {
    !taken && close_on_escape && is_close_key(event)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::keymap::Platform;
    use crate::{Key, Modifiers};

    fn event(key: Key, mods: Modifiers) -> KeyEvent {
        KeyEvent::from_input(key, mods, Platform::Mac)
    }

    #[test]
    fn only_a_bare_escape_closes() {
        let none = Modifiers::default();
        assert!(closes(&event(Key::Escape, none), false, true));
        for mods in [
            Modifiers {
                shift: true,
                ..none
            },
            Modifiers { alt: true, ..none },
            Modifiers {
                primary: true,
                ..none
            },
        ] {
            assert!(!closes(&event(Key::Escape, mods), false, true));
        }
        assert!(!closes(&event(Key::Enter, none), false, true));
    }

    #[test]
    fn a_key_already_used_inside_does_not_close() {
        let esc = event(Key::Escape, Modifiers::default());
        assert!(!closes(&esc, true, true));
        assert!(!closes(&esc, false, false));
    }

    #[test]
    fn a_physical_control_blocks_it_on_the_mac() {
        let mut esc = event(Key::Escape, Modifiers::default());
        esc.ctrl = true;
        assert!(!is_close_key(&esc));
    }

    #[test]
    fn a_key_release_is_not_a_close() {
        let mut esc = event(Key::Escape, Modifiers::default());
        esc.down = false;
        assert!(!is_close_key(&esc));
    }
}
