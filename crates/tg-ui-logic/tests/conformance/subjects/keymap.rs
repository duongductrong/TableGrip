use std::collections::HashSet;

use serde_json::{Map, Value};
use tg_ui_logic::{
    Code, Input, Key, KeyEvent, Keymap, Modifiers, Phase, Platform, Preset, dispatch,
};

use super::{Subject, flag};
use crate::step::Step;

const WINDOW: usize = 0;
const INNER: usize = 1;

/// The Kotlin subject's window: shortcuts named in `preview` run in the preview pass, the rest in the bubble pass, with
/// a focused control (`inner` keys) between them.
pub struct KeymapSubject {
    keymap: Keymap,
    preview: HashSet<String>,
    inner: Vec<Key>,
    fired: String,
    fires: i64,
    inner_hits: i64,
}

pub fn names(setup: &Map<String, Value>, name: &str) -> Vec<String> {
    setup
        .get(name)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str().map(str::to_owned))
        .collect()
}

pub fn keys(setup: &Map<String, Value>, name: &str) -> Vec<Key> {
    names(setup, name)
        .iter()
        .filter_map(|n| Key::from_name(n))
        .collect()
}

impl KeymapSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let preset = Preset::of(
            setup
                .get("preset")
                .and_then(Value::as_str)
                .unwrap_or("Default"),
        );
        let mut keymap = Keymap::new(Platform::current(), preset);
        keymap.recording = flag(setup, "recording", false);
        KeymapSubject {
            keymap,
            preview: names(setup, "preview").into_iter().collect(),
            inner: keys(setup, "inner"),
            fired: String::new(),
            fires: 0,
            inner_hits: 0,
        }
    }

    /// Runs the key through the window, the inner control and back. `act` is false for a dry run.
    fn press(&mut self, key: Key, mods: Modifiers, act: bool) -> bool {
        let event = KeyEvent::from_input(key, mods, self.keymap.platform());
        self.press_event(event, act)
    }

    fn press_event(&mut self, event: KeyEvent, act: bool) -> bool {
        let mut fired = None;
        let mut inner_hit = false;
        let result = dispatch(2, |layer, phase| match (layer, phase) {
            (WINDOW, phase) => {
                let wants_preview = phase == Phase::Preview;
                let ids: Vec<_> = self
                    .keymap
                    .window_matching(&event)
                    .into_iter()
                    .filter(|id| self.preview.contains(*id) == wants_preview)
                    .collect();
                let hit = !ids.is_empty();
                if hit {
                    fired = Some(ids.join(","));
                }
                hit
            }
            (INNER, Phase::Bubble) => {
                inner_hit = matches!(event.code, Code::Key(key) if self.inner.contains(&key));
                inner_hit
            }
            _ => false,
        });
        if act {
            if let Some(ids) = fired {
                self.fired = ids;
                self.fires += 1;
            }
            self.inner_hits += i64::from(inner_hit);
        }
        result.consumed()
    }
}

impl Subject for KeymapSubject {
    fn input(&mut self, input: &Input) -> bool {
        if let Input::Key(key, mods) = input {
            self.press(*key, *mods, true);
        }
        false
    }

    /// A shortcut takes its key before focus traversal does, so Tab can be one.
    fn claims_key(&self, key: Key, mods: Modifiers) -> bool {
        let mut probe = KeymapSubject {
            keymap: self.keymap.clone(),
            preview: self.preview.clone(),
            inner: self.inner.clone(),
            fired: String::new(),
            fires: 0,
            inner_hits: 0,
        };
        probe.press(key, mods, false)
    }

    /// Keys beyond the shared set, and keys held with the physical Control or Meta key.
    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Press(event) => {
                self.press_event(*event, true);
                Ok(())
            }
            other => Err(format!("unsupported step {other:?}")),
        }
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "fired" => Some(Value::from(self.fired.clone())),
            "fires" => Some(Value::from(self.fires)),
            "innerHits" => Some(Value::from(self.inner_hits)),
            _ => None,
        }
    }
}
