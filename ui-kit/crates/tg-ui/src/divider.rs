//! A 1dp rule in the theme's divider colour. Give it a length like any `div` (`w_full`, `h(...)`).

use gpui::{App, Div, IntoElement, RenderOnce, StyleRefinement, Styled, Window, div};

use crate::theme::{ThemeExt, hsla};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Orientation {
    Horizontal,
    Vertical,
}

#[derive(IntoElement)]
pub struct Divider {
    base: Div,
    orientation: Orientation,
}

/// A horizontal rule: 1dp high.
pub fn divider() -> Divider {
    Divider {
        base: div(),
        orientation: Orientation::Horizontal,
    }
}

/// A vertical rule: 1dp wide.
pub fn vertical_divider() -> Divider {
    Divider {
        base: div(),
        orientation: Orientation::Vertical,
    }
}

impl Styled for Divider {
    fn style(&mut self) -> &mut StyleRefinement {
        self.base.style()
    }
}

impl RenderOnce for Divider {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme();
        let thickness = t.dp(1.0);
        let base = self.base.flex_none().bg(hsla(t.theme.ui.divider));
        match self.orientation {
            Orientation::Horizontal => base.h(thickness),
            Orientation::Vertical => base.w(thickness),
        }
    }
}
