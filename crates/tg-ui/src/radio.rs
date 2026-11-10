//! A radio button row (a 17dp circle and its label). Like the app uses them, the row only reports activation; the
//! group's owner keeps which row is selected. Every row is its own Tab stop. Behaviour comes from
//! `tg_ui_logic::RadioGroup`.

use std::rc::Rc;

use gpui::{
    App, ElementId, IntoElement, MouseButton, RenderOnce, SharedString, Window, div, prelude::*,
};
use tg_ui_logic::{Input, RadioGroup};

use crate::interact::focus_handle;
use crate::look::Look;
use crate::palette::box_colors;
use crate::ring::{RingSpec, ring};
use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};

pub type OnClick = Rc<dyn Fn(&mut Window, &mut App)>;

const ROW_HEIGHT: f32 = 24.0;
const CIRCLE: f32 = 17.0;
const CIRCLE_INSET: f32 = 3.5;
const LABEL_GAP: f32 = 7.5;
const DOT: f32 = 6.0;
const EMPTY_RING: RingSpec = RingSpec::on_edge(2.0, CIRCLE / 2.0);
const FILLED_RING: RingSpec = RingSpec::outside(1.5, 2.0, CIRCLE / 2.0);

#[derive(IntoElement)]
pub struct RadioButton {
    id: SharedString,
    label: SharedString,
    selected: bool,
    enabled: bool,
    error: bool,
    on_click: Option<OnClick>,
    look: Option<Look>,
}

/// A radio row; [selected] is the group's state for this row.
pub fn radio_button(
    id: impl Into<SharedString>,
    label: impl Into<SharedString>,
    selected: bool,
) -> RadioButton {
    RadioButton {
        id: id.into(),
        label: label.into(),
        selected,
        enabled: true,
        error: false,
        on_click: None,
        look: None,
    }
}

impl RadioButton {
    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn error(mut self, error: bool) -> Self {
        self.error = error;
        self
    }

    /// Called when a click, or Space or Enter while focused, chooses this row.
    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }

    pub fn look(mut self, look: Look) -> Self {
        self.look = Some(look);
        self
    }
}

/// Whether [input] chooses a lone row, by the group logic.
fn chosen(input: &Input) -> bool {
    let mut group = RadioGroup::new(1, None);
    match input {
        Input::Click => group.click(0).chosen.is_some(),
        _ => {
            group.focus_first();
            group.handle(input).chosen.is_some()
        }
    }
}

impl RenderOnce for RadioButton {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let focus = focus_handle(window, cx, &self.id, self.enabled);
        let enabled = self.enabled;
        let look = self.look.unwrap_or_default();
        let focused = look.focused || (enabled && focus.is_focused(window));
        let t = cx.theme().clone();
        let colors = box_colors(&t.theme);
        let selected = self.selected;

        let ring_color = if !enabled {
            None
        } else if focused {
            Some(if selected {
                colors.ring
            } else {
                colors.ring_empty
            })
        } else if self.error {
            Some(colors.error)
        } else {
            None
        };
        let (fill, border) = match (enabled, selected) {
            (false, _) => (colors.fill_disabled, colors.border_disabled),
            (true, true) => (colors.accent, colors.accent),
            // The ring of an empty circle sits on its edge, over the border.
            (true, false) => (colors.fill, ring_color.unwrap_or(colors.border)),
        };
        let dot = selected.then_some(if enabled {
            colors.mark
        } else {
            colors.mark_disabled
        });

        let circle_border = if selected && enabled { 0.0 } else { 1.0 };
        let circle = div()
            .relative()
            .flex_none()
            .flex()
            .items_center()
            .justify_center()
            .size(t.dp(CIRCLE))
            .ml(t.dp(CIRCLE_INSET))
            .mr(t.dp(LABEL_GAP))
            .rounded_full()
            .border(t.dp(circle_border))
            .bg(hsla(fill))
            .border_color(hsla(border))
            .when_some(dot, |d, color| {
                d.child(div().size(t.dp(DOT)).rounded_full().bg(hsla(color)))
            })
            .when_some(ring_color, |d, color| {
                let spec = if selected { FILLED_RING } else { EMPTY_RING };
                d.child(ring(&t, spec.within_border(circle_border), color))
            });

        let selector = self.id.to_string();
        let on_click = self.on_click;
        let label_color = if enabled {
            TextColor::Normal
        } else {
            TextColor::Custom(colors.radio_label_disabled)
        };
        let mut el = div()
            .id(ElementId::Name(self.id))
            .debug_selector(move || selector)
            .flex()
            .items_center()
            .h(t.dp(ROW_HEIGHT))
            .child(circle)
            .child(text(self.label).color(label_color));
        if enabled {
            let key_click = on_click.clone();
            el = el
                .track_focus(&focus)
                .cursor_pointer()
                .on_mouse_down(MouseButton::Left, {
                    let focus = focus.clone();
                    move |_, window, _| window.focus(&focus)
                })
                .on_click(move |_, window, cx| {
                    if chosen(&Input::Click)
                        && let Some(f) = &on_click
                    {
                        f(window, cx);
                    }
                })
                .on_key_down(move |event, window, cx| {
                    if let Some(input) = crate::link::key_input(event)
                        && chosen(&input)
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
