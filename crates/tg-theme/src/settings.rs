//! The part of the app's `settings.json` that decides how the UI looks.

use std::collections::BTreeMap;

use serde::{Deserialize, Deserializer};

use crate::color::{parse_hex_color, readable_on};
use crate::registry::{DEFAULT_DARK_THEME, DEFAULT_LIGHT_THEME, Registry};
use crate::spec::ThemeOverride;
use crate::ui::Theme;

/// Light or dark (or the OS's choice), and the theme used for each.
#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(default)]
pub struct ThemeSelection {
    /// `System`, `Light` or `Dark`.
    pub mode: String,
    pub dark: String,
    pub light: String,
}

impl Default for ThemeSelection {
    fn default() -> Self {
        ThemeSelection {
            mode: "System".to_owned(),
            dark: DEFAULT_DARK_THEME.to_owned(),
            light: DEFAULT_LIGHT_THEME.to_owned(),
        }
    }
}

/// A bare string, as older settings files write it, is the mode.
fn selection<'de, D: Deserializer<'de>>(deserializer: D) -> Result<ThemeSelection, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Either {
        Mode(String),
        Full(ThemeSelection),
    }
    Ok(match Either::deserialize(deserializer)? {
        Either::Mode(mode) => ThemeSelection {
            mode,
            ..ThemeSelection::default()
        },
        Either::Full(s) => s,
    })
}

/// Appearance settings, with the app's defaults; unknown keys in `settings.json` are ignored.
#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AppearanceSettings {
    #[serde(deserialize_with = "selection")]
    pub theme: ThemeSelection,
    /// `#RRGGBB` or `#AARRGGBB`; blank keeps the theme's accent.
    pub accent_color: String,
    /// Keys laid over a theme, by theme name.
    pub theme_overrides: BTreeMap<String, ThemeOverride>,
    /// Points for labels and controls; 0 means the default (13).
    pub ui_font_size: i32,
    /// Clamped to 0–24 dp.
    pub island_corner_radius: i32,
    /// Percent; snapped to [crate::zoom::LEVELS].
    pub zoom: i32,
    /// A font installed on this machine; blank means the bundled JetBrains Mono.
    pub editor_font_family: String,
    pub editor_font_size: i32,
}

impl Default for AppearanceSettings {
    fn default() -> Self {
        AppearanceSettings {
            theme: ThemeSelection::default(),
            accent_color: String::new(),
            theme_overrides: BTreeMap::new(),
            ui_font_size: 0,
            island_corner_radius: 10,
            zoom: 100,
            editor_font_family: String::new(),
            editor_font_size: 13,
        }
    }
}

impl AppearanceSettings {
    /// The theme this mode uses, with the user's overrides for it and their accent colour, plus fonts and metrics.
    pub fn theme(&self, registry: &Registry, dark: bool) -> Theme {
        let name = if dark {
            &self.theme.dark
        } else {
            &self.theme.light
        };
        let accent = Some(self.accent_color.trim())
            .filter(|a| !a.is_empty())
            .and_then(parse_hex_color);
        let mut over = self.theme_overrides.get(name).cloned();
        if let Some(accent) = accent {
            let o = over.get_or_insert_with(ThemeOverride::default);
            o.colors.insert("accent".to_owned(), accent.to_hex());
            o.colors
                .insert("accent.foreground".to_owned(), readable_on(accent).to_hex());
        }
        let colors = registry.resolve(name, dark, over.as_ref());
        Theme::new(colors, self)
    }
}
