//! What the controls actually draw with: the resolved theme where it sets a colour, Int UI's own colour where it
//! doesn't, plus fonts and metrics from the settings.

use crate::color::Rgba;
use crate::resolve::ColorTheme;
use crate::settings::AppearanceSettings;

/// Colours of text, borders, outlines and links as drawn. Token names in `golden/themes/<name>.json` are `ui.` plus
/// the field name in dotted form.
#[derive(Clone, Debug, PartialEq)]
pub struct UiColors {
    pub text: Rgba,
    pub text_info: Rgba,
    pub text_disabled: Rgba,
    pub text_error: Rgba,
    pub text_warning: Rgba,
    pub border: Rgba,
    pub border_focused: Rgba,
    pub border_disabled: Rgba,
    pub outline_focused: Rgba,
    pub outline_error: Rgba,
    pub outline_warning: Rgba,
    pub divider: Rgba,
    pub link: Rgba,
    pub link_disabled: Rgba,
    pub link_hovered: Rgba,
    pub link_pressed: Rgba,
    pub link_focused: Rgba,
    pub link_visited: Rgba,
}

/// Int UI's colours for the keys a theme may leave out; Graphite leaves out all of them.
///
/// The Rust side cannot derive these, so they are taken from what the Kotlin app draws with in Graphite, recorded in
/// `ui-kit/conformance/golden/themes/graphite-{dark,light}.json` (`ui`) and cross-checked against the pixel colours
/// in `golden/graphite-{dark,light}.json`. The token test checks them for every theme.
struct IntUi {
    text: u32,
    info: u32,
    disabled: u32,
    error: u32,
    warning: u32,
    border: u32,
    border_focused: u32,
    border_disabled: u32,
    outline_focused: u32,
    outline_error: u32,
    outline_warning: u32,
    link: u32,
    link_disabled: u32,
}

const INT_UI_DARK: IntUi = IntUi {
    text: 0xDFE1E5,
    info: 0x6F737A,
    disabled: 0x5A5D63,
    error: 0xDB5C5C,
    warning: 0xDFE1E5,
    border: 0x1E1F22,
    border_focused: 0x2B2D30,
    border_disabled: 0x43454A,
    outline_focused: 0x3574F0,
    outline_error: 0x472B2B,
    outline_warning: 0x5E4D33,
    link: 0x6B9BFA,
    link_disabled: 0x6F737A,
};

const INT_UI_LIGHT: IntUi = IntUi {
    text: 0x000000,
    info: 0x818594,
    disabled: 0xA8ADBD,
    error: 0xDB3B4B,
    warning: 0x000000,
    border: 0xEBECF0,
    border_focused: 0xFFFFFF,
    border_disabled: 0xDFE1E5,
    outline_focused: 0x3574F0,
    outline_error: 0xFAD4D8,
    outline_warning: 0xFEE6B1,
    link: 0x315FBD,
    link_disabled: 0xA8ADBD,
};

impl UiColors {
    pub fn of(theme: &ColorTheme) -> UiColors {
        let d = if theme.is_dark {
            &INT_UI_DARK
        } else {
            &INT_UI_LIGHT
        };
        let o = &theme.int_ui;
        let c = theme.controls.as_ref();
        let link = c.map(|c| c.link).unwrap_or(Rgba::rgb(d.link));
        UiColors {
            text: o.text.unwrap_or(Rgba::rgb(d.text)),
            text_info: o.text_muted.unwrap_or(Rgba::rgb(d.info)),
            text_disabled: o.text_disabled.unwrap_or(Rgba::rgb(d.disabled)),
            text_error: o.error.unwrap_or(Rgba::rgb(d.error)),
            text_warning: o.warning.unwrap_or(Rgba::rgb(d.warning)),
            border: o.border.unwrap_or(Rgba::rgb(d.border)),
            border_focused: o.focus.unwrap_or(Rgba::rgb(d.border_focused)),
            border_disabled: Rgba::rgb(d.border_disabled),
            outline_focused: o.focus.unwrap_or(Rgba::rgb(d.outline_focused)),
            outline_error: Rgba::rgb(d.outline_error),
            outline_warning: Rgba::rgb(d.outline_warning),
            divider: theme.islands.divider,
            link,
            link_disabled: c
                .map(|c| c.text_disabled)
                .unwrap_or(Rgba::rgb(d.link_disabled)),
            link_hovered: link,
            link_pressed: link,
            link_focused: link,
            link_visited: link,
        }
    }

    /// Every colour by its token name (`ui.text`, `ui.link.hovered`…).
    pub fn tokens(&self) -> Vec<(&'static str, Rgba)> {
        vec![
            ("ui.text", self.text),
            ("ui.text.info", self.text_info),
            ("ui.text.disabled", self.text_disabled),
            ("ui.text.error", self.text_error),
            ("ui.text.warning", self.text_warning),
            ("ui.border", self.border),
            ("ui.border.focused", self.border_focused),
            ("ui.border.disabled", self.border_disabled),
            ("ui.outline.focused", self.outline_focused),
            ("ui.outline.error", self.outline_error),
            ("ui.outline.warning", self.outline_warning),
            ("ui.divider", self.divider),
            ("ui.link", self.link),
            ("ui.link.disabled", self.link_disabled),
            ("ui.link.hovered", self.link_hovered),
            ("ui.link.pressed", self.link_pressed),
            ("ui.link.focused", self.link_focused),
            ("ui.link.visited", self.link_visited),
        ]
    }
}

/// The bundled editor font, used when the settings name none.
pub const DEFAULT_EDITOR_FONT: &str = "JetBrains Mono";

/// Text styles in logical pixels (dp) before zoom.
#[derive(Clone, Debug, PartialEq)]
pub struct Fonts {
    /// Labels and controls: `uiFontSize`, or 13.
    pub ui_size: f32,
    pub ui_line_height: f32,
    /// A font installed on this machine, or [DEFAULT_EDITOR_FONT].
    pub editor_family: String,
    /// The editor text style's size; the SQL editor itself uses [Self::sql_editor_size].
    pub editor_size: f32,
    pub editor_line_height: f32,
    /// `editorFontSize`, which the SQL editor draws with.
    pub sql_editor_size: f32,
}

impl Fonts {
    pub fn of(settings: &AppearanceSettings) -> Fonts {
        let ui_size = if settings.ui_font_size > 0 {
            settings.ui_font_size as f32
        } else {
            13.0
        };
        let family = settings.editor_font_family.trim();
        Fonts {
            ui_size,
            // Int UI's default style keeps 4 points of leading at every size (13/17, 15/19).
            ui_line_height: ui_size + 4.0,
            editor_family: if family.is_empty() {
                DEFAULT_EDITOR_FONT.to_owned()
            } else {
                family.to_owned()
            },
            editor_size: 13.0,
            editor_line_height: 18.0,
            sql_editor_size: settings.editor_font_size as f32,
        }
    }
}

/// Everything a component needs to draw: colours, the effective UI colours, fonts and metrics.
#[derive(Clone, Debug, PartialEq)]
pub struct Theme {
    pub colors: ColorTheme,
    pub ui: UiColors,
    pub fonts: Fonts,
    /// Island corner radius in dp, `islandCornerRadius` clamped to 0–24.
    pub island_radius: f32,
    /// Percent, one of [crate::zoom::LEVELS].
    pub zoom: i32,
}

impl Theme {
    pub fn new(colors: ColorTheme, settings: &AppearanceSettings) -> Theme {
        Theme {
            ui: UiColors::of(&colors),
            colors,
            fonts: Fonts::of(settings),
            island_radius: settings.island_corner_radius.clamp(0, 24) as f32,
            zoom: crate::zoom::snap(settings.zoom),
        }
    }

    pub fn is_dark(&self) -> bool {
        self.colors.is_dark
    }
}
