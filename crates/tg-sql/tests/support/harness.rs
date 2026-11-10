//! The core golden harness, shared by every crate that ports part of the Kotlin core (include it with
//! `#[path = "../../tg-sql/tests/support/harness.rs"] mod harness;`).
//!
//! It holds a crate to `conformance/core/golden`, which the Kotlin core records (`CoreGoldenTest`) from the inputs in
//! `conformance/core/inputs`. Subjects belong to crates by name: `chart-*` to tg-chart, `data-*` to tg-data, the rest
//! to tg-sql. Each crate runs the subjects it owns; one it owns but can't run yet is reported as pending, which fails
//! the test, so a recorded subject can't be left unported. Offsets in the golden files are UTF-16 code units; [`u16`]
//! and [`byte`] convert.

#![allow(dead_code)]

use serde_json::Value;
use std::path::{Path, PathBuf};

/// The crate that owns `subject`.
pub fn owner(subject: &str) -> &'static str {
    if subject.starts_with("chart-") {
        "tg-chart"
    } else if subject.starts_with("data-") {
        "tg-data"
    } else {
        "tg-sql"
    }
}

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance/core")
}

fn read(path: &Path) -> Value {
    serde_json::from_str(&std::fs::read_to_string(path).unwrap())
        .unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

fn json_files(dir: &Path) -> Vec<PathBuf> {
    let mut files: Vec<_> = std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .collect();
    files.sort();
    files
}

/// Checks every subject `krate` owns against its golden file, with `run` turning one case into its output (`None`
/// while the subject isn't ported).
pub fn check(krate: &str, run: impl Fn(&str, &Value) -> Option<Value>) {
    let mut problems = Vec::new();
    let mut pending = Vec::new();
    let mut passed = 0;
    for input_path in json_files(&root().join("inputs")) {
        let name = input_path.file_name().unwrap().to_str().unwrap().to_owned();
        let input = read(&input_path);
        let subject = input["subject"].as_str().unwrap();
        if owner(subject) != krate {
            continue;
        }
        let golden_path = root().join("golden").join(&name);
        if !golden_path.is_file() {
            problems.push(format!(
                "{name}: no golden file; record it on the Kotlin side"
            ));
            continue;
        }
        let golden = read(&golden_path);
        assert_eq!(golden["subject"], input["subject"], "{name}");
        let cases = input["cases"].as_array().unwrap();
        let expected = golden["cases"].as_array().unwrap();
        let names = |v: &[Value]| v.iter().map(|c| c["name"].clone()).collect::<Vec<_>>();
        if names(cases) != names(expected) {
            problems.push(format!(
                "{name}: golden cases don't match the inputs; re-record"
            ));
            continue;
        }
        for (case, want) in cases.iter().zip(expected) {
            match run(subject, case) {
                None => {
                    pending.push(subject.to_owned());
                    break;
                }
                Some(got) if got == want["output"] => passed += 1,
                Some(got) => problems.push(format!(
                    "{name} / {}:\n  expected {}\n  got      {got}",
                    case["name"], want["output"]
                )),
            }
        }
    }
    println!("{krate} core golden: {passed} passed, pending subjects: {pending:?}");
    for subject in &pending {
        problems.push(format!("{subject}: pending (no runner in {krate})"));
    }
    assert!(problems.is_empty(), "{}", problems.join("\n"));
}

/// The UTF-16 offset of byte `offset` in `s`.
pub fn u16(s: &str, offset: usize) -> usize {
    s[..offset].encode_utf16().count()
}

/// The byte offset of UTF-16 offset `offset` in `s`.
pub fn byte(s: &str, offset: usize) -> usize {
    let mut units = 0;
    for (i, c) in s.char_indices() {
        if units >= offset {
            return i;
        }
        units += c.len_utf16();
    }
    s.len()
}

pub fn str<'a>(case: &'a Value, key: &str) -> &'a str {
    case[key]
        .as_str()
        .unwrap_or_else(|| panic!("case {}: no string {key}", case["name"]))
}

pub fn opt_str<'a>(case: &'a Value, key: &str) -> Option<&'a str> {
    case.get(key).and_then(Value::as_str)
}
