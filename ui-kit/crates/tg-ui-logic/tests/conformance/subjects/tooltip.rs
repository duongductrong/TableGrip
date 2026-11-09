use serde_json::{Map, Value};
use tg_ui_logic::{Input, Tooltip};

use super::Subject;
use crate::step::Step;

pub struct TooltipSubject(Tooltip);

impl TooltipSubject {
    pub fn new(_setup: &Map<String, Value>) -> Self {
        TooltipSubject(Tooltip::new())
    }
}

impl Subject for TooltipSubject {
    fn input(&mut self, input: &Input) -> bool {
        if matches!(input, Input::Click) {
            self.0.press();
        }
        false
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Hover(None) => self.0.pointer_enter(),
            // The "away" label beside the target: the pointer is on something else.
            Step::Hover(Some(tag)) if tag == "away" => self.0.pointer_leave(),
            Step::Unhover => self.0.pointer_leave(),
            Step::Wait(ms) => self.0.advance(*ms),
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        (key == "shown:tip").then(|| Value::Bool(self.0.shown()))
    }
}
