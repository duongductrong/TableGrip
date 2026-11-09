//! The colours the Phase 2 controls draw with, derived from the theme the way the Kotlin app ends up with them.
//!
//! A theme with a `controls` section replaces the button styles wholesale (`ThemedControls.kt`); without one
//! (Graphite) the buttons keep Int UI's own colours, which are recorded here from what the Kotlin app draws. Boxes
//! (checkbox, radio) and icon buttons are never themed: their colours follow only light or dark, plus the accent.

use tg_theme::{Rgba, Theme};

pub use tg_ui_logic::button::ButtonVariant;

pub const TRANSPARENT: Rgba = Rgba::argb(0x00000000);

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ButtonColors {
    pub fill: Rgba,
    pub fill_hovered: Rgba,
    pub fill_pressed: Rgba,
    pub border: Rgba,
    pub border_hovered: Rgba,
    /// The border while focused (or pressed, which takes focus). The outline variant draws its ring here instead.
    pub border_focused: Rgba,
    pub border_disabled: Rgba,
    pub text: Rgba,
    pub text_disabled: Rgba,
    pub ring: Rgba,
}

pub fn button_colors(theme: &Theme, variant: ButtonVariant) -> ButtonColors {
    let dark = theme.is_dark();
    let pick = |dark_hex: u32, light_hex: u32| Rgba::rgb(if dark { dark_hex } else { light_hex });
    let islands = &theme.colors.islands;
    let ui = &theme.ui;
    let themed = theme.colors.controls.as_ref();
    match (variant, themed) {
        (ButtonVariant::Default, Some(c)) => ButtonColors {
            fill: c.button_background,
            fill_hovered: c.button_hover,
            fill_pressed: c.button_pressed,
            border: c.button_background,
            border_hovered: c.button_hover,
            border_focused: c.focus,
            border_disabled: c.secondary_border,
            text: c.button_foreground,
            text_disabled: c.text_disabled,
            ring: ui.outline_focused,
        },
        (ButtonVariant::Default, None) => ButtonColors {
            fill: islands.accent,
            fill_hovered: pick(0x366ACE, 0x3369D6),
            fill_pressed: pick(0x375FAD, 0x315FBD),
            border: islands.accent,
            border_hovered: islands.accent,
            border_focused: islands.island,
            border_disabled: ui.border_disabled,
            text: islands.accent_foreground,
            text_disabled: ui.text_disabled,
            ring: ui.outline_focused,
        },
        (ButtonVariant::Outlined, Some(c)) => ButtonColors {
            fill: TRANSPARENT,
            fill_hovered: c.hover,
            fill_pressed: c.hover,
            border: c.secondary_border,
            border_hovered: c.text_muted,
            border_focused: ui.outline_focused,
            border_disabled: c
                .secondary_border
                .with_alpha(c.secondary_border.a as f32 / 255.0 * 0.6),
            text: c.text,
            text_disabled: c.text_disabled,
            ring: ui.outline_focused,
        },
        (ButtonVariant::Outlined, None) => ButtonColors {
            fill: TRANSPARENT,
            fill_hovered: TRANSPARENT,
            fill_pressed: pick(0x2B2D30, 0xF7F8FA),
            border: pick(0x4E5157, 0xC9CCD6),
            border_hovered: pick(0x6F737A, 0xA8ADBD),
            border_focused: ui.outline_focused,
            border_disabled: ui.border_disabled,
            text: ui.text,
            text_disabled: ui.text_disabled,
            ring: ui.outline_focused,
        },
    }
}

/// Checkbox and radio button colours.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BoxColors {
    /// The accent a checked box or selected radio button fills with.
    pub accent: Rgba,
    pub border: Rgba,
    pub fill: Rgba,
    pub border_disabled: Rgba,
    pub fill_disabled: Rgba,
    /// The check mark or dot of a disabled control.
    pub mark_disabled: Rgba,
    /// The dash of a disabled indeterminate box.
    pub dash_disabled: Rgba,
    pub mark: Rgba,
    pub error: Rgba,
    /// The ring around a filled box, Int UI's blue in every theme.
    pub ring: Rgba,
    /// The ring on the edge of an empty box follows the theme's accent instead.
    pub ring_empty: Rgba,
    pub label_disabled: Rgba,
    /// A disabled radio button's label, which is a little lighter than a checkbox's.
    pub radio_label_disabled: Rgba,
}

pub fn box_colors(theme: &Theme) -> BoxColors {
    let dark = theme.is_dark();
    let pick = |dark_hex: u32, light_hex: u32| Rgba::rgb(if dark { dark_hex } else { light_hex });
    BoxColors {
        accent: theme.colors.islands.accent,
        border: pick(0x5A5D63, 0xA8ADBD),
        fill: pick(0x2B2D30, 0xFFFFFF),
        border_disabled: pick(0x5A5D63, 0xDFE1E5),
        fill_disabled: pick(0x393B40, 0xF7F8FA),
        mark_disabled: pick(0x6F737A, 0xC9CCD6),
        dash_disabled: pick(0x868A91, 0xC9CCD6),
        mark: Rgba::WHITE,
        error: theme.ui.outline_error,
        // Int UI's blue in every theme, unlike the buttons' ring.
        ring: Rgba::rgb(0x3574F0),
        ring_empty: theme.colors.islands.accent,
        label_disabled: pick(0x6F737A, 0xA8ADBD),
        radio_label_disabled: pick(0x868A91, 0xA8ADBD),
    }
}

/// Icon button backgrounds.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct IconButtonColors {
    /// Hovered, and also focused.
    pub hovered: Rgba,
    pub pressed: Rgba,
}

pub fn icon_button_colors(theme: &Theme) -> IconButtonColors {
    let dark = theme.is_dark();
    let pick = |dark_hex: u32, light_hex: u32| Rgba::rgb(if dark { dark_hex } else { light_hex });
    IconButtonColors {
        hovered: pick(0x393B40, 0xEBECF0),
        pressed: pick(0x4E5157, 0xDFE1E5),
    }
}
