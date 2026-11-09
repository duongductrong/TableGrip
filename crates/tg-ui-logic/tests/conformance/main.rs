//! Runs `conformance/scenarios` against the state machines in this crate: the same files the Kotlin app passes
//! (`./gradlew :app:test --tests '*ConformanceTest*'`). Components without a subject here are reported as pending.
//!
//! Each component's harness lives in its own file under `subjects/`; adding one is a new file plus one line in
//! `subjects/mod.rs`.

mod step;
mod subjects;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::{Map, Value};
use step::Step;
use tg_ui_logic::{FocusOrder, Input, Key};

fn scenarios_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance/scenarios")
}

/// The component under test plus, with `setup.next`, a second focusable control after it. The subject is focus slot
/// 0, the next control slot 1.
struct Harness {
    subject: Box<dyn subjects::Subject>,
    focus: FocusOrder,
}

impl Harness {
    fn new(component: &str, setup: &Map<String, Value>) -> Option<Harness> {
        let subject = subjects::make(component, setup)?;
        let next = setup.get("next").and_then(Value::as_bool).unwrap_or(false);
        Some(Harness {
            subject,
            focus: FocusOrder::new(if next { 2 } else { 1 }),
        })
    }

    fn send(&mut self, input: Input) {
        if self.subject.input(&input) {
            self.focus.focus(0);
        }
    }

    /// Runs one step, then tells the subject when it has just gained focus.
    fn step(&mut self, step: Step) -> Result<(), String> {
        let had_focus = self.focus.focused() == Some(0);
        self.route(step)?;
        if !had_focus && self.focus.focused() == Some(0) {
            self.subject.on_focus();
        }
        Ok(())
    }

    fn route(&mut self, step: Step) -> Result<(), String> {
        match step {
            Step::Click(None) => self.send(Input::Click),
            Step::Focus => self.focus.focus(0),
            Step::Key(key, mods) => {
                let on_subject = self.focus.focused() == Some(0);
                let traversal = key == Key::Tab && !mods.primary && !mods.alt;
                if on_subject && self.subject.claims_key(key, mods) {
                    self.send(Input::Key(key, mods));
                } else if on_subject && traversal && self.subject.tab(mods.shift) {
                    // Focus moved between the subject's own stops.
                } else if !self.focus.handle_key(key, mods) && on_subject {
                    self.send(Input::Key(key, mods));
                }
            }
            Step::Type(text) => {
                if self.focus.focused() == Some(0) {
                    self.send(Input::Text(text));
                }
            }
            // Steps the shared harness has no generic meaning for go to the subject (hover, waits, clicks on parts).
            other => {
                self.subject.step(&other)?;
                if self.subject.pointer_focuses(&other) {
                    self.focus.focus(0);
                }
            }
        }
        Ok(())
    }

    fn observe(&self, key: &str) -> Option<Value> {
        match key {
            "focused" => Some(Value::Bool(self.focus.focused() == Some(0))),
            "nextFocused" => Some(Value::Bool(self.focus.focused() == Some(1))),
            _ => self.subject.observe(key),
        }
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
        harness.step(Step::parse(step)?)?;
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
