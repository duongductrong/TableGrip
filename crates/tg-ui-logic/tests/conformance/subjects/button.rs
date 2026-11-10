use serde_json::{Map, Value};
use tg_ui_logic::button::ButtonVariant;
use tg_ui_logic::{Button, Input};

use super::{Subject, flag};

pub struct ButtonSubject {
    button: Button,
    clicks: u32,
}

impl ButtonSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let variant = match setup.get("variant").and_then(Value::as_str) {
            Some("outlined") => ButtonVariant::Outlined,
            _ => ButtonVariant::Default,
        };
        ButtonSubject {
            button: Button {
                variant,
                enabled: flag(setup, "enabled", true),
            },
            clicks: 0,
        }
    }
}

impl Subject for ButtonSubject {
    fn input(&mut self, input: &Input) -> bool {
        let r = self.button.handle(input);
        self.clicks += r.activated as u32;
        r.focus
    }

    fn observe(&self, key: &str) -> Option<Value> {
        (key == "clicks").then(|| Value::from(self.clicks))
    }
}
