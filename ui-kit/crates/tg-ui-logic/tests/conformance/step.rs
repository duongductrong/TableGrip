//! The step vocabulary of scenario files, shared with the Kotlin runner (`Scenario.kt`).

use serde_json::Value;
use tg_ui_logic::{Key, Modifiers};

/// One thing a scenario does. Pointer steps act on the subject unless they name a test tag (a menu item, a tree row).
#[derive(Clone, Debug, PartialEq)]
pub enum Step {
    Click(Option<String>),
    DoubleClick(Option<String>),
    RightClick(Option<String>),
    Hover(Option<String>),
    Unhover,
    Focus,
    Key(Key, Modifiers),
    Type(String),
    /// Milliseconds of time passing, for delays such as a tooltip's.
    Wait(u64),
}

impl Step {
    pub fn parse(value: &Value) -> Result<Step, String> {
        if let Some(name) = value.as_str() {
            return Ok(match name {
                "click" => Step::Click(None),
                "doubleClick" => Step::DoubleClick(None),
                "rightClick" => Step::RightClick(None),
                "hover" => Step::Hover(None),
                "unhover" => Step::Unhover,
                "focus" => Step::Focus,
                _ => return Err(format!("unknown step {value}")),
            });
        }
        let o = value.as_object().ok_or(format!("unknown step {value}"))?;
        let text = |name: &str| {
            o[name]
                .as_str()
                .map(str::to_owned)
                .ok_or(format!("'{name}' must be a string"))
        };
        if o.contains_key("key") {
            let name = text("key")?;
            let key = Key::from_name(&name).ok_or(format!("unknown key {name}"))?;
            let mut mods = Modifiers::default();
            for m in o
                .get("mods")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                match m.as_str() {
                    Some("primary") => mods.primary = true,
                    Some("shift") => mods.shift = true,
                    Some("alt") => mods.alt = true,
                    // Word-wise movement: Option on macOS, Ctrl elsewhere.
                    Some("word") if cfg!(target_os = "macos") => mods.alt = true,
                    Some("word") => mods.primary = true,
                    other => return Err(format!("unknown modifier {other:?}")),
                }
            }
            return Ok(Step::Key(key, mods));
        }
        if o.contains_key("type") {
            return Ok(Step::Type(text("type")?));
        }
        if let Some(ms) = o.get("wait") {
            return ms
                .as_u64()
                .map(Step::Wait)
                .ok_or("'wait' must be milliseconds".into());
        }
        for (name, make) in [
            ("click", Step::Click as fn(Option<String>) -> Step),
            ("doubleClick", Step::DoubleClick),
            ("rightClick", Step::RightClick),
            ("hover", Step::Hover),
        ] {
            if o.contains_key(name) {
                return Ok(make(Some(text(name)?)));
            }
        }
        Err(format!("unknown step {value}"))
    }
}
