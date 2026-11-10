//! The workspace's own pill tabs (`WorkspaceView.PillTab` in the Kotlin app), with `tg_ui_logic::TabRow` behind them:
//! a press selects a tab and starts a drag that reorders it, the row scrolls while the dragged tab is held against an
//! edge, a middle click closes a tab, and the close button shows on the selected and the hovered tab only (its room is
//! always kept, so tabs never change width).
//!
//! Metrics from `WorkspaceView.kt`: a 42dp row with 6dp side padding and 4dp between tabs; a tab is 30dp tall, 8dp
//! round, padded 10dp left and 4dp right, with 6dp between its icon, title and an 18dp close box. Selected: the accent
//! at 22% (dark) or 12% (light) with a 1dp border of the accent at 55%; hovered: the theme's chip hover.

use std::cell::RefCell;
use std::rc::Rc;

use gpui::{
    App, BoxShadow, ElementId, Entity, InteractiveElement, IntoElement, MouseButton,
    MouseDownEvent, MouseMoveEvent, MouseUpEvent, ParentElement, RenderOnce, ScrollWheelEvent,
    SharedString, StatefulInteractiveElement, Styled, Window, deferred, div, point, prelude::*, px,
};
use tg_theme::Rgba;
use tg_ui_logic::TabRow;
use tg_ui_logic::tab_drag::{Button, TabAction, press};

use crate::icon::icon;
use crate::tab_strip::{OnIndex, label_width};
use crate::text::text;
use crate::theme::{ThemeExt, hsla};

pub const ROW_H: f32 = 42.0;
const ROW_PAD: f32 = 6.0;
const TAB_GAP: f32 = 4.0;
const TAB_H: f32 = 30.0;
const RADIUS: f32 = 8.0;
const PAD_START: f32 = 10.0;
const PAD_END: f32 = 4.0;
const INNER_GAP: f32 = 6.0;
const ICON: f32 = 16.0;
const CLOSE_BOX: f32 = 18.0;
/// Everything in a tab but its title.
const CHROME: f32 = PAD_START + ICON + INNER_GAP + INNER_GAP + CLOSE_BOX + PAD_END;

/// One open tab as the strip shows it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WorkspaceTab {
    pub title: SharedString,
    /// A SQL or command console (console icon) rather than a table.
    pub console: bool,
    /// Unsaved work: the title gets a trailing bullet.
    pub dirty: bool,
}

impl WorkspaceTab {
    pub fn new(title: impl Into<SharedString>) -> Self {
        WorkspaceTab {
            title: title.into(),
            console: false,
            dirty: false,
        }
    }

    pub fn console(mut self) -> Self {
        self.console = true;
        self
    }

    pub fn dirty(mut self) -> Self {
        self.dirty = true;
        self
    }

    fn label(&self) -> SharedString {
        if self.dirty {
            format!("{} •", self.title).into()
        } else {
            self.title.clone()
        }
    }
}

pub type OnMove = Rc<dyn Fn(usize, usize, &mut Window, &mut App)>;

#[derive(IntoElement)]
pub struct WorkspaceTabs {
    id: SharedString,
    tabs: Vec<WorkspaceTab>,
    selected: usize,
    hovered: Option<usize>,
    on_select: Option<OnIndex>,
    on_close: Option<OnIndex>,
    on_move: Option<OnMove>,
}

pub fn workspace_tabs(
    id: impl Into<SharedString>,
    tabs: Vec<WorkspaceTab>,
    selected: usize,
) -> WorkspaceTabs {
    WorkspaceTabs {
        id: id.into(),
        tabs,
        selected,
        hovered: None,
        on_select: None,
        on_close: None,
        on_move: None,
    }
}

impl WorkspaceTabs {
    /// Paint tab `index` as under the pointer, for a static specimen.
    pub fn hovered(mut self, index: usize) -> Self {
        self.hovered = Some(index);
        self
    }

    pub fn on_select(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_select = Some(Rc::new(f));
        self
    }

    /// The close button or a middle click.
    pub fn on_close(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_close = Some(Rc::new(f));
        self
    }

    /// A dragged tab traded places: move the tab at `from` to `to` in the owner's list.
    pub fn on_move(mut self, f: impl Fn(usize, usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_move = Some(Rc::new(f));
        self
    }
}

/// The row's drag state across frames.
struct RowState {
    row: TabRow,
    widths: Vec<f64>,
    viewport: f64,
    /// Where the pointer was last seen during a drag, in dp along the row.
    last_x: Option<f32>,
}

impl RowState {
    fn new() -> Self {
        RowState {
            row: TabRow::new(Vec::new(), TAB_GAP as f64, 0.0, 1.0),
            widths: Vec::new(),
            viewport: 0.0,
            last_x: None,
        }
    }

    /// Rebuilds the row for new tabs (never mid-drag, where the row keeps its own order), keeping the scroll.
    fn sync(&mut self, widths: Vec<f64>, viewport: f64) {
        if self.row.is_dragging() || self.row.dragged().is_some() {
            return;
        }
        if widths != self.widths || (viewport - self.viewport).abs() > 0.01 {
            let scroll = self.row.scroll();
            self.row = TabRow::new(widths.clone(), TAB_GAP as f64, viewport, 1.0);
            self.row.set_scroll(scroll);
            self.widths = widths;
            self.viewport = viewport;
        }
    }
}

impl RenderOnce for WorkspaceTabs {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let islands = t.theme.colors.islands.clone();
        let dark = t.theme.is_dark();
        let zoom = t.zoom();
        let labels: Vec<SharedString> = self.tabs.iter().map(WorkspaceTab::label).collect();
        let widths_dp: Vec<f32> = labels
            .iter()
            .map(|l| CHROME + label_width(window, &t, l))
            .collect();
        let widths: Vec<f64> = widths_dp.iter().map(|w| *w as f64).collect();
        let state: Entity<RowState> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, |_, _| RowState::new())
            .clone();
        let viewport = state.read(cx).viewport;
        state.update(cx, |s, _| s.sync(widths, viewport));
        let (scroll, offset, dragged, dragging) = {
            let s = state.read(cx);
            (
                s.row.scroll() as f32,
                s.row.offset() as f32,
                s.row.dragged(),
                s.row.is_dragging(),
            )
        };
        let selected_fill = islands.accent.with_alpha(if dark { 0.22 } else { 0.12 });
        let selected_border = islands.accent.with_alpha(0.55);
        let mut row = div()
            .flex()
            .flex_none()
            .items_center()
            .gap(t.dp(TAB_GAP))
            .h_full()
            .relative()
            .left(t.dp(-scroll));
        for (i, tab) in self.tabs.iter().enumerate() {
            let selected = i == self.selected;
            let lifted = dragged == Some(i);
            let hover_look = self.hovered == Some(i);
            let group: SharedString = format!("{}.tab.{i}", self.id).into();
            let (on_select, on_close, on_close_box) = (
                self.on_select.clone(),
                self.on_close.clone(),
                self.on_close.clone(),
            );
            let press_state = state.clone();
            let fill: Option<Rgba> = if selected {
                Some(selected_fill)
            } else if hover_look {
                Some(islands.chip_hover)
            } else {
                None
            };
            let chip_hover = hsla(islands.chip_hover);
            let pill = div()
                .id(ElementId::Name(group.clone()))
                .group(group.clone())
                .debug_selector(move || format!("workspace-tabs.tab.{i}"))
                .relative()
                .flex()
                .flex_none()
                .items_center()
                .gap(t.dp(INNER_GAP))
                .w(t.dp(widths_dp[i]))
                .h(t.dp(TAB_H))
                .pl(t.dp(PAD_START))
                .pr(t.dp(PAD_END))
                .rounded(t.dp(RADIUS))
                .when(lifted, |d| {
                    d.left(t.dp(offset))
                        .bg(hsla(islands.island))
                        .shadow(vec![BoxShadow {
                            color: hsla(Rgba::argb(0x40000000)),
                            offset: point(px(0.0), t.dp(2.0)),
                            blur_radius: t.dp(6.0),
                            spread_radius: px(0.0),
                        }])
                })
                .when_some(fill, |d, c| d.bg(hsla(c)))
                .when(!selected, |d| d.hover(move |s| s.bg(chip_hover)))
                // As in Compose the border takes no room: an overlay on the tab's edge.
                .when(selected, |d| {
                    d.child(
                        div()
                            .absolute()
                            .top_0()
                            .left_0()
                            .size_full()
                            .rounded(t.dp(RADIUS))
                            .border(t.dp(1.0))
                            .border_color(hsla(selected_border)),
                    )
                })
                .on_mouse_down(MouseButton::Left, move |e: &MouseDownEvent, window, cx| {
                    if let TabAction::Select(i) = press(i, Button::Primary) {
                        press_state.update(cx, |s, _| {
                            s.row.start(i);
                            s.last_x = Some(f32::from(e.position.x) / zoom);
                        });
                        if let Some(f) = &on_select {
                            f(i, window, cx);
                        }
                        window.refresh();
                    }
                })
                .on_mouse_down(MouseButton::Middle, move |_, window, cx| {
                    if let (TabAction::Close(i), Some(f)) = (press(i, Button::Middle), &on_close) {
                        f(i, window, cx);
                    }
                })
                .child(
                    icon(if tab.console {
                        "Nodes.Console"
                    } else {
                        "Nodes.DataTables"
                    })
                    .size(ICON),
                )
                .child(div().whitespace_nowrap().child(text(labels[i].clone())))
                .child(
                    div()
                        .id(ElementId::Name(format!("{}.close.{i}", self.id).into()))
                        .debug_selector(move || format!("workspace-tabs.close.{i}"))
                        .flex()
                        .flex_none()
                        .items_center()
                        .justify_center()
                        .size(t.dp(CLOSE_BOX))
                        .rounded(t.dp(4.0))
                        .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
                        .on_click(move |_, window, cx| {
                            if let Some(f) = &on_close_box {
                                f(i, window, cx);
                            }
                        })
                        .child(
                            div()
                                .when(!(selected || hover_look), |d| {
                                    d.invisible().group_hover(group.clone(), |s| s.visible())
                                })
                                .child(icon("General.CloseSmall").size(ICON)),
                        ),
                );
            // The dragged tab paints above the ones it passes over.
            if lifted {
                row = row.child(deferred(pill).with_priority(1));
            } else {
                row = row.child(pill);
            }
        }
        let watch = state.clone();
        let on_move = self.on_move.clone();
        div()
            .id(ElementId::Name(format!("{}.row", self.id).into()))
            .debug_selector(|| "workspace-tabs".into())
            .relative()
            .w_full()
            .h(t.dp(ROW_H))
            .px(t.dp(ROW_PAD))
            .overflow_hidden()
            .child(row)
            .on_scroll_wheel({
                let state = state.clone();
                move |event: &ScrollWheelEvent, window, cx| {
                    let d = event.delta.pixel_delta(px(20.0));
                    let dx = if d.x.abs() > d.y.abs() { d.x } else { d.y };
                    state.update(cx, |s, _| {
                        let to = s.row.scroll() - f64::from(f32::from(dx) / zoom);
                        s.row.set_scroll(to);
                    });
                    window.refresh();
                }
            })
            .child(
                gpui::canvas(
                    move |bounds, _, cx| {
                        let viewport = f64::from(f32::from(bounds.size.width) / zoom);
                        watch.update(cx, |s, _| {
                            if (s.viewport - viewport).abs() > 0.01 {
                                let widths = s.widths.clone();
                                s.viewport = -1.0;
                                s.sync(widths, viewport);
                            }
                        });
                    },
                    {
                        let state = state.clone();
                        move |_, _, window, cx| {
                            if !dragging {
                                return;
                            }
                            // While a tab is held: follow the pointer anywhere in the window, scroll at the edges.
                            let swap = Rc::new(RefCell::new(None));
                            if state.read(cx).row.scroll_speed() != 0.0 {
                                *swap.borrow_mut() = state.update(cx, |s, _| s.row.frame());
                                window.request_animation_frame();
                            }
                            if let (Some((from, to)), Some(f)) = (swap.take(), &on_move) {
                                f(from, to, window, cx);
                            }
                            let (moved, released) = (state.clone(), state.clone());
                            let on_move = on_move.clone();
                            window.on_mouse_event(move |e: &MouseMoveEvent, phase, window, cx| {
                                if !phase.bubble() {
                                    return;
                                }
                                let x = f32::from(e.position.x) / zoom;
                                let swap = moved.update(cx, |s, _| {
                                    let delta = x - s.last_x.unwrap_or(x);
                                    s.last_x = Some(x);
                                    s.row
                                        .is_dragging()
                                        .then(|| s.row.drag(delta as f64))
                                        .flatten()
                                });
                                if let (Some((from, to)), Some(f)) = (swap, &on_move) {
                                    f(from, to, window, cx);
                                }
                                window.refresh();
                            });
                            window.on_mouse_event(move |_: &MouseUpEvent, phase, window, cx| {
                                if !phase.bubble() {
                                    return;
                                }
                                released.update(cx, |s, _| {
                                    s.row.end();
                                    s.row.settle();
                                    s.last_x = None;
                                });
                                window.refresh();
                            });
                        }
                    },
                )
                .absolute()
                .size_full(),
            )
    }
}
