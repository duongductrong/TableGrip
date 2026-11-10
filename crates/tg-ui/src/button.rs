//! The default (filled) and outlined buttons. Behaviour comes from `tg_ui_logic::Button`; the colours from
//! [`crate::palette`]. A press takes focus, and a focused button draws its ring as an overlay outside its bounds.

use std::rc::Rc;

use gpui::{
    App, ElementId, FocusHandle, IntoElement, MouseButton, RenderOnce, SharedString, Window, div,
    prelude::*,
};
use tg_ui_logic::Input;

use crate::look::Look;
use crate::palette::{ButtonVariant, TRANSPARENT, button_colors};
use crate::ring::{RingSpec, ring};
use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};

/// Runs when the button is activated.
pub type OnClick = Rc<dyn Fn(&mut Window, &mut App)>;

const HEIGHT: f32 = 28.0;
const MIN_WIDTH: f32 = 72.0;
const PADDING: f32 = 13.0;
const RADIUS: f32 = 4.0;
/// A filled button's ring floats half a dp off the button; an outlined one sits on its edge.
const FILLED_RING: RingSpec = RingSpec::outside(0.5, 2.0, RADIUS);
const OUTLINED_RING: RingSpec = RingSpec::on_edge(2.0, RADIUS);

#[derive(IntoElement)]
pub struct Button {
    id: SharedString,
    label: SharedString,
    variant: ButtonVariant,
    enabled: bool,
    on_click: Option<OnClick>,
    look: Option<Look>,
}

/// A default (filled) button labelled [label]; [id] keeps its focus across frames and names it for tests.
pub fn button(id: impl Into<SharedString>, label: impl Into<SharedString>) -> Button {
    Button {
        id: id.into(),
        label: label.into(),
        variant: ButtonVariant::Default,
        enabled: true,
        on_click: None,
        look: None,
    }
}

impl Button {
    pub fn outlined(mut self) -> Self {
        self.variant = ButtonVariant::Outlined;
        self
    }

    pub fn variant(mut self, variant: ButtonVariant) -> Self {
        self.variant = variant;
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }

    /// Draws the button in [look] whatever the pointer and focus do; for the gallery.
    pub fn look(mut self, look: Look) -> Self {
        self.look = Some(look);
        self
    }
}

fn handle(
    variant: ButtonVariant,
    enabled: bool,
    input: &Input,
) -> tg_ui_logic::button::ButtonResponse {
    let mut logic = tg_ui_logic::Button::new(variant);
    logic.enabled = enabled;
    logic.handle(input)
}

impl RenderOnce for Button {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let focus: FocusHandle = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, |_, cx| {
                cx.focus_handle()
            })
            .read(cx)
            .clone()
            .tab_stop(self.enabled);
        let look = self.look.unwrap_or_default();
        let enabled = self.enabled;
        let focused = look.focused || (enabled && focus.is_focused(window));
        let t = cx.theme().clone();
        let colors = button_colors(&t.theme, self.variant);
        let filled = self.variant == ButtonVariant::Default;

        let border_at = move |focused: bool, hovered: bool| {
            if filled {
                if focused {
                    colors.border_focused
                } else if hovered {
                    colors.border_hovered
                } else {
                    colors.border
                }
            } else if focused {
                // The ring sits on the edge, over the border.
                colors.border_focused
            } else if hovered {
                colors.border_hovered
            } else {
                colors.border
            }
        };
        let (fill, border) = if !enabled {
            (TRANSPARENT, colors.border_disabled)
        } else {
            let fill = if look.pressed {
                colors.fill_pressed
            } else if look.hovered {
                colors.fill_hovered
            } else {
                colors.fill
            };
            (fill, border_at(focused, look.hovered || look.pressed))
        };
        let label_color = if enabled {
            colors.text
        } else {
            colors.text_disabled
        };

        let selector = self.id.to_string();
        let on_click = self.on_click;
        let variant = self.variant;
        let mut el = div()
            .id(ElementId::Name(self.id))
            .debug_selector(move || selector)
            .relative()
            .flex()
            .items_center()
            .justify_center()
            .h(t.dp(HEIGHT))
            .min_w(t.dp(MIN_WIDTH))
            .px(t.dp(PADDING))
            .rounded(t.dp(RADIUS))
            .border(t.dp(1.0))
            .bg(hsla(fill))
            .border_color(hsla(border))
            .child(text(self.label).color(TextColor::Custom(label_color)))
            .when(enabled && focused, |d| {
                let spec = if filled { FILLED_RING } else { OUTLINED_RING };
                d.child(ring(&t, spec.within_border(1.0), colors.ring))
            });
        if enabled {
            let key_click = on_click.clone();
            el = el
                .track_focus(&focus)
                .cursor_pointer()
                .hover(move |s| {
                    s.bg(hsla(colors.fill_hovered))
                        .border_color(hsla(border_at(focused, true)))
                })
                .active(move |s| s.bg(hsla(colors.fill_pressed)))
                .on_mouse_down(MouseButton::Left, {
                    let focus = focus.clone();
                    move |_, window, _| window.focus(&focus)
                })
                .on_click(move |_, window, cx| {
                    if handle(variant, true, &Input::Click).activated
                        && let Some(f) = &on_click
                    {
                        f(window, cx);
                    }
                })
                .on_key_down(move |event, window, cx| {
                    if let Some(input) = crate::link::key_input(event)
                        && handle(variant, true, &input).activated
                    {
                        cx.stop_propagation();
                        if let Some(f) = &key_click {
                            f(window, cx);
                        }
                    }
                });
        }
        el
    }
}
