//! A rounded content panel floating on the frame (TableGrip's `Island`): island colour, 1dp island border, corners
//! from Settings → Appearance unless a radius is given.
//!
//! As in Compose, the border is drawn over the background and doesn't inset the content: children are laid out from
//! the island's own edge.

use gpui::{
    AnyElement, App, IntoElement, ParentElement, RenderOnce, StyleRefinement, Styled, Window, div,
    prelude::*,
};

use crate::theme::{ThemeExt, hsla};

#[derive(IntoElement)]
pub struct Island {
    style: StyleRefinement,
    children: Vec<AnyElement>,
    radius: Option<f32>,
}

/// An island with the settings' corner radius; size and lay it out like any `div`.
pub fn island() -> Island {
    Island {
        style: StyleRefinement::default(),
        children: Vec::new(),
        radius: None,
    }
}

impl Island {
    /// An explicit corner radius in dp, as dialogs use (`14dp − inset`, at least 4dp).
    pub fn radius(mut self, dp: f32) -> Self {
        self.radius = Some(dp);
        self
    }
}

impl Styled for Island {
    fn style(&mut self) -> &mut StyleRefinement {
        &mut self.style
    }
}

impl ParentElement for Island {
    fn extend(&mut self, elements: impl IntoIterator<Item = AnyElement>) {
        self.children.extend(elements)
    }
}

impl RenderOnce for Island {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme();
        let islands = &t.theme.colors.islands;
        let radius = t.dp(self.radius.unwrap_or(t.theme.island_radius));
        let border = div()
            .absolute()
            .top_0()
            .left_0()
            .size_full()
            .border(t.dp(1.0))
            .border_color(hsla(islands.island_border))
            .rounded(radius);
        let mut el = div()
            .relative()
            .overflow_hidden()
            .bg(hsla(islands.island))
            .rounded(radius);
        el.style().refine(&self.style);
        el.child(border).children(self.children)
    }
}
