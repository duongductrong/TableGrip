//! Theme files as written: a family of variants, each a flat map of dotted colour keys, shaped after Zed's theme
//! files. Colours stay text here, as in the Kotlin `core` module; [crate::resolve] turns them into colours.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Deserializer};

/// A theme family as one JSON file: several variants (usually a dark and a light one) that share a name.
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
pub struct ThemeFamily {
    pub name: String,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub license: String,
    #[serde(default)]
    pub themes: Vec<ThemeSpec>,
}

/// One variant of a [ThemeFamily]; its [name](Self::name) is what settings select it by, e.g. `One Dark`.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct ThemeSpec {
    pub name: String,
    /// `dark` or `light`.
    #[serde(default = "dark")]
    pub appearance: String,
    #[serde(default)]
    pub colors: BTreeMap<String, String>,
    /// SQL token styles by token key (`keyword`, `string`…).
    #[serde(default)]
    pub syntax: BTreeMap<String, SyntaxStyle>,
    /// Series colours for charts, most distinct first.
    #[serde(default)]
    pub chart: Vec<String>,
}

fn dark() -> String {
    "dark".to_owned()
}

impl ThemeSpec {
    /// A variant with no keys, as the Kotlin registry makes when nothing else is found.
    pub fn empty(name: &str, dark: bool) -> ThemeSpec {
        ThemeSpec {
            name: name.to_owned(),
            appearance: if dark { "dark" } else { "light" }.to_owned(),
            colors: BTreeMap::new(),
            syntax: BTreeMap::new(),
            chart: Vec::new(),
        }
    }

    /// Anything but `light` (any case) is dark.
    pub fn is_dark(&self) -> bool {
        !self.appearance.eq_ignore_ascii_case("light")
    }

    /// This variant with [over]'s keys laid over its own.
    pub fn with(&self, over: Option<&ThemeOverride>) -> ThemeSpec {
        let Some(over) = over else {
            return self.clone();
        };
        let mut spec = self.clone();
        spec.colors
            .extend(over.colors.iter().map(|(k, v)| (k.clone(), v.clone())));
        spec.syntax
            .extend(over.syntax.iter().map(|(k, v)| (k.clone(), v.clone())));
        if !over.chart.is_empty() {
            spec.chart = over.chart.clone();
        }
        spec
    }
}

/// A token's look; None parts keep the theme's. Written either as a bare colour or as an object.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SyntaxStyle {
    pub color: Option<String>,
    /// `normal` or `italic`.
    pub font_style: Option<String>,
    /// `normal` or `bold`.
    pub font_weight: Option<String>,
}

impl<'de> Deserialize<'de> for SyntaxStyle {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct Full {
            color: Option<String>,
            font_style: Option<String>,
            font_weight: Option<String>,
        }
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Either {
            Color(String),
            Full(Full),
        }
        Ok(match Either::deserialize(deserializer)? {
            Either::Color(color) => SyntaxStyle {
                color: Some(color),
                ..SyntaxStyle::default()
            },
            Either::Full(f) => SyntaxStyle {
                color: f.color,
                font_style: f.font_style,
                font_weight: f.font_weight,
            },
        })
    }
}

/// What settings lay over a theme by its name (`themeOverrides`).
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(default)]
pub struct ThemeOverride {
    pub colors: BTreeMap<String, String>,
    pub syntax: BTreeMap<String, SyntaxStyle>,
    pub chart: Vec<String>,
}

/// A theme file that couldn't be read, kept so settings can say what is wrong with it.
#[derive(Clone, Debug, PartialEq)]
pub struct ThemeFileError {
    pub file: PathBuf,
    pub message: String,
}

/// The user's theme files as last read.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ThemeFiles {
    pub families: Vec<ThemeFamily>,
    pub errors: Vec<ThemeFileError>,
}

/// Parses a theme family, lenient the way editors' JSON is: comments and trailing commas are fine, unknown keys are
/// ignored.
pub fn parse_family(text: &str) -> Result<ThemeFamily, String> {
    serde_json::from_str(&crate::lenient::strip(text)).map_err(|e| e.to_string())
}

/// Every `*.json` in [dir], in name order. A file that fails to parse, or has no themes, is reported in
/// [ThemeFiles::errors] and skipped, never fatal. A missing directory is no themes.
pub fn load_directory(dir: &Path) -> ThemeFiles {
    let mut files = ThemeFiles::default();
    let Ok(entries) = std::fs::read_dir(dir) else {
        return files;
    };
    let mut paths: Vec<PathBuf> = entries
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.extension()
                .is_some_and(|e| e.eq_ignore_ascii_case("json"))
        })
        .collect();
    paths.sort_by_key(|p| p.file_name().map(|n| n.to_os_string()));
    for path in paths {
        let parsed = std::fs::read_to_string(&path)
            .map_err(|e| e.to_string())
            .and_then(|text| parse_family(&text));
        match parsed {
            Ok(family) if family.themes.is_empty() => files.errors.push(ThemeFileError {
                file: path,
                message: "No themes in \"themes\"".to_owned(),
            }),
            Ok(family) => files.families.push(family),
            Err(message) => files.errors.push(ThemeFileError {
                file: path,
                message,
            }),
        }
    }
    files
}
