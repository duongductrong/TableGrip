//! Runs `ui-kit/conformance/scenarios` against the state machines in this crate: the same files the Kotlin app passes
//! (`./gradlew :app:test --tests '*ConformanceTest*'`). Components this crate doesn't have yet are reported as pending.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::{Map, Value};
use tg_ui_logic::button::ButtonVariant;
use tg_ui_logic::{Button, Checkbox, FocusOrder, Input, Key, Modifiers};

fn scenarios_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance/scenarios")
}

/// The component under test, plus an optional second focusable control after it (`setup.next`).
enum Subject {
    Checkbox(Checkbox),
    Button(Button, u32),
}

struct Harness {
    subject: Subject,
    focus: FocusOrder,
}

impl Harness {
    /// None when this crate has no state machine for the component yet.
    fn new(component: &str, setup: &Map<String, Value>) -> Option<Harness> {
        let flag =
            |name: &str, default: bool| setup.get(name).and_then(Value::as_bool).unwrap_or(default);
        let subject = match component {
            "checkbox" => Subject::Checkbox(Checkbox {
                checked: flag("checked", false),
                enabled: flag("enabled", true),
            }),
            "button" => {
                let variant = match setup.get("variant").and_then(Value::as_str) {
                    Some("outlined") => ButtonVariant::Outlined,
                    _ => ButtonVariant::Default,
                };
                Subject::Button(
                    Button {
                        variant,
                        enabled: flag("enabled", true),
                    },
                    0,
                )
            }
            _ => return None,
        };
        Some(Harness {
            subject,
            focus: FocusOrder::new(if flag("next", false) { 2 } else { 1 }),
        })
    }

    fn send(&mut self, input: &Input) {
        let wants_focus = match &mut self.subject {
            Subject::Checkbox(c) => c.handle(input).focus,
            Subject::Button(b, clicks) => {
                let r = b.handle(input);
                *clicks += r.activated as u32;
                r.focus
            }
        };
        if wants_focus {
            self.focus.focus(0);
        }
    }

    fn step(&mut self, step: &Value) -> Result<(), String> {
        match step {
            Value::String(s) if s == "click" => self.send(&Input::Click),
            Value::String(s) if s == "focus" => self.focus.focus(0),
            Value::Object(o) if o.contains_key("key") => {
                let name = o["key"].as_str().unwrap_or_default();
                let key = Key::from_name(name).ok_or(format!("unknown key {name}"))?;
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
                        other => return Err(format!("unknown modifier {other:?}")),
                    }
                }
                if !self.focus.handle_key(key, mods) && self.focus.focused() == Some(0) {
                    self.send(&Input::Key(key, mods));
                }
            }
            Value::Object(o) if o.contains_key("type") => {
                if self.focus.focused() == Some(0) {
                    self.send(&Input::Text(
                        o["type"].as_str().unwrap_or_default().to_owned(),
                    ));
                }
            }
            other => return Err(format!("unknown step {other}")),
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        Some(match (key, &self.subject) {
            ("focused", _) => Value::Bool(self.focus.focused() == Some(0)),
            ("nextFocused", _) => Value::Bool(self.focus.focused() == Some(1)),
            ("checked", Subject::Checkbox(c)) => Value::Bool(c.checked),
            ("clicks", Subject::Button(_, clicks)) => Value::from(*clicks),
            _ => return None,
        })
    }
}

/// Runs one case; Ok(None) when the component is pending.
fn run_case(component: &str, case: &Value) -> Result<Option<()>, String> {
    let empty = Map::new();
    let setup = case
        .get("setup")
        .and_then(Value::as_object)
        .unwrap_or(&empty);
    let Some(mut harness) = Harness::new(component, setup) else {
        return Ok(None);
    };
    for step in case
        .get("steps")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        harness.step(step)?;
    }
    let expect = case["expect"].as_object().ok_or("case has no expect")?;
    let mut wrong = BTreeMap::new();
    for (key, want) in expect {
        let got = harness
            .observe(key)
            .ok_or(format!("{component} can't report '{key}'"))?;
        if &got != want {
            wrong.insert(key.clone(), format!("expected {want}, got {got}"));
        }
    }
    if wrong.is_empty() {
        Ok(Some(()))
    } else {
        Err(format!("{wrong:?}"))
    }
}

#[test]
fn scenarios() {
    let mut files: Vec<_> = std::fs::read_dir(scenarios_dir())
        .expect("scenarios dir")
        .map(|e| e.unwrap().path())
        .collect();
    files.sort();
    let (mut passed, mut pending, mut failures) = (0, Vec::new(), Vec::new());
    for path in files
        .iter()
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
    {
        let file: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let component = file["component"].as_str().unwrap();
        for case in file["cases"].as_array().unwrap() {
            let name = format!("{component}: {}", case["name"].as_str().unwrap_or("?"));
            match run_case(component, case) {
                Ok(Some(())) => passed += 1,
                Ok(None) => pending.push(name),
                Err(e) => failures.push(format!("{name}: {e}")),
            }
        }
    }
    println!("conformance: {passed} passed, {} pending", pending.len());
    for p in &pending {
        println!("  pending  {p}");
    }
    assert!(passed > 0, "no scenario ran");
    assert!(
        failures.is_empty(),
        "failing scenarios:\n{}",
        failures.join("\n")
    );
}

/// Guards against a runner that passes everything: each of these expectations is wrong and must fail.
#[test]
fn runner_rejects_wrong_expectations() {
    let wrong = [
        (
            "checkbox",
            r#"{"steps": ["click"], "expect": {"checked": false}}"#,
        ),
        (
            "checkbox",
            r#"{"steps": ["focus"], "expect": {"focused": false}}"#,
        ),
        ("button", r#"{"steps": ["click"], "expect": {"clicks": 2}}"#),
        (
            "button",
            r#"{"setup": {"next": true}, "steps": ["focus", {"key": "Tab"}], "expect": {"clicks": 1}}"#,
        ),
    ];
    for (component, case) in wrong {
        let case: Value = serde_json::from_str(case).unwrap();
        assert!(
            run_case(component, &case).is_err(),
            "{component} {case} should fail"
        );
    }
}
