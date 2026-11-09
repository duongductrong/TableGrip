//! Jewel's tab strip, as the structure view, the dialogs and the console's result tabs use it (`tg_ui_logic::TabStrip`):
//! 40dp tabs of 8dp padding around the label, a 3dp pill underline in the accent under the selected tab, a hover fill
//! over the whole tab. A click selects without taking focus; with focus Left/Right/Home/End move the selection; the
//! strip scrolls sideways when the tabs outgrow it, keeping the selected tab in view. A middle click on any tab asks to
//! close it, closable or not, as Jewel does.

use std::rc::Rc;

use gpui::{
    App, ElementId, Entity, InteractiveElement, IntoElement, MouseButton, ParentElement, Pixels,
    RenderOnce, ScrollWheelEvent, SharedString, StatefulInteractiveElement, Styled, TextRun,
    Window, div, font, prelude::*, px,
};
use tg_theme::{Rgba, Theme};
use tg_ui_logic::input::Key;

use crate::icon::icon;
use crate::interact::focus_handle;
use crate::text::{TextColor, text};
use crate::theme::{ActiveTheme, ThemeExt, hsla};

pub const TAB_H: f32 = 40.0;
const TAB_PAD: f32 = 8.0;
const UNDERLINE_H: f32 = 3.0;
const CLOSE: f32 = 16.0;
const CLOSE_GAP: f32 = 4.0;

/// The strip's colours: Int UI's unless the theme styles controls, then its hover and text and the accent underline.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TabColors {
    /// Every tab's own fill: Int UI's white in a light theme (it shows on an island that isn't white), none in a dark.
    pub background: Option<Rgba>,
    pub hover: Rgba,
    pub text: TextColor,
    pub underline: Rgba,
}

/// The label's opacity on a tab that is neither selected nor hovered (Int UI's tab content alpha).
pub const IDLE_ALPHA: f32 = 0.9;

pub fn tab_colors(theme: &Theme) -> TabColors {
    let dark = theme.is_dark();
    let background = (!dark).then_some(Rgba::WHITE);
    match theme.colors.controls.as_ref() {
        Some(c) => TabColors {
            background,
            hover: c.hover,
            text: TextColor::Custom(c.text),
            underline: theme.colors.islands.accent,
        },
        None => TabColors {
            background,
            hover: Rgba::rgb(if dark { 0x43454A } else { 0xEBECF0 }),
            text: TextColor::Normal,
            underline: Rgba::rgb(0x3574F0),
        },
    }
}

/// Width in dp of a one-line UI label, as Compose lays it out.
pub(crate) fn label_width(window: &Window, t: &ActiveTheme, label: &SharedString) -> f32 {
    let size = t.dp(t.theme.fonts.ui_size);
    let run = TextRun {
        len: label.len(),
        font: font(t.ui_font.clone()),
        color: gpui::black(),
        background_color: None,
        underline: None,
        strikethrough: None,
    };
    let line = window.text_system().layout_line(label, size, &[run], None);
    // Compose lays text out at a whole number of device pixels, rounding its width up.
    let scale = window.scale_factor();
    (f32::from(line.width) * scale).ceil() / scale / t.zoom()
}

pub type OnIndex = Rc<dyn Fn(usize, &mut Window, &mut App)>;

#[derive(IntoElement)]
pub struct TabStrip {
    id: SharedString,
    labels: Vec<SharedString>,
    selected: usize,
    closable: bool,
    hovered: Option<usize>,
    on_select: Option<OnIndex>,
    on_close: Option<OnIndex>,
}

/// A strip of `labels` with `selected` chosen; the owner keeps the selection and is told of changes.
pub fn tab_strip(
    id: impl Into<SharedString>,
    labels: Vec<SharedString>,
    selected: usize,
) -> TabStrip {
    TabStrip {
        id: id.into(),
        labels,
        selected,
        closable: false,
        hovered: None,
        on_select: None,
        on_close: None,
    }
}

impl TabStrip {
    /// Tabs with a close button after the label.
    pub fn closable(mut self) -> Self {
        self.closable = true;
        self
    }

    /// Paint tab `index` as under the pointer, for a static specimen.
    pub fn hovered(mut self, index: usize) -> Self {
        self.hovered = Some(index);
        self
    }

    pub fn on_select(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_select = Some(Rc::new(f));
        self
    }

    /// A close button or a middle click on a tab.
    pub fn on_close(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_close = Some(Rc::new(f));
        self
    }
}

/// The strip's logic plus what it was built from, kept across frames.
struct StripState {
    logic: tg_ui_logic::TabStrip,
    widths: Vec<f64>,
    viewport: f64,
}

impl Default for StripState {
    fn default() -> Self {
        StripState {
            logic: tg_ui_logic::TabStrip::new(Vec::new(), 0.0, None),
            widths: Vec::new(),
            // Until the strip is laid out nothing is scrolled.
            viewport: f64::MAX,
        }
    }
}

impl StripState {
    fn sync(&mut self, widths: Vec<f64>, selected: usize) {
        if widths != self.widths {
            self.logic = tg_ui_logic::TabStrip::new(widths.clone(), self.viewport, Some(selected));
            self.widths = widths;
        } else if self.logic.selected() != Some(selected) {
            self.logic.select(selected);
        }
    }

    /// True when the width changed (and the strip must draw again).
    fn set_viewport(&mut self, viewport: f64) -> bool {
        if (self.viewport - viewport).abs() <= 0.01 {
            return false;
        }
        self.viewport = viewport;
        self.logic.set_viewport(viewport);
        if let Some(i) = self.logic.selected() {
            self.logic.select(i);
        }
        true
    }
}

fn strip_key(key: &str) -> Option<Key> {
    Some(match key {
        "left" => Key::Left,
        "right" => Key::Right,
        "home" => Key::Home,
        "end" => Key::End,
        _ => return None,
    })
}

impl RenderOnce for TabStrip {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let colors = tab_colors(&t.theme);
        let extra = if self.closable {
            CLOSE_GAP + CLOSE
        } else {
            0.0
        };
        let widths_dp: Vec<f32> = self
            .labels
            .iter()
            .map(|l| 2.0 * TAB_PAD + label_width(window, &t, l) + extra)
            .collect();
        let widths: Vec<f64> = widths_dp.iter().map(|w| *w as f64).collect();
        let focus = focus_handle(window, cx, &format!("{}.focus", self.id).into(), true);
        let logic: Entity<StripState> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, |_, _| {
                StripState::default()
            })
            .clone();
        // The owner may have changed the tabs or the selection since the last frame.
        logic.update(cx, |s, _| s.sync(widths, self.selected));
        let offset = logic.read(cx).logic.offset() as f32;
        let hover_look = self.hovered;
        let mut row = div()
            .flex()
            .flex_none()
            .h_full()
            .relative()
            .left(t.dp(-offset));
        for (i, label) in self.labels.iter().enumerate() {
            let selected = i == self.selected;
            let (on_select, on_close, on_close_button) = (
                self.on_select.clone(),
                self.on_close.clone(),
                self.on_close.clone(),
            );
            let click_logic = logic.clone();
            let hover = hsla(colors.hover);
            let group: SharedString = format!("{}.tab.{i}", self.id).into();
            let idle = !selected && hover_look != Some(i);
            let tab = div()
                .id(ElementId::Name(format!("{}.tab.{i}", self.id).into()))
                .w(t.dp(widths_dp[i]))
                .debug_selector(move || format!("tab-strip.tab.{i}"))
                .relative()
                .flex()
                .flex_none()
                .items_center()
                .h_full()
                .px(t.dp(TAB_PAD))
                .gap(t.dp(CLOSE_GAP))
                .group(group.clone())
                .when_some(colors.background, |d, c| d.bg(hsla(c)))
                .when(hover_look == Some(i), |d| d.bg(hover))
                .hover(move |s| s.bg(hover))
                .on_click(move |_, window, cx| {
                    click_logic.update(cx, |s, _| s.logic.click(i));
                    window.refresh();
                    if let Some(f) = &on_select {
                        f(i, window, cx);
                    }
                })
                .on_mouse_down(MouseButton::Middle, move |_, window, cx| {
                    if let Some(f) = &on_close {
                        f(i, window, cx);
                    }
                })
                .child(
                    div()
                        .whitespace_nowrap()
                        .when(idle, |d| {
                            d.opacity(IDLE_ALPHA)
                                .group_hover(group.clone(), |s| s.opacity(1.0))
                        })
                        .child(text(label.clone()).color(colors.text)),
                )
                .when(self.closable, |d| {
                    d.child(
                        div()
                            .id(ElementId::Name(format!("{}.close.{i}", self.id).into()))
                            .debug_selector(move || format!("tab-strip.close.{i}"))
                            .size(t.dp(CLOSE))
                            .child(icon("General.CloseSmall").size(CLOSE))
                            .on_click(move |_, window, cx| {
                                cx.stop_propagation();
                                if let Some(f) = &on_close_button {
                                    f(i, window, cx);
                                }
                            }),
                    )
                })
                .when(selected, |d| {
                    d.child(
                        div()
                            .absolute()
                            .left_0()
                            .right_0()
                            .bottom_0()
                            .h(t.dp(UNDERLINE_H))
                            .rounded(t.dp(UNDERLINE_H / 2.0))
                            .bg(hsla(colors.underline)),
                    )
                });
            row = row.child(tab);
        }
        let (key_logic, wheel_logic, size_logic) = (logic.clone(), logic.clone(), logic.clone());
        let on_select = self.on_select.clone();
        let zoom = t.zoom();
        div()
            .id(ElementId::Name(self.id.clone()))
            .debug_selector(|| "tab-strip".into())
            .track_focus(&focus)
            .w_full()
            .h(t.dp(TAB_H))
            .overflow_hidden()
            .child(row)
            .child(
                // Tells the logic how wide the strip is once laid out, so it can keep the selection in view.
                gpui::canvas(
                    move |bounds, window, cx| {
                        let viewport = f64::from(f32::from(bounds.size.width) / zoom);
                        if size_logic.update(cx, |s, _| s.set_viewport(viewport)) {
                            // A refresh during drawing is dropped; ask for the next frame instead.
                            window.request_animation_frame();
                        }
                    },
                    |_, _, _, _| {},
                )
                .absolute()
                .size_full(),
            )
            .on_scroll_wheel(move |event: &ScrollWheelEvent, window, cx| {
                let delta = event.delta.pixel_delta(px(20.0));
                let dx: Pixels = if delta.x.abs() > delta.y.abs() {
                    delta.x
                } else {
                    delta.y
                };
                wheel_logic.update(cx, |s, _| {
                    s.logic.scroll_by(-f64::from(f32::from(dx) / zoom))
                });
                window.refresh();
            })
            .on_key_down(move |event, window, cx| {
                let Some(key) = strip_key(event.keystroke.key.as_str()) else {
                    return;
                };
                let picked = key_logic.update(cx, |s, _| {
                    s.logic.key(key).then(|| s.logic.selected()).flatten()
                });
                if let Some(i) = picked {
                    cx.stop_propagation();
                    window.refresh();
                    if let Some(f) = &on_select {
                        f(i, window, cx);
                    }
                }
            })
    }
}
