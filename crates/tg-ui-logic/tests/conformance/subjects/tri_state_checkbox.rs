use serde_json::{Map, Value};
use tg_ui_logic::{Input, TriStateCheckbox};

use super::Subject;

pub struct TriStateCheckboxSubject(TriStateCheckbox);

impl TriStateCheckboxSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let n = |name: &str, default: u64| {
            setup.get(name).and_then(Value::as_u64).unwrap_or(default) as usize
        };
        TriStateCheckboxSubject(TriStateCheckbox::new(n("selected", 0), n("total", 3)))
    }
}

impl Subject for TriStateCheckboxSubject {
    fn input(&mut self, input: &Input) -> bool {
        self.0.handle(input).focus
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "state" => Some(Value::from(self.0.state().name())),
            "selected" => Some(Value::from(self.0.selected)),
            _ => None,
        }
    }
}
