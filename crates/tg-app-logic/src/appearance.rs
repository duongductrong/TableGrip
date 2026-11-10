//! Theme, font sizes and zoom from `settings.json` (`AppTheme.kt`, `UiZoom.kt`). The look is read straight from the
//! file through `tg_theme::AppearanceSettings`, which knows the keys the UI kit draws with; zoom changes are written
//! back through `tg_core`'s settings store, keeping every other setting as it was.

use tg_core::settings::{Settings, SettingsStore};
use tg_theme::{AppearanceSettings, Registry, Theme};

/// The appearance part of `settings.json`; defaults when the file is missing or broken.
pub fn appearance(store: &SettingsStore) -> AppearanceSettings {
    store
        .read_text()
        .and_then(|text| tg_core::json::from_lenient_str::<AppearanceSettings>(&text).ok())
        .unwrap_or_default()
}

/// Whether the UI is dark: the mode's choice, or the OS's for `System`.
pub fn is_dark(mode: &str, system_dark: bool) -> bool {
    match mode {
        "Dark" => true,
        "Light" => false,
        _ => system_dark,
    }
}

/// The theme to draw with. User themes from `~/.tablegrip/themes` are read into `registry` by the caller.
pub fn theme(settings: &AppearanceSettings, registry: &Registry, system_dark: bool) -> Theme {
    settings.theme(registry, is_dark(&settings.theme.mode, system_dark))
}

/// Saves `percent` as the zoom, snapped to the levels the app offers. Returns the settings as saved; Ok(None) when
/// settings.json has an error to fix first (it is left alone, as Kotlin does).
pub fn save_zoom(store: &SettingsStore, percent: i32) -> std::io::Result<Option<Settings>> {
    let mut settings = store.load();
    if store.load_error().is_some() {
        return Ok(None);
    }
    settings.zoom = tg_theme::zoom::snap(percent);
    Ok(store.save(&settings)?.then_some(settings))
}
