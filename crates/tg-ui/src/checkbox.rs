//! The checkbox row (a 16dp box and its label), including the tri-state header variant. Behaviour comes from
//! `tg_ui_logic::Checkbox`; what a click means for a tri-state header is the app's decision, so it only gets
//! `on_click`.

use std::rc::Rc;

use gpui::{
    App, ElementId, IntoElement, MouseButton, RenderOnce, SharedString, Window, div, prelude::*,
};
use tg_ui_logic::{Input, ToggleState};

use crate::interact::focus_handle;
use crate::look::Look;
use crate::marks;
use crate::palette::box_colors;
use crate::ring::{RingSpec, ring};
use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};

pub type OnToggle = Rc<dyn Fn(bool, &mut Window, &mut App)>;
pub type OnClick = Rc<dyn Fn(&mut Window, &mut App)>;

const ROW_HEIGHT: f32 = 24.0;
const BOX: f32 = 16.0;
/// The box sits 4dp in from the row's edge and its label starts 29dp in.
const BOX_INSET: f32 = 4.0;
const LABEL_GAP: f32 = 9.0;
const RADIUS: f32 = 3.2;
/// An empty box's ring replaces its border; a filled box's floats 1dp off it.
const EMPTY_RING: RingSpec = RingSpec::on_edge(2.0, RADIUS);
// Its outer corner measures 5.5dp, a little tighter than the box's radius plus the ring would give.
const FILLED_RING: RingSpec = RingSpec::outside(1.0, 2.0, 2.5);

#[derive(IntoElement)]
pub struct Checkbox {
    id: SharedString,
    label: SharedString,
    state: ToggleState,
    enabled: bool,
    error: bool,
    on_toggle: Option<OnToggle>,
    on_click: Option<OnClick>,
    look: Option<Look>,
}

/// A checkbox row, unchecked until [`Checkbox::checked`] or [`Checkbox::state`] says otherwise.
pub fn checkbox(id: impl Into<SharedString>, label: impl Into<SharedString>) -> Checkbox {
    Checkbox {
        id: id.into(),
        label: label.into(),
        state: ToggleState::Off,
        enabled: true,
        error: false,
        on_toggle: None,
        on_click: None,
        look: None,
    }
}

impl Checkbox {
    pub fn checked(mut self, checked: bool) -> Self {
        self.state = if checked {
            ToggleState::On
        } else {
            ToggleState::Off
        };
        self
    }

    /// The tri-state header's state, derived from its children by the app.
    pub fn state(mut self, state: ToggleState) -> Self {
        self.state = state;
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    /// Outlines the box in the error colour (`Outline.Error`).
    pub fn error(mut self, error: bool) -> Self {
        self.error = error;
        self
    }

    /// Called with the new value when a click or Space/Enter toggles the box.
    pub fn on_toggle(mut self, f: impl Fn(bool, &mut Window, &mut App) + 'static) -> Self {
        self.on_toggle = Some(Rc::new(f));
        self
    }

    /// Called when the control is activated, whatever the value becomes: for a tri-state header.
    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }

    pub fn look(mut self, look: Look) -> Self {
        self.look = Some(look);
        self
    }
}

impl RenderOnce for Checkbox {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let focus = focus_handle(window, cx, &self.id, self.enabled);
        let enabled = self.enabled;
        let look = self.look.unwrap_or_default();
        let focused = look.focused || (enabled && focus.is_focused(window));
        let t = cx.theme().clone();
        let colors = box_colors(&t.theme);
        let filled = self.state != ToggleState::Off;

        let ring_color = if !enabled {
            None
        } else if focused {
            Some(if filled {
                colors.ring
            } else {
                colors.ring_empty
            })
        } else if self.error {
            Some(colors.error)
        } else {
            None
        };
        let (fill, border) = match (enabled, filled) {
            (false, _) => (colors.fill_disabled, colors.border_disabled),
            (true, true) => (colors.accent, colors.accent),
            // The ring of an empty box sits on its edge, over the border.
            (true, false) => (colors.fill, ring_color.unwrap_or(colors.border)),
        };
        let mark = match (self.state, enabled) {
            (ToggleState::Off, _) => None,
            (ToggleState::On, true) | (ToggleState::Indeterminate, true) => Some(colors.mark),
            (ToggleState::On, false) => Some(colors.mark_disabled),
            (ToggleState::Indeterminate, false) => Some(colors.dash_disabled),
        };

        let the_box = div()
            .relative()
            .flex_none()
            .size(t.dp(BOX))
            .ml(t.dp(BOX_INSET))
            .mr(t.dp(LABEL_GAP))
            .rounded(t.dp(RADIUS))
            .border(t.dp(1.0))
            .bg(hsla(fill))
            .border_color(hsla(border))
            .when_some(mark, |d, color| {
                if self.state == ToggleState::On {
                    // The check is measured from the box's outer corner; the border is inside it.
                    d.child(
                        marks::check(&t, color)
                            .absolute()
                            .top(-t.dp(1.0))
                            .left(-t.dp(1.0))
                            .size(t.dp(BOX)),
                    )
                } else {
                    d.child(marks::dash(&t, color))
                }
            })
            .when_some(ring_color, |d, color| {
                let spec = if filled { FILLED_RING } else { EMPTY_RING };
                d.child(ring(&t, spec.within_border(1.0), color))
            });

        let selector = self.id.to_string();
        let state = self.state;
        let (on_toggle, on_click) = (self.on_toggle, self.on_click);
        let label_color = if enabled {
            TextColor::Normal
        } else {
            TextColor::Custom(colors.label_disabled)
        };
        let mut el = div()
            .id(ElementId::Name(self.id))
            .debug_selector(move || selector)
            .flex()
            .items_center()
            .h(t.dp(ROW_HEIGHT))
            .child(the_box)
            .child(text(self.label).color(label_color));
        if enabled {
            let activate = {
                let (on_toggle, on_click) = (on_toggle.clone(), on_click.clone());
                Rc::new(
                    move |input: &Input, window: &mut Window, cx: &mut App| -> bool {
                        let mut logic = tg_ui_logic::Checkbox::new(state == ToggleState::On);
                        let response = logic.handle(input);
                        let Some(value) = response.toggled else {
                            return false;
                        };
                        if let Some(f) = &on_toggle {
                            f(value, window, cx);
                        }
                        if let Some(f) = &on_click {
                            f(window, cx);
                        }
                        true
                    },
                )
            };
            let key_activate = activate.clone();
            el = el
                .track_focus(&focus)
                .cursor_pointer()
                .on_mouse_down(MouseButton::Left, {
                    let focus = focus.clone();
                    move |_, window, _| window.focus(&focus)
                })
                .on_click(move |_, window, cx| {
                    activate(&Input::Click, window, cx);
                })
                .on_key_down(move |event, window, cx| {
                    if let Some(input) = crate::link::key_input(event)
                        && key_activate(&input, window, cx)
                    {
                        cx.stop_propagation();
                    }
                });
        }
        el
    }
}
