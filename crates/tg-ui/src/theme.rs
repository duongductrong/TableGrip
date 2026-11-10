//! The theme in use, as a GPUI global, and the conversions every component needs: tokens to GPUI colours and dp to
//! pixels at the current UI zoom.

use std::sync::Arc;

use gpui::{App, Global, Hsla, Pixels, SharedString, px};
use tg_theme::{Rgba, Theme};

/// The bundled UI font the Kotlin app draws with (Int UI's).
pub const UI_FONT: &str = "Inter";
/// Used when [UI_FONT] isn't available.
pub const UI_FONT_FALLBACK: &str = ".SystemUIFont";
/// Used when neither the editor font from settings nor JetBrains Mono is available.
pub const EDITOR_FONT_FALLBACK: &str = if cfg!(target_os = "macos") {
    "Menlo"
} else if cfg!(target_os = "windows") {
    "Consolas"
} else {
    "DejaVu Sans Mono"
};

/// The theme every component draws with, plus the font families actually available on this machine.
#[derive(Clone)]
pub struct ActiveTheme {
    pub theme: Arc<Theme>,
    pub ui_font: SharedString,
    pub editor_font: SharedString,
}

impl Global for ActiveTheme {}

impl ActiveTheme {
    /// [dp] logical pixels at the current zoom; the window's own scale factor applies on top.
    pub fn dp(&self, dp: f32) -> Pixels {
        px(dp * self.zoom())
    }

    /// The zoom factor (0.5–3.0); it multiplies every size, as Compose's density does in the Kotlin app.
    pub fn zoom(&self) -> f32 {
        tg_theme::zoom::factor(self.theme.zoom)
    }
}

/// Installs [theme] for every window, resolving fonts against those the text system has.
pub fn set_theme(cx: &mut App, theme: Theme) {
    let names = cx.text_system().all_font_names();
    let has = |name: &str| names.iter().any(|n| n == name);
    let ui_font = if has(UI_FONT) {
        UI_FONT
    } else {
        UI_FONT_FALLBACK
    };
    let editor_font = [
        theme.fonts.editor_family.as_str(),
        tg_theme::ui::DEFAULT_EDITOR_FONT,
    ]
    .into_iter()
    .find(|f| has(f))
    .unwrap_or(EDITOR_FONT_FALLBACK)
    .to_owned();
    cx.set_global(ActiveTheme {
        theme: Arc::new(theme),
        ui_font: ui_font.into(),
        editor_font: editor_font.into(),
    });
    cx.refresh_windows();
}

/// Access to the [ActiveTheme] from anything that can read globals.
pub trait ThemeExt {
    fn theme(&self) -> &ActiveTheme;
}

impl ThemeExt for App {
    fn theme(&self) -> &ActiveTheme {
        self.global::<ActiveTheme>()
    }
}

/// A theme colour as GPUI's.
pub fn hsla(color: Rgba) -> Hsla {
    gpui::Rgba {
        r: color.r as f32 / 255.0,
        g: color.g as f32 / 255.0,
        b: color.b as f32 / 255.0,
        a: color.a as f32 / 255.0,
    }
    .into()
}
