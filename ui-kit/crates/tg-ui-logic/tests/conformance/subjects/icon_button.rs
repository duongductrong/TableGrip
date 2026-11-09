use serde_json::{Map, Value};
use tg_ui_logic::{IconButton, Input};

use super::{Subject, flag};
use crate::step::Step;

pub struct IconButtonSubject {
    button: IconButton,
    clicks: u32,
}

impl IconButtonSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let mut button = IconButton::new();
        button.button.enabled = flag(setup, "enabled", true);
        IconButtonSubject { button, clicks: 0 }
    }
}

impl Subject for IconButtonSubject {
    fn input(&mut self, input: &Input) -> bool {
        let r = self.button.handle(input);
        self.clicks += r.activated as u32;
        r.focus
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Hover(None) => self.button.tooltip.pointer_enter(),
            Step::Unhover => self.button.tooltip.pointer_leave(),
            Step::Wait(ms) => self.button.tooltip.advance(*ms),
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "clicks" => Some(Value::from(self.clicks)),
            "shown:tip" => Some(Value::Bool(self.button.tooltip.shown())),
            _ => None,
        }
    }
}
