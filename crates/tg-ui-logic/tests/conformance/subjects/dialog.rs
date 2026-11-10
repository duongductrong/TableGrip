use serde_json::{Map, Value};
use tg_ui_logic::dialog::closes;
use tg_ui_logic::{Input, Key, KeyEvent, Phase, Platform, dispatch};

use super::Subject;
use super::keymap::keys;

pub struct DialogSubject {
    close_on_escape: bool,
    inner: Vec<Key>,
    handler: Vec<Key>,
    closed: bool,
    inner_hits: i64,
    handler_hits: i64,
}

impl DialogSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        DialogSubject {
            close_on_escape: setup
                .get("closeOnEscape")
                .and_then(Value::as_bool)
                .unwrap_or(true),
            inner: keys(setup, "inner"),
            handler: keys(setup, "handler"),
            closed: false,
            inner_hits: 0,
            handler_hits: 0,
        }
    }
}

impl Subject for DialogSubject {
    fn input(&mut self, input: &Input) -> bool {
        let Input::Key(key, mods) = input else {
            return false;
        };
        let event = KeyEvent::from_input(*key, *mods, Platform::current());
        // Layer 0 is the dialog (a bubble handler only), layer 1 its focused content.
        dispatch(2, |layer, phase| match (layer, phase) {
            (1, Phase::Bubble) => {
                let hit = self.inner.contains(key);
                self.inner_hits += i64::from(hit);
                hit
            }
            (0, Phase::Bubble) => {
                let taken = self.handler.contains(key);
                self.handler_hits += i64::from(taken);
                if closes(&event, taken, self.close_on_escape) {
                    self.closed = true;
                }
                taken || self.closed
            }
            _ => false,
        });
        false
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "closed" => Some(Value::Bool(self.closed)),
            "innerHits" => Some(Value::from(self.inner_hits)),
            "handlerHits" => Some(Value::from(self.handler_hits)),
            _ => None,
        }
    }
}
