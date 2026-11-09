//! The thin tool-window stripes on the frame's left and right edges (`ToolStripe.kt`): 44dp wide, a column of 32dp
//! buttons 6dp apart. A selected button fills with the accent and draws its icon white; a hovered one shows the
//! theme's stripe-hover colour. Each button has a tooltip.

use std::rc::Rc;

use gpui::{
    App, Div, ElementId, IntoElement, MouseButton, ParentElement, RenderOnce, SharedString, Styled,
    Window, div, prelude::*,
};
use tg_theme::Rgba;

use crate::divider::divider;
use crate::icon::icon;
use crate::icon_button::{attach_tooltip, tip_state};
use crate::interact::focus_handle;
use crate::look::Look;
use crate::theme::{ThemeExt, hsla};

const WIDTH: f32 = 44.0;
const BUTTON: f32 = 32.0;
const RADIUS: f32 = 8.0;
const GAP: f32 = 6.0;

pub type OnClick = Rc<dyn Fn(&mut Window, &mut App)>;

/// One stripe button.
#[derive(IntoElement)]
pub struct StripeButton {
    id: SharedString,
    icon: &'static str,
    tooltip: Option<SharedString>,
    selected: bool,
    look: Option<Look>,
    on_click: Option<OnClick>,
}

/// A stripe button showing the icon [key], identified by [id] (an element id, unique in the window).
pub fn stripe_button(id: impl Into<SharedString>, key: &'static str) -> StripeButton {
    StripeButton {
        id: id.into(),
        icon: key,
        tooltip: None,
        selected: false,
        look: None,
        on_click: None,
    }
}

impl StripeButton {
    pub fn tooltip(mut self, text: impl Into<SharedString>) -> Self {
        self.tooltip = Some(text.into());
        self
    }

    /// The button's tool window (or dialog) is showing: accent fill, white icon.
    pub fn selected(mut self, selected: bool) -> Self {
        self.selected = selected;
        self
    }

    /// Draws the button as hovered or focused regardless of the pointer, for the gallery.
    pub fn look(mut self, look: Look) -> Self {
        self.look = Some(look);
        self
    }

    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for StripeButton {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let focus = focus_handle(window, cx, &self.id, true);
        let tip = tip_state(window, cx, &self.id);
        let t = cx.theme().clone();
        let islands = &t.theme.colors.islands;
        let look = self.look.unwrap_or_default();
        let hovered_bg = hsla(islands.stripe_hover);
        let background = if self.selected {
            Some(hsla(islands.accent))
        } else if look.hovered || look.pressed {
            Some(hovered_bg)
        } else {
            None
        };
        let selector = self.id.to_string();
        let on_click = self.on_click;
        let key_click = on_click.clone();
        let selected = self.selected;
        let mut el = div()
            .id(ElementId::Name(self.id))
            .debug_selector(move || selector)
            .flex()
            .items_center()
            .justify_center()
            .flex_none()
            .size(t.dp(BUTTON))
            .rounded(t.dp(RADIUS))
            .when_some(background, |d, c| d.bg(c))
            .when(!selected, |d| d.hover(move |s| s.bg(hovered_bg)))
            .track_focus(&focus)
            .cursor_pointer()
            .on_mouse_down(MouseButton::Left, {
                let focus = focus.clone();
                move |_, window, _| window.focus(&focus)
            })
            .on_click(move |_, window, cx| {
                if let Some(f) = &on_click {
                    f(window, cx);
                }
            })
            .on_key_down(move |event, window, cx| {
                if matches!(event.keystroke.key.as_str(), "space" | "enter") {
                    cx.stop_propagation();
                    if let Some(f) = &key_click {
                        f(window, cx);
                    }
                }
            })
            .child(if selected {
                icon(self.icon).crisp().tint(Rgba::rgb(0xFFFFFF))
            } else {
                icon(self.icon).crisp()
            });
        if let Some(label) = self.tooltip {
            el = attach_tooltip(el, tip, label, &t, BUTTON + 4.0, cx);
        }
        el
    }
}

/// A stripe: its tool buttons from the top, then actions, then (pinned to the bottom) the bottom buttons.
#[derive(IntoElement)]
pub struct ToolStripe {
    top: Vec<StripeButton>,
    actions: Vec<StripeButton>,
    bottom: Vec<StripeButton>,
}

/// The left stripe: tool windows, a short rule, actions, then (pinned to the bottom) [bottom](ToolStripe::bottom).
pub fn tool_stripe(tools: Vec<StripeButton>) -> ToolStripe {
    ToolStripe {
        top: tools,
        actions: Vec::new(),
        bottom: Vec::new(),
    }
}

/// The right stripe: tool windows beside the editor, nothing else.
pub fn right_tool_stripe(tools: Vec<StripeButton>) -> ToolStripe {
    tool_stripe(tools)
}

impl ToolStripe {
    /// Action buttons below the tools; a 20dp rule separates the two groups when both are there.
    pub fn actions(mut self, actions: Vec<StripeButton>) -> Self {
        self.actions = actions;
        self
    }

    pub fn bottom(mut self, bottom: Vec<StripeButton>) -> Self {
        self.bottom = bottom;
        self
    }
}

impl RenderOnce for ToolStripe {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let rule = !self.top.is_empty() && !self.actions.is_empty();
        let column: Div = div()
            .flex()
            .flex_col()
            .items_center()
            .flex_none()
            .w(t.dp(WIDTH))
            .h_full()
            .py(t.dp(6.0))
            .gap(t.dp(GAP));
        column
            .children(self.top)
            .when(rule, |d| {
                d.child(div().py(t.dp(2.0)).child(divider().w(t.dp(20.0))))
            })
            .children(self.actions)
            .child(div().flex_1())
            .children(self.bottom)
    }
}
