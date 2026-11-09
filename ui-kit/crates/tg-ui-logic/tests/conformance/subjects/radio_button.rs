use serde_json::{Map, Value};
use tg_ui_logic::{Input, RadioGroup};

use super::Subject;
use crate::step::Step;

pub struct RadioButtonSubject(RadioGroup);

impl RadioButtonSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let rows = setup
            .get("options")
            .and_then(Value::as_array)
            .map_or(3, Vec::len);
        let selected = setup
            .get("selected")
            .and_then(Value::as_i64)
            .and_then(|i| usize::try_from(i).ok());
        RadioButtonSubject(RadioGroup::new(rows, selected))
    }
}

impl Subject for RadioButtonSubject {
    fn input(&mut self, input: &Input) -> bool {
        // The harness hands over a focus request as the first key; a key on an unfocused group focuses the first row.
        if matches!(input, Input::Click) {
            // An untagged click lands on the middle of the group, which is the middle row.
            return self.0.click(self.0.rows / 2).focus;
        }
        if self.0.focused.is_none() {
            self.0.focus_first();
        }
        self.0.handle(input).focus
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Click(Some(tag)) => {
                let row = tag
                    .strip_prefix("row:")
                    .and_then(|n| n.parse().ok())
                    .ok_or(format!("unknown row {tag}"))?;
                self.0.click(row);
                Ok(())
            }
            other => Err(format!("unsupported step {other:?}")),
        }
    }

    fn observe(&self, key: &str) -> Option<Value> {
        (key == "selected").then(|| Value::from(self.0.selected.map_or(-1, |i| i as i64)))
    }
}
