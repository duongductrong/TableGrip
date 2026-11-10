//! Ports of SettingsStoreTest, the settings part of StorageTest, ThemeFilesTest and BackupScheduleTest.

use indexmap::IndexMap;
use std::fs;
use tg_core::backup::{BackupFrequency, BackupSchedule};
use tg_core::settings::{KeyBinding, Settings, SettingsStore, ThemeSelection};
use tg_core::theme::{SyntaxStyle, ThemeJson, ThemeOverride, ThemeSpec};

fn store(dir: &tempfile::TempDir) -> SettingsStore {
    SettingsStore::new(dir.path().join("settings.json"))
}

fn map<V: Clone>(pairs: &[(&str, V)]) -> IndexMap<String, V> {
    pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.clone()))
        .collect()
}

#[test]
fn a_settings_file_from_before_customization_loads_with_todays_look() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    fs::write(
        store.file(),
        r#"{ "theme": "Dark", "editorFontSize": 15, "pageSize": 500 }"#,
    )
    .unwrap();
    let loaded = store.load();
    assert_eq!(
        loaded,
        Settings {
            theme: ThemeSelection::with_mode("Dark"),
            editor_font_size: 15,
            page_size: 500,
            ..Settings::default()
        }
    );
    // The defaults are what the app hard-coded before they became settings.
    assert_eq!(loaded.null_text, "<null>");
    assert_eq!(loaded.tab_size, 4);
    assert_eq!(loaded.editor_line_height, 150);
    assert_eq!(loaded.format_keyword_case, "Upper");
    assert_eq!(loaded.grid_density, "Comfortable");
    assert_eq!(loaded.island_corner_radius, 10);
}

#[test]
fn custom_colours_and_key_bindings_survive_a_round_trip() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    let settings = Settings {
        accent_color: "#D95C9A".into(),
        syntax_colors: map(&[("dark.keyword", "#FF0000".to_string())]),
        keymap: "VsCode".into(),
        key_bindings: map(&[(
            "Format",
            KeyBinding {
                key: 76,
                label: "L".into(),
                primary: true,
                shift: true,
                alt: false,
                ctrl: false,
            },
        )]),
        stripe_hidden: vec!["Queries".into()],
        ..Settings::default()
    };
    assert!(store.save(&settings).unwrap());
    assert_eq!(store.load(), settings);
}

#[test]
fn the_theme_takes_zeds_shape_and_a_bare_mode_from_older_files_still_loads() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    fs::write(store.file(), r#"{ "theme": "Light" }"#).unwrap();
    assert_eq!(store.load().theme, ThemeSelection::with_mode("Light"));
    fs::write(
        store.file(),
        r#"{ "theme": { "mode": "Dark", "dark": "One Dark", "light": "One Light" } }"#,
    )
    .unwrap();
    assert_eq!(
        store.load().theme,
        ThemeSelection {
            mode: "Dark".into(),
            dark: "One Dark".into(),
            light: "One Light".into()
        }
    );
}

#[test]
fn hand_written_settings_may_have_comments_and_trailing_commas() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    fs::write(
        store.file(),
        r##"{
  // Like VS Code's and Zed's settings
  "pageSize": 300,
  "themeOverrides": { "One Dark": { "colors": { "accent": "#E5C07B" }, "syntax": { "keyword": "#FF79C6" } } },
}"##,
    )
    .unwrap();
    let loaded = store.load();
    assert_eq!(store.load_error(), None);
    assert_eq!(loaded.page_size, 300);
    assert_eq!(
        loaded.theme_overrides.get("One Dark"),
        Some(&ThemeOverride {
            colors: map(&[("accent", "#E5C07B".to_string())]),
            syntax: map(&[("keyword", SyntaxStyle::color("#FF79C6"))]),
            chart: vec![],
        })
    );
}

#[test]
fn a_file_that_doesnt_parse_is_never_overwritten_with_defaults() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    let broken = r#"{ "pageSize": 300, "editorFontSize": }"#;
    fs::write(store.file(), broken).unwrap();
    assert_eq!(store.load(), Settings::default());
    assert!(store.load_error().is_some());
    let wanted = Settings {
        page_size: 10,
        ..Settings::default()
    };
    assert!(!store.save(&wanted).unwrap());
    assert_eq!(fs::read_to_string(store.file()).unwrap(), broken);
    // Once fixed by hand, it loads and saves again.
    fs::write(store.file(), r#"{ "pageSize": 300 }"#).unwrap();
    assert_eq!(store.load().page_size, 300);
    assert!(store.save(&wanted).unwrap());
}

#[test]
fn only_settings_that_differ_from_the_defaults_are_written() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    store
        .save(&Settings {
            page_size: 50,
            theme: ThemeSelection {
                dark: "Dracula".into(),
                ..ThemeSelection::default()
            },
            ..Settings::default()
        })
        .unwrap();
    let text = fs::read_to_string(store.file()).unwrap();
    assert!(text.contains("pageSize") && text.contains("Dracula"));
    assert!(
        !text.contains("editorFontSize") && !text.contains("mode"),
        "{text}"
    );
    // Two-space indent, a trailing newline, and the order of the declaration.
    assert_eq!(
        text,
        "{\n  \"theme\": {\n    \"dark\": \"Dracula\"\n  },\n  \"pageSize\": 50\n}\n"
    );
    assert_eq!(store.last_written().as_deref(), Some(text.as_str()));
}

#[test]
fn settings_round_trip_and_fall_back_to_defaults_on_bad_files() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    assert_eq!(store.load(), Settings::default());

    let dark = Settings {
        theme: ThemeSelection::with_mode("Dark"),
        page_size: 50,
        ..Settings::default()
    };
    store.save(&dark).unwrap();
    assert_eq!(store.load(), dark);

    fs::write(store.file(), "{ not json").unwrap();
    assert_eq!(store.load(), Settings::default());
}

#[test]
fn ai_access_names_and_missing_fields_default_safely() {
    let loaded: Settings = tg_core::json::from_lenient_str("{}").unwrap();
    assert_eq!(loaded, Settings::default());
    assert_eq!(loaded.ai_access, "SchemaOnly");
}

#[test]
fn a_theme_file_reads_like_zeds_with_tokens_as_colours_or_styles() {
    let family = ThemeJson::parse(
        r##"{
  "$schema": "https://tablegrip.dev/schema/theme-v1.json",
  "name": "Mine",
  "themes": [
    {
      "name": "Mine Dark",
      "appearance": "dark",
      // Just the keys that differ
      "colors": { "accent": "#FF8800", "some.future.key": "#000000" },
      "syntax": { "keyword": "#C678DD", "comment": { "color": "#5C6370", "fontStyle": "italic" } },
    },
  ],
}"##,
    )
    .unwrap();
    let dark = &family.themes[0];
    assert!(dark.is_dark());
    assert_eq!(dark.colors["accent"], "#FF8800");
    assert_eq!(dark.syntax["keyword"], SyntaxStyle::color("#C678DD"));
    assert_eq!(
        dark.syntax["comment"],
        SyntaxStyle {
            color: Some("#5C6370".into()),
            font_style: Some("italic".into()),
            font_weight: None
        }
    );
    // Writing keeps the short form for plain colours.
    let encoded = ThemeJson::encode(&family);
    assert_eq!(ThemeJson::parse(&encoded).unwrap(), family);
    assert!(encoded.contains("\"keyword\": \"#C678DD\""));
}

#[test]
fn overrides_lay_keys_over_a_variant() {
    let spec = ThemeSpec {
        name: "One Dark".into(),
        appearance: "dark".into(),
        colors: map(&[
            ("accent", "#4D78CC".to_string()),
            ("text", "#ABB2BF".to_string()),
        ]),
        syntax: map(&[("keyword", SyntaxStyle::color("#C678DD"))]),
        chart: vec![],
    };
    let merged = spec.with(Some(&ThemeOverride {
        colors: map(&[("accent", "#E5C07B".to_string())]),
        syntax: map(&[("string", SyntaxStyle::color("#00FF00"))]),
        chart: vec![],
    }));
    assert_eq!(
        merged.colors,
        map(&[
            ("accent", "#E5C07B".to_string()),
            ("text", "#ABB2BF".to_string())
        ])
    );
    assert_eq!(
        merged.syntax.keys().collect::<Vec<_>>(),
        ["keyword", "string"]
    );
}

#[test]
fn a_broken_file_in_the_themes_folder_is_reported_the_others_still_load() {
    let dir = tempfile::tempdir().unwrap();
    fs::write(
        dir.path().join("good.json"),
        r#"{ "name": "Good", "themes": [ { "name": "Good Light", "appearance": "light" } ] }"#,
    )
    .unwrap();
    fs::write(
        dir.path().join("bad.json"),
        r#"{ "name": "Bad", "themes": [ "#,
    )
    .unwrap();
    fs::write(dir.path().join("notes.txt"), "ignored").unwrap();
    let files = ThemeJson::load_directory(dir.path());
    assert_eq!(
        files
            .families
            .iter()
            .map(|f| f.name.as_str())
            .collect::<Vec<_>>(),
        ["Good"]
    );
    assert_eq!(
        files
            .errors
            .iter()
            .map(|e| e.file.file_name().unwrap().to_str().unwrap())
            .collect::<Vec<_>>(),
        ["bad.json"]
    );
}

#[test]
fn backup_schedules_are_due_after_their_interval_and_missed_runs_catch_up() {
    let now: i64 = 1_793_440_800_000; // 2026-10-31T10:00:00Z
    let daily = BackupSchedule {
        frequency: BackupFrequency::Daily,
        ..BackupSchedule::new("a", "c")
    };
    assert!(daily.is_due(now), "never ran");
    let ran = |secs_ago: i64| BackupSchedule {
        last_run: Some(now - secs_ago * 1000),
        ..daily.clone()
    };
    assert!(!ran(3_600).is_due(now));
    assert!(ran(3 * 86_400).is_due(now), "missed while closed");
    assert!(
        !BackupSchedule {
            enabled: false,
            ..daily
        }
        .is_due(now)
    );
}

#[test]
fn pruning_touches_only_the_schedules_own_oldest_backups() {
    let dir = tempfile::tempdir().unwrap();
    let schedule = BackupSchedule {
        keep: 2,
        ..BackupSchedule::new("a", "c")
    };
    for name in [
        "scheduled-a-20261028",
        "scheduled-a-20261029",
        "scheduled-a-20261030",
        "scheduled-b-20261001",
        "manual.dump",
    ] {
        fs::write(dir.path().join(name), "x").unwrap();
    }
    let deleted = BackupSchedule::prune(dir.path(), &schedule);
    assert_eq!(
        deleted
            .iter()
            .map(|p| p.file_name().unwrap().to_str().unwrap())
            .collect::<Vec<_>>(),
        ["scheduled-a-20261028"]
    );
    let mut left: Vec<String> = fs::read_dir(dir.path())
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    left.sort();
    assert_eq!(
        left,
        [
            "manual.dump",
            "scheduled-a-20261029",
            "scheduled-a-20261030",
            "scheduled-b-20261001"
        ]
    );
}

#[test]
fn settings_this_version_doesnt_know_are_ignored() {
    let dir = tempfile::tempdir().unwrap();
    let store = store(&dir);
    fs::write(
        store.file(),
        r#"{ "futureSetting": { "a": 1 }, "pageSize": 7 }"#,
    )
    .unwrap();
    let loaded = store.load();
    assert_eq!(store.load_error(), None);
    assert_eq!(loaded.page_size, 7);
}
