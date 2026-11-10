//! What every popup shares: the surface colours (measured from the Kotlin app, themed ones from the theme's
//! `controls`), the border, rounded corners and drop shadow, key mapping, and where a popup goes at the window's edge.

use gpui::{BoxShadow, Div, Pixels, Point, Size, div, point, prelude::*};
use tg_theme::{Rgba, Theme};
use tg_ui_logic::input::{Input, Key, Modifiers};

use crate::text::TextColor;
use crate::theme::{ActiveTheme, hsla};

pub const BORDER: f32 = 1.0;
pub const RADIUS: f32 = 8.0;

/// The colours of a popup surface and the highlight of its items.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PopupColors {
    pub background: Rgba,
    pub border: Rgba,
    pub separator: Rgba,
    /// The row under the pointer or the keyboard highlight.
    pub highlight: Rgba,
    pub text: TextColor,
    pub text_disabled: TextColor,
    pub shadow: Rgba,
}

pub fn popup_colors(theme: &Theme) -> PopupColors {
    let dark = theme.is_dark();
    let pick = |d: u32, l: u32| Rgba::rgb(if dark { d } else { l });
    let shadow = if dark {
        Rgba::argb(0x66000000)
    } else {
        Rgba::argb(0x29000000)
    };
    match &theme.colors.controls {
        Some(c) => PopupColors {
            background: c.popup_background,
            border: c.popup_border,
            separator: c.popup_border,
            highlight: c.selection,
            text: TextColor::Custom(c.text),
            text_disabled: TextColor::Custom(c.text_disabled),
            shadow,
        },
        None => PopupColors {
            background: pick(0x2B2D30, 0xFFFFFF),
            border: pick(0x393B40, 0xC9CCD6),
            separator: pick(0x393B40, 0xEBECF0),
            highlight: pick(0x2E436E, 0xD4E2FF),
            text: TextColor::Normal,
            // Int UI's menu greys, not the theme's general disabled text.
            text_disabled: TextColor::Custom(pick(0x6F737A, 0xA8ADBD)),
            shadow,
        },
    }
}

/// The surface: border, rounded corners, shadow. Content goes inside, past its own padding.
pub fn surface(t: &ActiveTheme, c: &PopupColors) -> Div {
    div()
        .flex()
        .flex_col()
        .flex_none()
        .border(t.dp(BORDER))
        .border_color(hsla(c.border))
        .rounded(t.dp(RADIUS))
        .bg(hsla(c.background))
        .shadow(vec![BoxShadow {
            color: hsla(c.shadow),
            offset: point(Pixels::ZERO, t.dp(6.0)),
            blur_radius: t.dp(10.0),
            spread_radius: Pixels::ZERO,
        }])
}

/// The keys popups act on.
pub(crate) fn nav_input(event: &gpui::KeyDownEvent) -> Option<Input> {
    let key = match event.keystroke.key.as_str() {
        "space" => Key::Space,
        "enter" => Key::Enter,
        "escape" => Key::Escape,
        "up" => Key::Up,
        "down" => Key::Down,
        _ => return None,
    };
    let m = &event.keystroke.modifiers;
    Some(Input::Key(
        key,
        Modifiers {
            primary: if cfg!(target_os = "macos") {
                m.platform
            } else {
                m.control
            },
            shift: m.shift,
            alt: m.alt,
        },
    ))
}

/// Where a popup of `size` opens from a trigger at `anchor` (origin and size, in the window's coordinates): below
/// the trigger, left edges aligned, unless that would run off the bottom and there is more room above, then above it.
/// The result is kept inside the window horizontally. `gap` is the space left between trigger and popup.
pub fn place(
    anchor: (Point<Pixels>, Size<Pixels>),
    size: Size<Pixels>,
    window: Size<Pixels>,
    gap: Pixels,
) -> (Point<Pixels>, bool) {
    let (origin, anchor_size) = anchor;
    let below = origin.y + anchor_size.height + gap;
    let above = origin.y - gap - size.height;
    let fits_below = below + size.height <= window.height;
    let flip = !fits_below && above >= Pixels::ZERO;
    let y = if flip { above } else { below };
    let x = origin.x.min(window.width - size.width).max(Pixels::ZERO);
    (point(x, y), flip)
}

#[cfg(test)]
mod tests {
    use super::*;
    use gpui::{px, size};

    fn anchor(y: f32) -> (Point<Pixels>, Size<Pixels>) {
        (point(px(10.0), px(y)), size(px(80.0), px(20.0)))
    }

    #[test]
    fn opens_below_when_it_fits() {
        let (p, flipped) = place(
            anchor(10.0),
            size(px(100.0), px(60.0)),
            size(px(400.0), px(300.0)),
            px(2.0),
        );
        assert!(!flipped);
        assert_eq!(p, point(px(10.0), px(32.0)));
    }

    #[test]
    fn flips_above_at_the_bottom_edge() {
        let (p, flipped) = place(
            anchor(260.0),
            size(px(100.0), px(60.0)),
            size(px(400.0), px(300.0)),
            px(2.0),
        );
        assert!(flipped);
        assert_eq!(p.y, px(198.0));
    }

    #[test]
    fn slides_in_from_the_right_edge() {
        let a = (point(px(380.0), px(10.0)), size(px(10.0), px(20.0)));
        let (p, _) = place(
            a,
            size(px(100.0), px(60.0)),
            size(px(400.0), px(300.0)),
            px(0.0),
        );
        assert_eq!(p.x, px(300.0));
    }
}
