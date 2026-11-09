//! A 24dp icon button, usually wrapped in a tooltip (`Tooltip { IconButton }` in the Kotlin app). It behaves as a
//! button; its background shows on hover and focus, and deepens while pressed. The tooltip follows
//! `tg_ui_logic::Tooltip`: it shows after the pointer has rested on the button for 500 ms, goes away when the pointer
//! leaves or the button is pressed, and keyboard focus never shows it.

use std::rc::Rc;
use std::time::Duration;

use gpui::{
    App, Div, ElementId, Entity, IntoElement, MouseButton, ParentElement, RenderOnce, SharedString,
    Styled, Window, deferred, div, prelude::*,
};
use tg_ui_logic::tooltip::SHOW_DELAY_MS;
use tg_ui_logic::{Input, button::ButtonVariant};

use crate::icon::icon;
use crate::interact::focus_handle;
use crate::look::Look;
use crate::palette::icon_button_colors;
use crate::text::text;
use crate::theme::{ThemeExt, hsla};

pub type OnClick = Rc<dyn Fn(&mut Window, &mut App)>;

const SIZE: f32 = 24.0;
const ICON: f32 = 14.0;
const RADIUS: f32 = 4.0;

#[derive(IntoElement)]
pub struct IconButton {
    id: SharedString,
    icon: &'static str,
    size: f32,
    icon_size: f32,
    enabled: bool,
    tooltip: Option<SharedString>,
    on_click: Option<OnClick>,
    look: Option<Look>,
}

/// An icon button showing the icon [key] (an `AllIconsKeys` name such as `Actions.Execute`).
pub fn icon_button(id: impl Into<SharedString>, key: &'static str) -> IconButton {
    IconButton {
        id: id.into(),
        icon: key,
        size: SIZE,
        icon_size: ICON,
        enabled: true,
        tooltip: None,
        on_click: None,
        look: None,
    }
}

impl IconButton {
    /// The button's side in dp (24 unless a call site sizes it otherwise).
    pub fn size(mut self, dp: f32) -> Self {
        self.size = dp;
        self
    }

    pub fn icon_size(mut self, dp: f32) -> Self {
        self.icon_size = dp;
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn tooltip(mut self, text: impl Into<SharedString>) -> Self {
        self.tooltip = Some(text.into());
        self
    }

    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }

    pub fn look(mut self, look: Look) -> Self {
        self.look = Some(look);
        self
    }
}

/// What the pointer has done on the button, for its tooltip. [`Tooltip`] decides when it shows.
struct TipState {
    logic: tg_ui_logic::Tooltip,
    /// Bumped whenever the pointer enters, leaves or presses, so a timer that was started earlier knows it is stale.
    epoch: u64,
}

/// The tooltip bubble: the popup colours, the text in the UI style.
fn bubble(t: &crate::theme::ActiveTheme, text_: SharedString) -> Div {
    let (background, border) = match &t.theme.colors.controls {
        Some(c) => (c.popup_background, c.popup_border),
        None => (
            tg_theme::Rgba::rgb(if t.theme.is_dark() {
                0x2B2D30
            } else {
                0xFFFFFF
            }),
            tg_theme::Rgba::rgb(if t.theme.is_dark() {
                0x43454A
            } else {
                0xDFE1E5
            }),
        ),
    };
    div()
        .debug_selector(|| "tooltip".into())
        .whitespace_nowrap()
        .px(t.dp(8.0))
        .py(t.dp(4.0))
        .rounded(t.dp(4.0))
        .border(t.dp(1.0))
        .border_color(hsla(border))
        .bg(hsla(background))
        .child(text(text_))
}

fn tip_changed(
    state: &Entity<TipState>,
    window: &mut Window,
    cx: &mut App,
    f: impl FnOnce(&mut TipState),
) -> u64 {
    let epoch = state.update(cx, |s, _| {
        f(s);
        s.epoch += 1;
        s.epoch
    });
    window.refresh();
    epoch
}

fn activated(input: &Input) -> bool {
    let mut logic = tg_ui_logic::IconButton::new();
    logic.button.variant = ButtonVariant::Outlined;
    logic.handle(input).activated
}

impl RenderOnce for IconButton {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let focus = focus_handle(window, cx, &self.id, self.enabled);
        let tip: Entity<TipState> = window
            .use_keyed_state(
                ElementId::Name(format!("{}.tooltip", self.id).into()),
                cx,
                |_, _| TipState {
                    logic: tg_ui_logic::Tooltip::new(),
                    epoch: 0,
                },
            )
            .clone();
        let tip_shown = self.tooltip.is_some() && tip.read(cx).logic.shown();
        let enabled = self.enabled;
        let look = self.look.unwrap_or_default();
        let focused = look.focused || (enabled && focus.is_focused(window));
        let t = cx.theme().clone();
        let colors = icon_button_colors(&t.theme);

        let background = if !enabled {
            None
        } else if look.pressed || look.hovered || focused {
            Some(colors.hovered)
        } else {
            None
        };
        let selector = self.id.to_string();
        let on_click = self.on_click;
        let radius = t.dp(RADIUS);
        let mut el = div()
            .id(ElementId::Name(self.id))
            .debug_selector(move || selector)
            .relative()
            .flex()
            .items_center()
            .justify_center()
            .flex_none()
            .size(t.dp(self.size))
            .rounded(radius)
            .when_some(background, |d, c| d.bg(hsla(c)))
            // A pressed button shows the pressed colour inside a 1dp rim of the hover colour.
            .when(enabled && look.pressed, |d| {
                d.child(
                    div()
                        .absolute()
                        .top(t.dp(1.0))
                        .left(t.dp(1.0))
                        .right(t.dp(1.0))
                        .bottom(t.dp(1.0))
                        .rounded(t.dp(RADIUS - 0.75))
                        .bg(hsla(colors.pressed)),
                )
            })
            .child(if enabled {
                icon(self.icon).size(self.icon_size).crisp()
            } else {
                // A disabled icon is a flat grey silhouette.
                icon(self.icon)
                    .size(self.icon_size)
                    .tint(tg_theme::Rgba::rgb(if t.theme.is_dark() {
                        0x5A5A5A
                    } else {
                        0xC6C6C6
                    }))
            });
        if enabled {
            let key_click = on_click.clone();
            el = el
                .track_focus(&focus)
                .cursor_pointer()
                .hover(move |s| s.bg(hsla(colors.hovered)))
                .active(move |s| s.bg(hsla(colors.pressed)))
                .on_mouse_down(MouseButton::Left, {
                    let focus = focus.clone();
                    move |_, window, _| window.focus(&focus)
                })
                .on_click(move |_, window, cx| {
                    if activated(&Input::Click)
                        && let Some(f) = &on_click
                    {
                        f(window, cx);
                    }
                })
                .on_key_down(move |event, window, cx| {
                    if let Some(input) = crate::link::key_input(event)
                        && activated(&input)
                    {
                        cx.stop_propagation();
                        if let Some(f) = &key_click {
                            f(window, cx);
                        }
                    }
                });
        }
        if let Some(label) = self.tooltip {
            let on_hover_state = tip.clone();
            let on_press_state = tip.clone();
            el = el
                .on_hover(move |hovered, window, cx| {
                    if *hovered {
                        let epoch =
                            tip_changed(&on_hover_state, window, cx, |s| s.logic.pointer_enter());
                        let state = on_hover_state.clone();
                        window
                            .spawn(cx, async move |cx| {
                                cx.background_executor()
                                    .timer(Duration::from_millis(SHOW_DELAY_MS))
                                    .await;
                                cx.update(|window, cx| {
                                    let current = state.read(cx).epoch;
                                    if current == epoch {
                                        state.update(cx, |s, _| s.logic.advance(SHOW_DELAY_MS));
                                        window.refresh();
                                    }
                                })
                                .ok();
                            })
                            .detach();
                    } else {
                        tip_changed(&on_hover_state, window, cx, |s| s.logic.pointer_leave());
                    }
                })
                .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                    tip_changed(&on_press_state, window, cx, |s| s.logic.press());
                })
                .when(tip_shown, |d| {
                    d.child(deferred(
                        bubble(&t, label.clone())
                            .absolute()
                            .top(t.dp(self.size + 4.0))
                            .left_0(),
                    ))
                });
        }
        el
    }
}
