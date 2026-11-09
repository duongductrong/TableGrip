use crate::input::{Key, Modifiers};

/// The order Tab moves focus in: the focusable controls of a window, as indexes into it.
///
/// Verified against the Kotlin app: Tab moves to the next control and Shift+Tab to the previous one, both wrapping
/// round at the ends (Tab on the last control focuses the first, Shift+Tab on the first focuses the last), and a
/// disabled control is never a stop. A window with a single control keeps focus on it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FocusOrder {
    enabled: Vec<bool>,
    focused: Option<usize>,
}

impl FocusOrder {
    pub fn new(len: usize) -> Self {
        FocusOrder {
            enabled: vec![true; len],
            focused: None,
        }
    }

    pub fn focused(&self) -> Option<usize> {
        self.focused
    }

    /// Focuses a control directly (a click, a focus request). Disabled and unknown controls can't take focus.
    pub fn focus(&mut self, index: usize) {
        if self.enabled.get(index) == Some(&true) {
            self.focused = Some(index);
        }
    }

    /// Enables or disables a control. A disabled control is skipped by Tab and drops focus if it had it.
    pub fn set_enabled(&mut self, index: usize, enabled: bool) {
        if let Some(slot) = self.enabled.get_mut(index) {
            *slot = enabled;
            if !enabled && self.focused == Some(index) {
                self.focused = None;
            }
        }
    }

    /// Moves focus for Tab or Shift+Tab; true when the key was a focus key, so the control doesn't get it.
    pub fn handle_key(&mut self, key: Key, mods: Modifiers) -> bool {
        if key != Key::Tab || mods.primary || mods.alt {
            return false;
        }
        if let Some(i) = self.focused {
            let len = self.enabled.len();
            let step = if mods.shift { len - 1 } else { 1 };
            self.focused = (1..=len)
                .map(|n| (i + n * step) % len)
                .find(|&j| self.enabled[j])
                .or(Some(i));
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TAB: Modifiers = Modifiers {
        primary: false,
        shift: false,
        alt: false,
    };
    const SHIFT_TAB: Modifiers = Modifiers { shift: true, ..TAB };

    fn order(len: usize, start: usize) -> FocusOrder {
        let mut f = FocusOrder::new(len);
        f.focus(start);
        f
    }

    #[test]
    fn tab_wraps_from_the_last_to_the_first() {
        let mut f = order(3, 2);
        assert!(f.handle_key(Key::Tab, TAB));
        assert_eq!(f.focused(), Some(0));
    }

    #[test]
    fn shift_tab_wraps_from_the_first_to_the_last() {
        let mut f = order(3, 0);
        f.handle_key(Key::Tab, SHIFT_TAB);
        assert_eq!(f.focused(), Some(2));
    }

    #[test]
    fn disabled_controls_are_skipped_both_ways() {
        let mut f = order(4, 0);
        f.set_enabled(1, false);
        f.set_enabled(2, false);
        f.handle_key(Key::Tab, TAB);
        assert_eq!(f.focused(), Some(3));
        f.handle_key(Key::Tab, SHIFT_TAB);
        assert_eq!(f.focused(), Some(0));
    }

    #[test]
    fn a_lone_control_keeps_focus() {
        let mut f = order(1, 0);
        f.handle_key(Key::Tab, TAB);
        f.handle_key(Key::Tab, SHIFT_TAB);
        assert_eq!(f.focused(), Some(0));
    }

    #[test]
    fn disabled_controls_cannot_be_focused() {
        let mut f = FocusOrder::new(2);
        f.set_enabled(0, false);
        f.focus(0);
        assert_eq!(f.focused(), None);
    }

    #[test]
    fn disabling_the_focused_control_drops_focus() {
        let mut f = order(2, 1);
        f.set_enabled(1, false);
        assert_eq!(f.focused(), None);
    }

    #[test]
    fn other_keys_and_chords_are_not_focus_keys() {
        let mut f = order(2, 0);
        assert!(!f.handle_key(Key::Space, TAB));
        assert!(!f.handle_key(
            Key::Tab,
            Modifiers {
                primary: true,
                ..TAB
            }
        ));
    }
}
