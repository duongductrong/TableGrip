//! The step vocabulary of scenario files, shared with the Kotlin runner (`Scenario.kt`).

use serde_json::Value;
use tg_ui_logic::{Code, Key, KeyEvent, Modifiers, Platform};

/// One thing a scenario does. Pointer steps act on the subject unless they name a test tag (a menu item, a tree row).
#[derive(Clone, Debug, PartialEq)]
pub enum Step {
    Click(Option<String>),
    /// The middle mouse button.
    MiddleClick(Option<String>),
    DoubleClick(Option<String>),
    RightClick(Option<String>),
    Hover(Option<String>),
    Unhover,
    Focus,
    Key(Key, Modifiers),
    /// A key the shared [`Key`] doesn't name (`Equals`, `Digit1`, `NumpadEnter`…) or one held with the physical `ctrl`
    /// or `meta` modifier: only shortcut subjects take these, through [`Subject::step`](crate::subjects::Subject).
    Press(KeyEvent),
    /// Press at the target's centre, move by (`dx`, `dy`) dp, release.
    Drag {
        tag: Option<String>,
        dx: f64,
        dy: f64,
    },
    Type(String),
    /// Milliseconds of time passing, for delays such as a tooltip's.
    Wait(u64),
}

impl Step {
    pub fn parse(value: &Value) -> Result<Step, String> {
        if let Some(name) = value.as_str() {
            return Ok(match name {
                "click" => Step::Click(None),
                "middleClick" => Step::MiddleClick(None),
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
            let platform = Platform::current();
            let mut mods = Modifiers::default();
            let (mut ctrl, mut meta) = (false, false);
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
                    Some("word") if platform == Platform::Mac => mods.alt = true,
                    Some("word") => mods.primary = true,
                    // The physical Control and Meta (Cmd, Windows) keys.
                    Some("ctrl") => ctrl = true,
                    Some("meta") => meta = true,
                    other => return Err(format!("unknown modifier {other:?}")),
                }
            }
            let key = Key::from_name(&name);
            if let (Some(key), false, false) = (key, ctrl, meta) {
                return Ok(Step::Key(key, mods));
            }
            let code = key
                .map(Code::Key)
                .or_else(|| code_of(&name))
                .ok_or(format!("unknown key {name}"))?;
            let mut event = KeyEvent::new(code, mods, platform);
            event.ctrl |= ctrl;
            event.meta |= meta;
            return Ok(Step::Press(event));
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
        if o.contains_key("drag") {
            let by = |name: &str| o.get(name).map_or(Some(0.0), Value::as_f64);
            return Ok(Step::Drag {
                tag: o["drag"].as_str().map(str::to_owned),
                dx: by("dx").ok_or("'dx' must be a number")?,
                dy: by("dy").ok_or("'dy' must be a number")?,
            });
        }
        for (name, make) in [
            ("click", Step::Click as fn(Option<String>) -> Step),
            ("middleClick", Step::MiddleClick),
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

/// Key names beyond [`Key`], for shortcuts; the Kotlin runner maps the same names.
fn code_of(name: &str) -> Option<Code> {
    Some(match name {
        "Equals" => Code::Equals,
        "Minus" => Code::Minus,
        "Slash" => Code::Slash,
        "Comma" => Code::Comma,
        "Period" => Code::Period,
        "LeftBracket" => Code::LeftBracket,
        "RightBracket" => Code::RightBracket,
        "NumpadEnter" => Code::NumPadEnter,
        "NumpadAdd" => Code::NumPadAdd,
        "NumpadSubtract" => Code::NumPadSubtract,
        _ => {
            let digit = name.strip_prefix("Digit")?.parse::<u8>().ok()?;
            Code::Digit((digit < 10).then_some(digit)?)
        }
    })
}
