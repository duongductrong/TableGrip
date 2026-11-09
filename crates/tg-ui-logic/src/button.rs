use crate::input::{Input, Key};

/// How a button looks; both behave the same.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ButtonVariant {
    /// The filled, accent-coloured button for the main action (Jewel's DefaultButton in the Kotlin app).
    #[default]
    Default,
    /// The bordered button for secondary actions.
    Outlined,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Button {
    pub variant: ButtonVariant,
    pub enabled: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ButtonResponse {
    /// The button was activated: run its action.
    pub activated: bool,
    pub focus: bool,
}

impl Button {
    pub fn new(variant: ButtonVariant) -> Self {
        Button {
            variant,
            enabled: true,
        }
    }

    /// A click, or Space or Enter while focused, activates an enabled button.
    pub fn handle(&mut self, input: &Input) -> ButtonResponse {
        if !self.enabled {
            return ButtonResponse::default();
        }
        let activated = match input {
            Input::Click => true,
            Input::Key(Key::Space | Key::Enter, mods) => mods.none(),
            _ => false,
        };
        ButtonResponse {
            activated,
            focus: matches!(input, Input::Click),
        }
    }
}
