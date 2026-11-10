use serde_json::{Map, Value};
use tg_ui_logic::{Checkbox, Input};

use super::{Subject, flag};

pub struct CheckboxSubject(Checkbox);

impl CheckboxSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        CheckboxSubject(Checkbox {
            checked: flag(setup, "checked", false),
            enabled: flag(setup, "enabled", true),
        })
    }
}

impl Subject for CheckboxSubject {
    fn input(&mut self, input: &Input) -> bool {
        self.0.handle(input).focus
    }

    fn observe(&self, key: &str) -> Option<Value> {
        (key == "checked").then(|| Value::Bool(self.0.checked))
    }
}
