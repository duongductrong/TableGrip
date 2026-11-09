use crate::button::{Button, ButtonResponse, ButtonVariant};
use crate::input::Input;
use crate::tooltip::Tooltip;

/// The app's dominant idiom: `Tooltip { IconButton }`. It behaves as a button; the tooltip hides on a click.
#[derive(Clone, Debug)]
pub struct IconButton {
    pub button: Button,
    pub tooltip: Tooltip,
}

impl IconButton {
    pub fn new() -> Self {
        IconButton {
            button: Button::new(ButtonVariant::Outlined),
            tooltip: Tooltip::new(),
        }
    }

    pub fn handle(&mut self, input: &Input) -> ButtonResponse {
        if matches!(input, Input::Click) {
            self.tooltip.press();
        }
        self.button.handle(input)
    }
}

impl Default for IconButton {
    fn default() -> Self {
        Self::new()
    }
}
