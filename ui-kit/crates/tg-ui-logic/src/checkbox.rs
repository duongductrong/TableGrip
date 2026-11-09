use crate::input::{Input, Key};

/// A checkbox with its label, toggled as one control.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Checkbox {
    pub checked: bool,
    pub enabled: bool,
}

/// What handling an input asks of the host.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct CheckboxResponse {
    /// The new value, when the input toggled it.
    pub toggled: Option<bool>,
    /// The control wants focus (a click focuses it).
    pub focus: bool,
}

impl Checkbox {
    pub fn new(checked: bool) -> Self {
        Checkbox {
            checked,
            enabled: true,
        }
    }

    /// Handles input while the box has focus (keys) or under the pointer (clicks). A disabled box ignores everything.
    pub fn handle(&mut self, input: &Input) -> CheckboxResponse {
        if !self.enabled {
            return CheckboxResponse::default();
        }
        let toggles = match input {
            Input::Click => true,
            Input::Key(Key::Space | Key::Enter, mods) => mods.none(),
            _ => false,
        };
        if toggles {
            self.checked = !self.checked;
        }
        CheckboxResponse {
            toggled: toggles.then_some(self.checked),
            focus: matches!(input, Input::Click),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::Modifiers;

    #[test]
    fn modified_space_does_not_toggle() {
        let mut b = Checkbox::new(false);
        b.handle(&Input::Key(
            Key::Space,
            Modifiers {
                primary: true,
                ..Default::default()
            },
        ));
        assert!(!b.checked);
    }

    #[test]
    fn click_reports_the_new_value_and_asks_for_focus() {
        let mut b = Checkbox::new(false);
        assert_eq!(
            b.handle(&Input::Click),
            CheckboxResponse {
                toggled: Some(true),
                focus: true
            }
        );
    }
}
