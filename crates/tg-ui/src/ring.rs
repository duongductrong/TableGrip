//! The focus (and error) ring: an overlay drawn around a control's bounds that takes no room in the layout, so
//! focusing a control never moves its neighbours.

use gpui::{Div, Pixels, div, prelude::*};
use tg_theme::Rgba;

use crate::theme::{ActiveTheme, hsla};

/// Where a ring sits relative to the control's bounds, in dp, as measured from the Kotlin app.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RingSpec {
    /// Distance from the bounds to the ring's inner edge; negative means the ring overlaps the control by that much.
    pub outset: f32,
    pub width: f32,
    /// Corner radius of the ring's outer edge.
    pub radius: f32,
    /// The border of the element the ring is a child of: children are positioned inside it.
    pub parent_border: f32,
}

impl RingSpec {
    /// The same ring as a child of an element with a [border] of this many dp.
    pub const fn within_border(mut self, border: f32) -> RingSpec {
        self.parent_border = border;
        self
    }

    /// A ring around the control with a gap between them: a filled checkbox, radio button or default button.
    pub const fn outside(gap: f32, width: f32, control_radius: f32) -> RingSpec {
        RingSpec {
            outset: gap,
            width,
            radius: control_radius + gap + width,
            parent_border: 0.0,
        }
    }

    /// A ring centred on the control's edge, replacing its border: an outline button or an empty box.
    pub const fn on_edge(width: f32, control_radius: f32) -> RingSpec {
        RingSpec {
            outset: -width / 2.0,
            width,
            radius: control_radius + width / 2.0,
            parent_border: 0.0,
        }
    }
}

/// The ring as an absolutely positioned child; its parent must be `relative()`. It is a plain `Div`, so it can be
/// placed anywhere a child can, and being absolute it never affects layout.
pub fn ring(t: &ActiveTheme, spec: RingSpec, color: Rgba) -> Div {
    let reach: Pixels = t.dp(spec.width + spec.outset + spec.parent_border);
    div()
        .absolute()
        .top(-reach)
        .left(-reach)
        .right(-reach)
        .bottom(-reach)
        .border(t.dp(spec.width))
        .border_color(hsla(color))
        .rounded(t.dp(spec.radius))
}
