//! Every theme the app knows: the built-in families, read straight from the Kotlin app's resources at compile time,
//! then the user's own files. A user theme with a built-in theme's name replaces it.

use std::path::Path;

use crate::resolve::{ColorTheme, resolve_theme};
use crate::spec::{
    ThemeFamily, ThemeFileError, ThemeFiles, ThemeOverride, ThemeSpec, load_directory, parse_family,
};

/// Graphite's two variants, the defaults and the base other themes inherit from.
pub const DEFAULT_DARK_THEME: &str = "Graphite Dark";
pub const DEFAULT_LIGHT_THEME: &str = "Graphite Light";

macro_rules! built_in {
    ($($name:literal),* $(,)?) => {
        [$(($name, include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../resources/themes/", $name, ".json")))),*]
    };
}

/// The built-in families in the order the pickers list them, as the files in `resources/themes`.
const BUILT_IN: [(&str, &str); 6] = built_in!(
    "graphite",
    "vscode-modern",
    "dracula",
    "one",
    "catppuccin",
    "contrast"
);

/// A theme variant and the family it came from.
#[derive(Clone, Debug, PartialEq)]
pub struct ThemeEntry<'a> {
    pub spec: &'a ThemeSpec,
    pub family: &'a ThemeFamily,
    pub built_in: bool,
}

/// The built-in themes plus the user's theme files.
#[derive(Clone, Debug)]
pub struct Registry {
    built_in: Vec<ThemeFamily>,
    user: ThemeFiles,
}

impl Default for Registry {
    fn default() -> Self {
        Self::new()
    }
}

impl Registry {
    /// The built-in themes only.
    pub fn new() -> Registry {
        let built_in = BUILT_IN
            .iter()
            .map(|(name, text)| {
                parse_family(text).unwrap_or_else(|e| panic!("built-in theme {name}.json: {e}"))
            })
            .collect();
        Registry {
            built_in,
            user: ThemeFiles::default(),
        }
    }

    pub fn built_in(&self) -> &[ThemeFamily] {
        &self.built_in
    }

    /// Reads the user's theme folder (`~/.tablegrip/themes`, or [dir]) again; bad files land in [Self::errors].
    pub fn reload_user_themes(&mut self, dir: &Path) {
        self.user = load_directory(dir);
    }

    pub fn errors(&self) -> &[ThemeFileError] {
        &self.user.errors
    }

    /// Every variant, built-ins first, user files over built-ins of the same name.
    pub fn entries(&self) -> Vec<ThemeEntry<'_>> {
        let user: Vec<ThemeEntry> = self
            .user
            .families
            .iter()
            .flat_map(|f| {
                f.themes.iter().map(move |spec| ThemeEntry {
                    spec,
                    family: f,
                    built_in: false,
                })
            })
            .collect();
        let mut all: Vec<ThemeEntry> = self
            .built_in
            .iter()
            .flat_map(|f| {
                f.themes.iter().map(move |spec| ThemeEntry {
                    spec,
                    family: f,
                    built_in: true,
                })
            })
            .filter(|e| !user.iter().any(|u| u.spec.name == e.spec.name))
            .collect();
        all.extend(user);
        all
    }

    /// Names of the variants for dark or light mode, for the theme pickers.
    pub fn names(&self, dark: bool) -> Vec<String> {
        self.entries()
            .into_iter()
            .filter(|e| e.spec.is_dark() == dark)
            .map(|e| e.spec.name.clone())
            .collect()
    }

    pub fn entry(&self, name: &str) -> Option<ThemeEntry<'_>> {
        self.entries().into_iter().find(|e| e.spec.name == name)
    }

    /// The theme named [name] with [over] laid over it, resolved. An unknown name, or one of the other appearance,
    /// falls back to Graphite of the mode, so a typo in settings never leaves the app unstyled.
    pub fn resolve(&self, name: &str, dark: bool, over: Option<&ThemeOverride>) -> ColorTheme {
        let default = if dark {
            DEFAULT_DARK_THEME
        } else {
            DEFAULT_LIGHT_THEME
        };
        let entry = self
            .entry(name)
            .filter(|e| e.spec.is_dark() == dark)
            .or_else(|| self.entry(default));
        let spec = entry
            .as_ref()
            .map(|e| e.spec.clone())
            .unwrap_or_else(|| ThemeSpec::empty(name, dark))
            .with(over);
        let graphite = if spec.is_dark() {
            DEFAULT_DARK_THEME
        } else {
            DEFAULT_LIGHT_THEME
        };
        let base = self
            .entry(graphite)
            .map(|e| e.spec)
            .filter(|b| b.name != spec.name);
        let family = entry.as_ref().map(|e| e.family.name.as_str()).unwrap_or("");
        resolve_theme(&spec, family, base)
    }
}
