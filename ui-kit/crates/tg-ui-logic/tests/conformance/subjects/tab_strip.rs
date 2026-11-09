use serde_json::{Map, Value};
use tg_ui_logic::{Input, TabStrip};

use super::Subject;
use crate::step::Step;

/// What each tab of the Kotlin subject measures: its title, padding and, when closable, the close button.
const TAB_WIDTH: f64 = 75.0;

pub struct TabStripSubject {
    titles: Vec<String>,
    strip: TabStrip,
}

impl TabStripSubject {
    pub fn new(setup: &Map<String, Value>) -> Self {
        let count = setup.get("tabs").and_then(Value::as_u64).unwrap_or(3) as usize;
        let width = setup.get("width").and_then(Value::as_f64).unwrap_or(400.0);
        let selected = setup.get("selected").and_then(Value::as_u64).unwrap_or(0) as usize;
        TabStripSubject {
            titles: (0..count).map(|i| format!("Tab {i}")).collect(),
            strip: TabStrip::new(
                vec![TAB_WIDTH; count],
                width,
                (count > 0).then_some(selected),
            ),
        }
    }

    fn index(tag: &str, prefix: &str) -> Option<usize> {
        tag.strip_prefix(prefix)?.parse().ok()
    }
}

impl Subject for TabStripSubject {
    /// A click on the strip itself does nothing; the strip has focus only when asked for.
    fn input(&mut self, input: &Input) -> bool {
        if let Input::Key(key, _) = input {
            self.strip.key(*key);
        }
        false
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::Click(Some(tag)) if Self::index(tag, "tab:").is_some() => {
                self.strip.click(Self::index(tag, "tab:").unwrap());
            }
            Step::Click(Some(tag)) if Self::index(tag, "close:").is_some() => {
                let i = Self::index(tag, "close:").unwrap();
                self.titles.remove(i);
                self.strip.close(i);
            }
            other => return Err(format!("unsupported step {other:?}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "selected" => Some(Value::from(
                self.strip
                    .selected()
                    .map(|i| self.titles[i].clone())
                    .unwrap_or_default(),
            )),
            "tabs" => Some(Value::from(self.titles.join(","))),
            "selectedVisible" => Some(Value::Bool(
                self.strip
                    .selected()
                    .is_some_and(|i| self.strip.is_visible(i)),
            )),
            _ => None,
        }
    }
}
