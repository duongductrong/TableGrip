use crate::input::{Input, Key};

/// The three states of a group header.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ToggleState {
    Off,
    On,
    Indeterminate,
}

impl ToggleState {
    /// The state a header shows for `selected` of `total` children. The app derives it this way everywhere it uses
    /// a tri-state checkbox (SchemaCompareView, ImportConnections).
    pub fn of_children(selected: usize, total: usize) -> Self {
        match selected {
            0 => ToggleState::Off,
            n if n == total => ToggleState::On,
            _ => ToggleState::Indeterminate,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            ToggleState::Off => "off",
            ToggleState::On => "on",
            ToggleState::Indeterminate => "indeterminate",
        }
    }
}

/// A group header's checkbox. The control itself only reports clicks; what a click means is the app's decision, which
/// is: select every child unless all are already selected, then deselect them all.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TriStateCheckbox {
    pub total: usize,
    pub selected: usize,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct TriStateResponse {
    /// The control was activated: the app toggles its children.
    pub activated: bool,
    pub focus: bool,
}

impl TriStateCheckbox {
    pub fn new(selected: usize, total: usize) -> Self {
        TriStateCheckbox { total, selected }
    }

    pub fn state(&self) -> ToggleState {
        ToggleState::of_children(self.selected, self.total)
    }

    /// Handles a click, or Space or Enter while focused, applying the app's rule to the children.
    pub fn handle(&mut self, input: &Input) -> TriStateResponse {
        let activated = match input {
            Input::Click => true,
            Input::Key(Key::Space | Key::Enter, mods) => mods.none(),
            _ => false,
        };
        if activated {
            self.selected = if self.state() == ToggleState::On {
                0
            } else {
                self.total
            };
        }
        TriStateResponse {
            activated,
            focus: matches!(input, Input::Click),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn states_follow_the_children() {
        assert_eq!(ToggleState::of_children(0, 3), ToggleState::Off);
        assert_eq!(ToggleState::of_children(3, 3), ToggleState::On);
        assert_eq!(ToggleState::of_children(1, 3), ToggleState::Indeterminate);
    }

    #[test]
    fn indeterminate_selects_everything() {
        let mut c = TriStateCheckbox::new(1, 3);
        c.handle(&Input::Click);
        assert_eq!(c.state(), ToggleState::On);
    }
}
