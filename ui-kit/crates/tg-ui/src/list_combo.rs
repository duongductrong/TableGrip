//! A non-editable ListComboBox: a 28dp box showing the selected item with a chevron; a click or Space opens the list
//! under it, Up and Down change the selection at once while it is open, Enter or Escape close it
//! (`tg_ui_logic::ListComboBox`). The app owns the selected index and is told of changes.

use std::rc::Rc;

use gpui::{
    App, ElementId, Entity, FocusHandle, InteractiveElement, IntoElement, MouseButton,
    ParentElement, RenderOnce, SharedString, StatefulInteractiveElement, Styled, Window, deferred,
    div, prelude::*,
};
use tg_theme::{Rgba, Theme};
use tg_ui_logic::Input;

use crate::icon::icon;
use crate::look::Look;
use crate::popup::{nav_input, popup_colors, surface};
use crate::ring::{RingSpec, ring};
use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};

pub const HEIGHT: f32 = 28.0;
const RADIUS: f32 = 4.0;
const PAD_LEFT: f32 = 9.0;
const ROW_H: f32 = 24.0;

pub type OnChange = Rc<dyn Fn(usize, &mut Window, &mut App)>;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ComboColors {
    pub background: Rgba,
    pub border: Rgba,
    pub background_disabled: Rgba,
    pub border_disabled: Rgba,
    pub text: TextColor,
    pub text_disabled: TextColor,
    pub chevron_disabled: Rgba,
    pub ring: Rgba,
}

pub fn combo_colors(theme: &Theme) -> ComboColors {
    let dark = theme.is_dark();
    let pick = |d: u32, l: u32| Rgba::rgb(if dark { d } else { l });
    match &theme.colors.controls {
        Some(c) => ComboColors {
            background: c.input_background,
            border: c.input_border,
            background_disabled: c.input_background,
            border_disabled: c.input_border,
            text: TextColor::Custom(c.text),
            // Jewel draws a disabled box's content at half the theme's disabled colour.
            text_disabled: TextColor::Custom(
                c.text_disabled
                    .with_alpha(c.text_disabled.a as f32 / 255.0 * 0.5),
            ),
            chevron_disabled: c
                .text_disabled
                .with_alpha(c.text_disabled.a as f32 / 255.0 * 0.5),
            ring: c.focus,
        },
        None => ComboColors {
            background: pick(0x393B40, 0xFFFFFF),
            border: pick(0x4E5157, 0xC9CCD6),
            background_disabled: pick(0x2B2D30, 0xF7F8FA),
            border_disabled: pick(0x4E5157, 0xDFE1E5),
            text: TextColor::Normal,
            text_disabled: TextColor::Custom(pick(0x4E5157, 0xDFE1E5)),
            chevron_disabled: pick(0x4E5157, 0xDFE1E5),
            ring: theme.ui.outline_focused,
        },
    }
}

#[derive(IntoElement)]
pub struct ListComboBox {
    id: SharedString,
    items: Vec<SharedString>,
    selected: usize,
    enabled: bool,
    width: Option<f32>,
    look: Option<Look>,
    on_change: Option<OnChange>,
}

pub fn list_combo_box(
    id: impl Into<SharedString>,
    items: Vec<SharedString>,
    selected: usize,
) -> ListComboBox {
    ListComboBox {
        id: id.into(),
        items,
        selected,
        enabled: true,
        width: None,
        look: None,
        on_change: None,
    }
}

impl ListComboBox {
    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    /// The box's width in dp; it fills its parent otherwise.
    pub fn width(mut self, dp: f32) -> Self {
        self.width = Some(dp);
        self
    }

    pub fn look(mut self, look: Look) -> Self {
        self.look = Some(look);
        self
    }

    pub fn on_change(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_change = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for ListComboBox {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let colors = combo_colors(&t.theme);
        let popup = popup_colors(&t.theme);
        let focus: FocusHandle = window
            .use_keyed_state(
                ElementId::Name(format!("{}.focus", self.id).into()),
                cx,
                |_, cx| cx.focus_handle(),
            )
            .read(cx)
            .clone()
            .tab_stop(self.enabled);
        let (len, selected, enabled) = (self.items.len(), self.selected, self.enabled);
        let logic: Entity<tg_ui_logic::ListComboBox> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, move |_, _| {
                tg_ui_logic::ListComboBox::new(len, selected)
            })
            .clone();
        logic.update(cx, |l, _| {
            l.len = len;
            l.selected = selected;
            l.enabled = enabled;
        });
        let open = logic.read(cx).is_open();
        let look = self.look.unwrap_or_default();
        let focused = look.focused || (enabled && focus.is_focused(window));
        let (bg, border, fg) = if enabled {
            (colors.background, colors.border, colors.text)
        } else {
            (
                colors.background_disabled,
                colors.border_disabled,
                colors.text_disabled,
            )
        };
        let current = self.items.get(self.selected).cloned().unwrap_or_default();
        let on_change = self.on_change.clone();
        let (click_logic, key_logic) = (logic.clone(), logic.clone());
        let (click_change, key_change) = (on_change.clone(), on_change);
        let (click_focus, id) = (focus.clone(), self.id.clone());
        let mut combo = div()
            .id(ElementId::Name(self.id.clone()))
            .debug_selector({
                let id = id.to_string();
                move || id.clone()
            })
            .relative()
            .flex()
            .items_center()
            .whitespace_nowrap()
            .h(t.dp(HEIGHT))
            .pl(t.dp(PAD_LEFT))
            .pr(t.dp(6.0))
            .rounded(t.dp(RADIUS))
            .border(t.dp(1.0))
            // A focused box's ring covers its border: the ring's inner half is painted under the border otherwise.
            .border_color(hsla(if focused { colors.ring } else { border }))
            .bg(hsla(bg));
        combo = match self.width {
            Some(w) => combo.w(t.dp(w)),
            None => combo.w_full(),
        };
        // The chevron keeps the icon's own colour in every theme; only a disabled box greys it.
        let chevron = if enabled {
            icon("General.ChevronDown").size(16.0)
        } else {
            icon("General.ChevronDown")
                .size(16.0)
                .tint(colors.chevron_disabled)
        };
        combo = combo
            .child(div().flex_1().child(text(current).color(fg)))
            .child(chevron)
            .when(focused, |d| {
                d.child(ring(
                    &t,
                    RingSpec::on_edge(2.0, RADIUS).within_border(1.0),
                    colors.ring,
                ))
            });
        if enabled {
            combo = combo
                .track_focus(&focus)
                .on_mouse_down(MouseButton::Left, move |_, window, _| {
                    window.focus(&click_focus)
                })
                .on_click(move |_, window, cx| {
                    click_logic.update(cx, |l, _| l.handle(&Input::Click));
                    window.refresh();
                })
                .on_key_down(move |event, window, cx| {
                    let Some(input) = nav_input(event) else {
                        return;
                    };
                    cx.stop_propagation();
                    let changed = key_logic.update(cx, |l, _| l.handle(&input)).changed;
                    window.refresh();
                    if let (Some(i), Some(f)) = (changed, &key_change) {
                        f(i, window, cx);
                    }
                });
        }
        if open {
            let click_change = click_change;
            let mut list = surface(&t, &popup)
                .debug_selector(|| "list-combo-box.list".into())
                .absolute()
                .top(t.dp(HEIGHT + 2.0))
                .left_0()
                .w_full()
                .py(t.dp(4.0))
                .px(t.dp(4.0));
            for (i, label) in self.items.iter().enumerate() {
                let (row_logic, on_change) = (logic.clone(), click_change.clone());
                let highlight = hsla(popup.highlight);
                list = list.child(
                    div()
                        .id(ElementId::Name(format!("{}.item.{i}", self.id).into()))
                        .flex()
                        .items_center()
                        .whitespace_nowrap()
                        .h(t.dp(ROW_H))
                        .px(t.dp(6.0))
                        .rounded(t.dp(4.0))
                        .when(i == selected, |d| d.bg(highlight))
                        .hover(move |s| s.bg(highlight))
                        .on_click(move |_, window, cx| {
                            row_logic.update(cx, |l, _| l.selected = i);
                            if let Some(f) = &on_change {
                                f(i, window, cx);
                            }
                            row_logic.update(cx, |l, _| {
                                l.handle(&Input::Click);
                            });
                            window.refresh();
                        })
                        .child(text(label.clone()).color(popup.text)),
                );
            }
            return div()
                .relative()
                .when_some(self.width, |d, w| d.w(t.dp(w)))
                .child(combo)
                .child(deferred(list).with_priority(1))
                .into_any_element();
        }
        combo.into_any_element()
    }
}
