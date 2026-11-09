//! A horizontal split as `MainScreen` lays out the sidebar and the agent panel (`tg_ui_logic::Split`): the divider is
//! invisible and the 6dp gap between the two panes' islands is the handle. Each pane is inset 3dp on the side facing
//! the other, so islands placed in them stand 6dp apart. Dragging the gap moves the divider within both panes'
//! minimum widths; the owner can read the ratio back to save it.

use std::rc::Rc;

use gpui::{
    AnyElement, App, CursorStyle, ElementId, Entity, InteractiveElement, IntoElement, MouseButton,
    MouseDownEvent, MouseMoveEvent, MouseUpEvent, ParentElement, RenderOnce, SharedString, Styled,
    Window, div, prelude::*,
};
use tg_ui_logic::Split;

use crate::theme::ThemeExt;

/// The gap between islands, which is also the handle's width.
pub const GAP: f32 = 6.0;

pub type OnRatio = Rc<dyn Fn(f64, &mut Window, &mut App)>;

#[derive(IntoElement)]
pub struct SplitLayout {
    id: SharedString,
    first: AnyElement,
    second: AnyElement,
    ratio: f64,
    first_min: f32,
    second_min: f32,
    on_ratio: Option<OnRatio>,
}

/// `first` and `second` side by side, the first `ratio` of the width to begin with.
pub fn split_layout(
    id: impl Into<SharedString>,
    ratio: f64,
    first: impl IntoElement,
    second: impl IntoElement,
) -> SplitLayout {
    SplitLayout {
        id: id.into(),
        first: first.into_any_element(),
        second: second.into_any_element(),
        ratio,
        first_min: 0.0,
        second_min: 0.0,
        on_ratio: None,
    }
}

impl SplitLayout {
    /// Minimum pane widths in dp (the sidebar's 220 and 420, the agent's 480 and 300).
    pub fn min_widths(mut self, first: f32, second: f32) -> Self {
        self.first_min = first;
        self.second_min = second;
        self
    }

    /// The divider moved: the new ratio, to save.
    pub fn on_ratio(mut self, f: impl Fn(f64, &mut Window, &mut App) + 'static) -> Self {
        self.on_ratio = Some(Rc::new(f));
        self
    }
}

struct SplitState {
    split: Split,
    /// The split's width in dp at the last layout; 0 before the first.
    total: f64,
    dragging: bool,
    /// The split's left edge in window pixels, to turn pointer positions into divider positions.
    left: f32,
}

impl RenderOnce for SplitLayout {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let zoom = t.zoom();
        let (ratio, first_min, second_min) =
            (self.ratio, self.first_min as f64, self.second_min as f64);
        let state: Entity<SplitState> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, move |_, _| {
                SplitState {
                    split: Split::new(ratio, first_min, second_min),
                    total: 0.0,
                    dragging: false,
                    left: 0.0,
                }
            })
            .clone();
        let (first_w, total, dragging) = {
            let s = state.read(cx);
            (s.split.first_width(s.total) as f32, s.total, s.dragging)
        };
        let first = div()
            .flex_none()
            .h_full()
            .pr(t.dp(GAP / 2.0))
            .child(self.first);
        // Before the first layout the ratio stands in for the width.
        let first = if total > 0.0 {
            first.w(t.dp(first_w))
        } else {
            first.w(gpui::relative(ratio as f32))
        };
        let second = div()
            .flex_1()
            .min_w_0()
            .h_full()
            .pl(t.dp(GAP / 2.0))
            .child(self.second);
        let press = state.clone();
        let handle = div()
            .id(ElementId::Name(format!("{}.handle", self.id).into()))
            .debug_selector(|| "split.handle".into())
            .absolute()
            .top_0()
            .bottom_0()
            .when(total > 0.0, |d| d.left(t.dp(first_w - GAP / 2.0)))
            .when(total <= 0.0, |d| {
                d.left(gpui::relative(ratio as f32)).ml(t.dp(-GAP / 2.0))
            })
            .w(t.dp(GAP))
            .cursor(CursorStyle::ResizeLeftRight)
            .on_mouse_down(MouseButton::Left, move |_: &MouseDownEvent, window, cx| {
                cx.stop_propagation();
                press.update(cx, |s, _| s.dragging = true);
                window.refresh();
            });
        let layout = state.clone();
        let drag = state.clone();
        let on_ratio = self.on_ratio.clone();
        div()
            .id(ElementId::Name(self.id.clone()))
            .relative()
            .flex()
            .size_full()
            .child(first)
            .child(second)
            .child(handle)
            .child(
                gpui::canvas(
                    move |bounds, window, cx| {
                        let total = f64::from(f32::from(bounds.size.width) / zoom);
                        let left = f32::from(bounds.origin.x);
                        let changed = layout.update(cx, |s, _| {
                            s.left = left;
                            let changed = (s.total - total).abs() > 0.01;
                            s.total = total;
                            s.split.layout(total);
                            changed
                        });
                        // The first frame laid out by ratio alone; the next applies the minimum widths.
                        if changed {
                            // A refresh during drawing is dropped; ask for the next frame instead.
                            window.request_animation_frame();
                        }
                    },
                    move |_, _, window, _| {
                        if !dragging {
                            return;
                        }
                        let (moved, released) = (drag.clone(), drag.clone());
                        let on_ratio = on_ratio.clone();
                        window.on_mouse_event(move |e: &MouseMoveEvent, phase, window, cx| {
                            if !phase.bubble() {
                                return;
                            }
                            let ratio = moved.update(cx, |s, _| {
                                let x = (f32::from(e.position.x) - s.left) / zoom;
                                s.split.drag_to(x as f64, s.total);
                                s.split.ratio
                            });
                            if let Some(f) = &on_ratio {
                                f(ratio, window, cx);
                            }
                            window.refresh();
                        });
                        window.on_mouse_event(move |_: &MouseUpEvent, phase, window, cx| {
                            if phase.bubble() {
                                released.update(cx, |s, _| s.dragging = false);
                                window.refresh();
                            }
                        });
                    },
                )
                .absolute()
                .size_full(),
            )
    }
}
