use serde_json::{Map, Value};
use tg_ui_logic::{Input, ListComboBox};

use super::{Subject, flag};

pub struct ListComboBoxSubject {
    combo: ListComboBox,
    changes: u32,
}

impl ListComboBoxSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let len = setup
            .get("items")
            .and_then(Value::as_array)
            .map_or(4, Vec::len);
        let selected = setup.get("selected").and_then(Value::as_u64).unwrap_or(0) as usize;
        let mut combo = ListComboBox::new(len, selected);
        combo.enabled = flag(setup, "enabled", true);
        ListComboBoxSubject { combo, changes: 0 }
    }
}

impl Subject for ListComboBoxSubject {
    fn input(&mut self, input: &Input) -> bool {
        let r = self.combo.handle(input);
        self.changes += r.changed.is_some() as u32;
        r.focus
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "selected" => Some(Value::from(self.combo.selected)),
            "changes" => Some(Value::from(self.changes)),
            _ => None,
        }
    }
}
