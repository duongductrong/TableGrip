use serde_json::{Map, Value};
use tg_ui_logic::{Input, Key, Phase, dispatch};

use super::Subject;
use super::keymap::keys;

struct Layer {
    preview: Vec<Key>,
    bubble: Vec<Key>,
}

pub struct KeyDispatchSubject {
    layers: Vec<Layer>,
    trace: String,
    consumed_by: String,
}

impl KeyDispatchSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let layers = setup
            .get("layers")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(Value::as_object)
            .map(|o| Layer {
                preview: keys(o, "preview"),
                bubble: keys(o, "bubble"),
            })
            .collect();
        KeyDispatchSubject {
            layers,
            trace: String::new(),
            consumed_by: String::new(),
        }
    }
}

impl Subject for KeyDispatchSubject {
    fn input(&mut self, input: &Input) -> bool {
        let Input::Key(key, _) = input else {
            return false;
        };
        let layers = &self.layers;
        let result = dispatch(layers.len(), |layer, phase| match phase {
            Phase::Preview => layers[layer].preview.contains(key),
            Phase::Bubble => layers[layer].bubble.contains(key),
        });
        self.trace = result
            .trace
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join(",");
        self.consumed_by = result
            .consumed_by
            .map(|v| v.to_string())
            .unwrap_or_default();
        false
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "trace" => Some(Value::from(self.trace.clone())),
            "consumedBy" => Some(Value::from(self.consumed_by.clone())),
            _ => None,
        }
    }
}
