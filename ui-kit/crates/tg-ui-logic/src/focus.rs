use crate::input::{Key, Modifiers};

/// The order Tab moves focus in: the focusable controls of a window, as indexes into it.
///
/// Tab moving to the next control is verified against the Kotlin app. Shift+Tab moving back, what happens at either
/// end (focus stays put here) and skipping disabled controls are not yet: Phase 2 adds scenarios for them.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FocusOrder {
    len: usize,
    focused: Option<usize>,
}

impl FocusOrder {
    pub fn new(len: usize) -> Self {
        FocusOrder { len, focused: None }
    }

    pub fn focused(&self) -> Option<usize> {
        self.focused
    }

    pub fn focus(&mut self, index: usize) {
        if index < self.len {
            self.focused = Some(index);
        }
    }

    /// Moves focus for Tab or Shift+Tab; true when the key was a focus key, so the control doesn't get it.
    pub fn handle_key(&mut self, key: Key, mods: Modifiers) -> bool {
        if key != Key::Tab || mods.primary || mods.alt {
            return false;
        }
        if let Some(i) = self.focused {
            self.focused = Some(if mods.shift {
                i.saturating_sub(1)
            } else {
                (i + 1).min(self.len - 1)
            });
        }
        true
    }
}
