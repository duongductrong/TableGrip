use serde_json::{Map, Value};
use tg_ui_logic::{Input, PopupMenu};

use super::{Subject, flag};
use crate::step::Step;

pub struct PopupMenuSubject {
    menu: PopupMenu,
    chosen: i64,
}

impl PopupMenuSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let len = setup
            .get("items")
            .and_then(Value::as_array)
            .map_or(4, Vec::len);
        let mut enabled = vec![true; len];
        for i in setup
            .get("disabled")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(Value::as_u64)
        {
            if let Some(slot) = enabled.get_mut(i as usize) {
                *slot = false;
            }
        }
        let selected = match setup.get("selected").and_then(Value::as_i64) {
            Some(i) => usize::try_from(i).ok(),
            None => Some(0),
        };
        let mut menu = PopupMenu::new(enabled, selected);
        if flag(setup, "open", false) {
            menu.open();
        }
        PopupMenuSubject { menu, chosen: -1 }
    }

    fn record(&mut self, chosen: Option<usize>) {
        if let Some(i) = chosen {
            self.chosen = i as i64;
        }
    }
}

impl Subject for PopupMenuSubject {
    /// A click is on the trigger and opens the menu; the open popup then holds focus.
    fn input(&mut self, input: &Input) -> bool {
        match input {
            Input::Click => {
                self.menu.open();
                true
            }
            other => {
                let r = self.menu.handle(other);
                self.record(r.chosen);
                false
            }
        }
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            // The menu's centre is its middle item (the scenarios use an odd number of items).
            Step::Click(Some(tag)) if tag == "menu" => {
                let middle = self.menu_len() / 2;
                let r = self.menu.click_item(middle);
                self.record(r.chosen);
            }
            Step::Click(Some(tag)) if tag == "away" => self.menu.click_outside(),
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "open" => Some(Value::Bool(self.menu.is_open())),
            "shown:menu" => Some(Value::Bool(self.menu.is_open())),
            "chosen" => Some(Value::from(self.chosen)),
            _ => None,
        }
    }
}

impl PopupMenuSubject {
    fn menu_len(&self) -> usize {
        self.menu.len()
    }
}
