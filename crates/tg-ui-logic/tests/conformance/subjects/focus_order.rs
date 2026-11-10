use serde_json::{Map, Value};
use tg_ui_logic::button::ButtonVariant;
use tg_ui_logic::{Button, Input};

use super::Subject;

/// A button. The disabled buttons the Kotlin subject puts around it are not controls here: they are never focus stops,
/// so the shared harness's `FocusOrder` (the subject and the next control) is exactly what Tab should walk.
pub struct FocusOrderSubject(Button);

impl FocusOrderSubject {
    pub fn new(_setup: &Map<String, Value>) -> Self {
        FocusOrderSubject(Button::new(ButtonVariant::Outlined))
    }
}

impl Subject for FocusOrderSubject {
    fn input(&mut self, input: &Input) -> bool {
        self.0.handle(input).focus
    }

    fn observe(&self, _key: &str) -> Option<Value> {
        None
    }
}
