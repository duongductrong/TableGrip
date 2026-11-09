//! The window's status bar (`StatusBar.kt`): 26dp high on the frame, the breadcrumbs and the latest message on the
//! left, small widgets on the right. A widget is 20dp high with a 4dp-rounded hover chip when it can be clicked.

use gpui::{
    AnyElement, App, Div, ElementId, IntoElement, ParentElement, RenderOnce, SharedString, Styled,
    Window, div, prelude::*,
};
use tg_theme::Rgba;

use crate::icon::icon;
use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};

const HEIGHT: f32 = 26.0;

/// A thin vertical rule between the bar's halves.
pub fn status_divider(cx: &App) -> Div {
    let t = cx.theme();
    div()
        .mx(t.dp(4.0))
        .flex_none()
        .w(t.dp(1.0))
        .h(t.dp(12.0))
        .bg(hsla(t.theme.ui.border))
}

/// A 7dp state dot; hollow when there is no state to show (not connected).
pub fn status_dot(cx: &App, color: Option<Rgba>) -> Div {
    let t = cx.theme();
    let d = div().flex_none().size(t.dp(7.0)).rounded_full();
    match color {
        Some(c) => d.bg(hsla(c)),
        None => d.border(t.dp(1.0)).border_color(hsla(t.theme.ui.text_info)),
    }
}

/// One widget: a row of children 5dp apart, 6dp padding either side.
#[derive(IntoElement)]
pub struct StatusWidget {
    id: SharedString,
    children: Vec<AnyElement>,
    hovered: bool,
    clickable: bool,
}

pub fn status_widget(id: impl Into<SharedString>) -> StatusWidget {
    StatusWidget {
        id: id.into(),
        children: Vec::new(),
        hovered: false,
        clickable: false,
    }
}

impl StatusWidget {
    pub fn clickable(mut self) -> Self {
        self.clickable = true;
        self
    }

    /// Draws the hover chip regardless of the pointer, for the gallery.
    pub fn hovered(mut self) -> Self {
        self.hovered = true;
        self
    }
}

impl ParentElement for StatusWidget {
    fn extend(&mut self, elements: impl IntoIterator<Item = AnyElement>) {
        self.children.extend(elements);
    }
}

impl RenderOnce for StatusWidget {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let chip = hsla(t.theme.colors.islands.chip_hover);
        div()
            .id(ElementId::Name(self.id))
            .flex()
            .flex_none()
            .items_center()
            .gap(t.dp(5.0))
            .h(t.dp(20.0))
            .px(t.dp(6.0))
            .rounded(t.dp(4.0))
            .when(self.clickable && self.hovered, |d| d.bg(chip))
            .when(self.clickable, |d| {
                d.cursor_pointer().hover(move |s| s.bg(chip))
            })
            .children(self.children)
    }
}

/// The bar itself.
#[derive(IntoElement)]
pub struct StatusBar {
    crumbs: Vec<SharedString>,
    message: SharedString,
    widgets: Vec<AnyElement>,
}

/// A status bar showing [message] after any breadcrumbs.
pub fn status_bar(message: impl Into<SharedString>) -> StatusBar {
    StatusBar {
        crumbs: Vec::new(),
        message: message.into(),
        widgets: Vec::new(),
    }
}

impl StatusBar {
    /// Where you are, outermost first; the last one is drawn in the normal text colour, the others muted.
    pub fn breadcrumbs(
        mut self,
        crumbs: impl IntoIterator<Item = impl Into<SharedString>>,
    ) -> Self {
        self.crumbs = crumbs.into_iter().map(Into::into).collect();
        self
    }

    pub fn widget(mut self, widget: impl IntoElement) -> Self {
        self.widgets.push(widget.into_any_element());
        self
    }
}

impl RenderOnce for StatusBar {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let last = self.crumbs.len().saturating_sub(1);
        let has_crumbs = !self.crumbs.is_empty();
        let crumbs = has_crumbs.then(|| {
            div()
                .flex()
                .flex_none()
                .items_center()
                .gap(t.dp(4.0))
                .pr(t.dp(4.0))
                .max_w(t.dp(480.0))
                .children(self.crumbs.into_iter().enumerate().flat_map(|(i, crumb)| {
                    let mut parts = Vec::new();
                    if i > 0 {
                        parts.push(
                            icon("General.ChevronRight")
                                .size(12.0)
                                .tint(t.theme.ui.text_info)
                                .into_any_element(),
                        );
                    }
                    parts.push(
                        text(crumb)
                            .color(if i == last {
                                TextColor::Normal
                            } else {
                                TextColor::Info
                            })
                            .ellipsis()
                            .into_any_element(),
                    );
                    parts
                }))
        });
        div()
            .flex()
            .items_center()
            .flex_none()
            .w_full()
            .h(t.dp(HEIGHT))
            .pl(t.dp(10.0))
            .pr(t.dp(8.0))
            .gap(t.dp(2.0))
            .children(crumbs)
            .when(has_crumbs, |d| d.child(status_divider(cx)))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .px(t.dp(4.0))
                    .child(text(self.message).color(TextColor::Info).ellipsis()),
            )
            .children(self.widgets)
    }
}
