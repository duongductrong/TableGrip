//! Holds this crate to `conformance/core/golden`, which the Kotlin core records (`CoreGoldenTest`) from the inputs in
//! `conformance/core/inputs`. Each subject this crate implements gets a runner in `run`; the others are reported as
//! pending until it does. Offsets in the golden files are UTF-16 code units and every span also carries its text.

use serde_json::Value;
use std::path::{Path, PathBuf};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance/core")
}

fn read(path: &Path) -> Value {
    serde_json::from_str(&std::fs::read_to_string(path).unwrap())
        .unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

/// The output for one case of `subject`, or `None` while the subject isn't ported.
#[allow(clippy::match_single_binding)] // an arm per subject arrives with its port (M1)
fn run(subject: &str, _case: &Value) -> Option<Value> {
    match subject {
        _ => None,
    }
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

#[test]
fn golden() {
    let mut problems = Vec::new();
    let mut pending = Vec::new();
    let mut passed = 0;
    for input_path in json_files(&root().join("inputs")) {
        let name = input_path.file_name().unwrap().to_str().unwrap().to_owned();
        let input = read(&input_path);
        let golden_path = root().join("golden").join(&name);
        if !golden_path.is_file() {
            problems.push(format!(
                "{name}: no golden file; record it on the Kotlin side"
            ));
            continue;
        }
        let golden = read(&golden_path);
        let subject = input["subject"].as_str().unwrap();
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
                    "{name} / {}: expected {}, got {got}",
                    case["name"], want["output"]
                )),
            }
        }
    }
    println!("core golden: {passed} passed, pending subjects: {pending:?}");
    assert!(problems.is_empty(), "{}", problems.join("\n"));
}
