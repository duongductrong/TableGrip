use serde_json::{Map, Value};
use tg_text::{Editor, KeyResult, MemoryClipboard, Options, Platform, grapheme_count};
use tg_ui_logic::{Input, Key, Modifiers};

use super::{Subject, flag};
use crate::step::Step;

/// TextField (`text-field`) or TextArea (`text-area`) over `tg-text`, inside a parent that records the keys the field
/// lets through, on a clipboard of its own. Reports the same keys as the Kotlin `TextSubject`.
pub struct TextSubject {
    editor: Editor,
    clipboard: MemoryClipboard,
    bubbled: Vec<String>,
}

impl TextSubject {
    pub fn new(component: &str, setup: &Map<String, Value>) -> Self {
        let text = |name: &str| setup.get(name).and_then(Value::as_str).map(str::to_owned);
        let options = Options {
            multi_line: component == "text-area",
            password: flag(setup, "password", false),
            read_only: flag(setup, "readOnly", false),
            enabled: flag(setup, "enabled", true),
            placeholder: text("placeholder").unwrap_or_default(),
            platform: Platform::current(),
        };
        TextSubject {
            editor: Editor::new(text("text").unwrap_or_default(), options),
            clipboard: MemoryClipboard(text("clipboard")),
            bubbled: Vec::new(),
        }
    }
}

impl Subject for TextSubject {
    fn input(&mut self, input: &Input) -> bool {
        match input {
            // The scenarios' text is shorter than the field, so the pointer lands past its end.
            Input::Click => {
                let enabled = self.editor.options().enabled;
                if enabled {
                    self.editor.place_caret(self.editor.text().len(), false);
                }
                enabled
            }
            Input::Key(key, mods) => {
                if self.editor.handle_key(*key, *mods, &mut self.clipboard) == KeyResult::Bubble {
                    self.bubbled.push(describe(*key, *mods));
                }
                false
            }
            Input::Text(text) => {
                self.editor.insert_text(text);
                false
            }
        }
    }

    fn step(&mut self, step: &Step) -> Result<(), String> {
        match step {
            Step::DoubleClick(None) if self.editor.options().enabled => {
                self.editor.select_word_at(self.editor.text().len());
                Ok(())
            }
            other => Err(format!("unsupported step {other:?}")),
        }
    }

    fn observe(&self, key: &str) -> Option<Value> {
        let text = self.editor.text();
        let head = self.editor.selection().head;
        Some(match key {
            "text" => Value::from(text),
            "selected" => Value::from(self.editor.selected_text()),
            "caret" => Value::from(grapheme_count(&text[..head])),
            "clipboard" => Value::from(self.clipboard.0.clone().unwrap_or_default()),
            "bubbled" => Value::from(self.bubbled.join(" ")),
            "placeholder" => Value::Bool(self.editor.placeholder_visible()),
            _ => return None,
        })
    }
}

/// `primary+shift+alt+Key`, as the Kotlin subject names a key press.
fn describe(key: Key, mods: Modifiers) -> String {
    let name = match key {
        Key::Letter(c) => c.to_string(),
        other => format!("{other:?}"),
    };
    [
        (mods.primary, "primary"),
        (mods.shift, "shift"),
        (mods.alt, "alt"),
    ]
    .into_iter()
    .filter_map(|(held, m)| held.then_some(m))
    .chain([name.as_str()])
    .collect::<Vec<_>>()
    .join("+")
}
