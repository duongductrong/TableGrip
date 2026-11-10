//! Theme files and the overrides settings lay over them (`theme/ThemeFiles.kt`). Colours stay text, as in Kotlin: the
//! app resolves them.

use crate::json::{Baseline, first_line, from_lenient_str, omit_defaults, to_pretty};
use indexmap::IndexMap;
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::path::{Path, PathBuf};

/// A theme family as one JSON file, shaped after Zed's theme files.
#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThemeFamily {
    pub name: String,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub license: String,
    #[serde(default)]
    pub themes: Vec<ThemeSpec>,
}

impl Baseline for ThemeFamily {
    fn baseline(&self) -> Self {
        Self {
            name: self.name.clone(),
            author: String::new(),
            license: String::new(),
            themes: Vec::new(),
        }
    }
}
omit_defaults!(ThemeFamily {
    name: "name" = req,
    author: "author",
    license: "license",
    themes: "themes"
});

/// One variant of a [`ThemeFamily`]; settings select it by its `name`.
#[derive(Clone, Debug, PartialEq, Deserialize)]
pub struct ThemeSpec {
    pub name: String,
    /// `dark` or `light`.
    #[serde(default = "dark")]
    pub appearance: String,
    #[serde(default)]
    pub colors: IndexMap<String, String>,
    /// SQL token styles by token key, each a colour or an object.
    #[serde(default)]
    pub syntax: IndexMap<String, SyntaxStyle>,
    /// Series colours for charts, most distinct first.
    #[serde(default)]
    pub chart: Vec<String>,
}

fn dark() -> String {
    "dark".to_string()
}

impl Baseline for ThemeSpec {
    fn baseline(&self) -> Self {
        Self {
            name: self.name.clone(),
            appearance: dark(),
            colors: IndexMap::new(),
            syntax: IndexMap::new(),
            chart: Vec::new(),
        }
    }
}
omit_defaults!(ThemeSpec {
    name: "name" = req,
    appearance: "appearance",
    colors: "colors",
    syntax: "syntax",
    chart: "chart"
});

impl ThemeSpec {
    pub fn is_dark(&self) -> bool {
        !self.appearance.eq_ignore_ascii_case("light")
    }

    /// This variant with `over`'s keys laid over its own.
    pub fn with(&self, over: Option<&ThemeOverride>) -> ThemeSpec {
        let Some(over) = over else {
            return self.clone();
        };
        let mut colors = self.colors.clone();
        colors.extend(over.colors.clone());
        let mut syntax = self.syntax.clone();
        syntax.extend(over.syntax.clone());
        ThemeSpec {
            colors,
            syntax,
            chart: if over.chart.is_empty() {
                self.chart.clone()
            } else {
                over.chart.clone()
            },
            ..self.clone()
        }
    }
}

/// A token's look; None parts keep the theme's. Read from a bare colour string or an object, written as the string
/// when only the colour is set.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SyntaxStyle {
    pub color: Option<String>,
    /// `normal` or `italic`.
    pub font_style: Option<String>,
    /// `normal` or `bold`.
    pub font_weight: Option<String>,
}

impl SyntaxStyle {
    pub fn color(color: &str) -> Self {
        Self {
            color: Some(color.to_string()),
            ..Self::default()
        }
    }
}

impl Baseline for SyntaxStyle {
    fn baseline(&self) -> Self {
        Self::default()
    }
}

impl Serialize for SyntaxStyle {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        if let (Some(color), None, None) = (&self.color, &self.font_style, &self.font_weight) {
            return serializer.serialize_str(color);
        }
        let mut map = serializer.serialize_map(None)?;
        if let Some(color) = &self.color {
            map.serialize_entry("color", color)?;
        }
        if let Some(style) = &self.font_style {
            map.serialize_entry("fontStyle", style)?;
        }
        if let Some(weight) = &self.font_weight {
            map.serialize_entry("fontWeight", weight)?;
        }
        map.end()
    }
}

impl<'de> Deserialize<'de> for SyntaxStyle {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(untagged)]
        enum Form {
            Color(String),
            #[serde(rename_all = "camelCase")]
            Object {
                #[serde(default)]
                color: Option<String>,
                #[serde(default)]
                font_style: Option<String>,
                #[serde(default)]
                font_weight: Option<String>,
            },
        }
        Ok(match Form::deserialize(deserializer)? {
            Form::Color(color) => SyntaxStyle::color(&color),
            Form::Object {
                color,
                font_style,
                font_weight,
            } => SyntaxStyle {
                color,
                font_style,
                font_weight,
            },
        })
    }
}

/// What settings lay over a theme by its name, like Zed's `theme_overrides`.
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(default)]
pub struct ThemeOverride {
    pub colors: IndexMap<String, String>,
    pub syntax: IndexMap<String, SyntaxStyle>,
    pub chart: Vec<String>,
}

impl Baseline for ThemeOverride {
    fn baseline(&self) -> Self {
        Self::default()
    }
}
omit_defaults!(ThemeOverride {
    colors: "colors",
    syntax: "syntax",
    chart: "chart"
});

/// A theme file that couldn't be read, kept so Settings can say what is wrong with it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ThemeFileError {
    pub file: PathBuf,
    pub message: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ThemeFiles {
    pub families: Vec<ThemeFamily>,
    pub errors: Vec<ThemeFileError>,
}

/// Lenient the way editors' JSON is: comments and trailing commas are fine, unknown keys are ignored.
pub struct ThemeJson;

impl ThemeJson {
    pub fn parse(text: &str) -> Result<ThemeFamily, serde_json::Error> {
        from_lenient_str(text)
    }

    pub fn encode(family: &ThemeFamily) -> String {
        to_pretty(family, "  ").expect("a theme family is JSON")
    }

    /// Every `*.json` in `dir`, in name order; a file that fails to parse is reported and skipped, never fatal.
    pub fn load_directory(dir: &Path) -> ThemeFiles {
        let mut files = ThemeFiles::default();
        if !dir.is_dir() {
            return files;
        }
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
        paths.sort_by_key(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()));
        for file in paths {
            let parsed = std::fs::read_to_string(&file)
                .map_err(|e| e.to_string())
                .and_then(|text| Self::parse(&text).map_err(|e| first_line(&e.to_string())));
            match parsed {
                Ok(family) if family.themes.is_empty() => files.errors.push(ThemeFileError {
                    file,
                    message: "No themes in \"themes\"".to_string(),
                }),
                Ok(family) => files.families.push(family),
                Err(message) => files.errors.push(ThemeFileError { file, message }),
            }
        }
        files
    }
}
