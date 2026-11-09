//! Every token in `conformance/golden/themes/*.json`, which the Kotlin app dumps (`ThemeTokensGoldenTest`), must
//! come out of `tg-theme` with the same value.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::Value;
use tg_theme::{AppearanceSettings, Registry, ThemeOverride, ThemeSelection};

fn conformance() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance")
}

fn settings_of(setup: &Value) -> AppearanceSettings {
    let name = setup["theme"].as_str().unwrap().to_owned();
    let dark = setup["dark"].as_bool().unwrap();
    let overrides: BTreeMap<String, ThemeOverride> = setup
        .get("themeOverrides")
        .map(|v| serde_json::from_value(v.clone()).unwrap())
        .unwrap_or_default();
    let mut theme = ThemeSelection::default();
    if dark {
        theme.dark = name;
    } else {
        theme.light = name;
    }
    AppearanceSettings {
        theme,
        accent_color: setup["accentColor"].as_str().unwrap_or("").to_owned(),
        theme_overrides: overrides,
        ui_font_size: setup["uiFontSize"].as_i64().unwrap_or(0) as i32,
        island_corner_radius: setup["islandCornerRadius"].as_i64().unwrap_or(10) as i32,
        ..AppearanceSettings::default()
    }
}

#[test]
fn tokens_match_the_kotlin_app() {
    let dir = conformance().join("golden/themes");
    let mut files: Vec<PathBuf> = std::fs::read_dir(&dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .collect();
    files.sort();
    assert!(
        files.len() >= 12,
        "expected a file per built-in variant at least"
    );

    let built_in = Registry::new();
    let mut with_user = Registry::new();
    with_user.reload_user_themes(&conformance().join("fixtures/themes/user"));
    assert_eq!(
        with_user.errors().len(),
        2,
        "broken.json and empty.json are reported: {:?}",
        with_user.errors()
    );

    let mut failures = Vec::new();
    let mut checked = 0;
    for file in &files {
        let golden: Value = serde_json::from_str(&std::fs::read_to_string(file).unwrap()).unwrap();
        let name = file.file_name().unwrap().to_string_lossy().into_owned();
        let setup = &golden["setup"];
        let registry = if golden["case"] == "built-in" {
            &built_in
        } else {
            &with_user
        };
        let dark = setup["dark"].as_bool().unwrap();
        let theme = settings_of(setup).theme(registry, dark);
        let mut check = |key: &str, expected: &Value, actual: Value| {
            checked += 1;
            if *expected != actual {
                failures.push(format!("{name} {key}: expected {expected}, got {actual}"));
            }
        };

        let resolved = &golden["resolved"];
        check(
            "resolved.name",
            &resolved["name"],
            Value::from(theme.colors.name.clone()),
        );
        check(
            "resolved.family",
            &resolved["family"],
            Value::from(theme.colors.family.clone()),
        );
        check(
            "resolved.dark",
            &resolved["dark"],
            Value::from(theme.colors.is_dark),
        );

        let tokens = theme.colors.tokens();
        let expected = golden["colors"].as_object().unwrap();
        // Sorted: serde_json keeps file order when another crate in the build turns on `preserve_order`.
        let mut names: Vec<&str> = expected.keys().map(String::as_str).collect();
        names.sort_unstable();
        assert_eq!(
            names,
            tokens.keys().copied().collect::<Vec<_>>(),
            "{name}: token names"
        );
        for (key, value) in &tokens {
            let actual = value
                .map(|c| Value::from(c.to_hex()))
                .unwrap_or(Value::Null);
            check(key, &expected[*key], actual);
        }

        for (token, style) in &theme.colors.syntax {
            let e = &golden["syntax"][token];
            check(
                &format!("syntax.{token}.color"),
                &e["color"],
                Value::from(style.color.to_hex()),
            );
            check(
                &format!("syntax.{token}.italic"),
                &e["italic"],
                Value::from(style.italic),
            );
            check(
                &format!("syntax.{token}.bold"),
                &e["bold"],
                Value::from(style.bold),
            );
        }
        let chart: Vec<Value> = theme
            .colors
            .chart
            .palette
            .iter()
            .map(|c| Value::from(c.to_hex()))
            .collect();
        check("chart", &golden["chart"], Value::from(chart));

        for (key, color) in theme.ui.tokens() {
            check(key, &golden["ui"][key], Value::from(color.to_hex()));
        }
        let fonts = &golden["fonts"];
        check(
            "fonts.ui.size",
            &fonts["ui.size"],
            Value::from(theme.fonts.ui_size as f64),
        );
        check(
            "fonts.ui.lineHeight",
            &fonts["ui.lineHeight"],
            Value::from(theme.fonts.ui_line_height as f64),
        );
        check(
            "fonts.editor.size",
            &fonts["editor.size"],
            Value::from(theme.fonts.editor_size as f64),
        );
        check(
            "fonts.editor.lineHeight",
            &fonts["editor.lineHeight"],
            Value::from(theme.fonts.editor_line_height as f64),
        );
        check(
            "fonts.island.cornerRadius",
            &fonts["island.cornerRadius"],
            Value::from(theme.island_radius as f64),
        );
    }
    assert!(
        failures.is_empty(),
        "{} of {checked} values differ:\n{}",
        failures.len(),
        failures.join("\n")
    );
    println!("{checked} values in {} files match", files.len());
}
