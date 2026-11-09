//! A PopupMenu: selectable items (some disabled) with separators between groups. Opening highlights the selected
//! item; Up and Down move over enabled items, Enter or Space chooses, Escape, a click outside or a choice closes
//! (`tg_ui_logic::PopupMenu`). The owner keeps the open flag: render the menu while it is open and drop it on
//! `on_dismiss`.

use std::rc::Rc;

use gpui::{
    App, Bounds, Corner, ElementId, Entity, FocusHandle, InteractiveElement, IntoElement,
    MouseButton, ParentElement, Pixels, RenderOnce, SharedString, Stateful, Styled, Window,
    anchored, deferred, div, point, prelude::*,
};

use crate::popup::{BORDER, nav_input, place, popup_colors, surface};
use crate::text::text;
use crate::theme::{ThemeExt, hsla};

const PAD_V: f32 = 6.0;
const PAD_H: f32 = 7.0;
const ITEM_H: f32 = 23.5;
const ITEM_PAD: f32 = 6.0;
const SEPARATOR_H: f32 = 9.0;
const ITEM_RADIUS: f32 = 4.0;

pub type OnChoose = Rc<dyn Fn(usize, &mut Window, &mut App)>;
pub type OnDismiss = Rc<dyn Fn(&mut Window, &mut App)>;

#[derive(Clone, Debug)]
pub struct MenuItem {
    pub label: SharedString,
    pub enabled: bool,
    pub separator_after: bool,
}

pub fn menu_item(label: impl Into<SharedString>) -> MenuItem {
    MenuItem {
        label: label.into(),
        enabled: true,
        separator_after: false,
    }
}

impl MenuItem {
    pub fn disabled(mut self) -> Self {
        self.enabled = false;
        self
    }

    pub fn separator_after(mut self) -> Self {
        self.separator_after = true;
        self
    }
}

#[derive(IntoElement)]
pub struct PopupMenu {
    id: SharedString,
    items: Vec<MenuItem>,
    selected: Option<usize>,
    hovered: Option<usize>,
    anchor: Option<Bounds<Pixels>>,
    on_choose: Option<OnChoose>,
    on_dismiss: Option<OnDismiss>,
}

pub fn popup_menu(id: impl Into<SharedString>, items: Vec<MenuItem>) -> PopupMenu {
    PopupMenu {
        id: id.into(),
        items,
        selected: Some(0),
        hovered: None,
        anchor: None,
        on_choose: None,
        on_dismiss: None,
    }
}

impl PopupMenu {
    /// The current choice, highlighted when the menu opens (the first item when none).
    pub fn selected(mut self, index: Option<usize>) -> Self {
        self.selected = index;
        self
    }

    /// Paint item `index` as under the pointer, for a static specimen.
    pub fn hovered(mut self, index: usize) -> Self {
        self.hovered = Some(index);
        self
    }

    /// Open from a trigger with these window bounds: below it, or above it at the bottom edge of the window.
    /// Without it the menu is laid out in place.
    pub fn below(mut self, trigger: Bounds<Pixels>) -> Self {
        self.anchor = Some(trigger);
        self
    }

    pub fn on_choose(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_choose = Some(Rc::new(f));
        self
    }

    pub fn on_dismiss(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_dismiss = Some(Rc::new(f));
        self
    }

    /// The menu's height in dp: borders, padding, items and separators.
    pub fn height_dp(&self) -> f32 {
        let seps = self.items.iter().filter(|i| i.separator_after).count();
        2.0 * BORDER + 2.0 * PAD_V + ITEM_H * self.items.len() as f32 + SEPARATOR_H * seps as f32
    }
}

impl RenderOnce for PopupMenu {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let colors = popup_colors(&t.theme);
        let focus: FocusHandle = window
            .use_keyed_state(
                ElementId::Name(format!("{}.focus", self.id).into()),
                cx,
                |_, cx| cx.focus_handle(),
            )
            .read(cx)
            .clone();
        let enabled: Vec<bool> = self.items.iter().map(|i| i.enabled).collect();
        let (selected, focus_init) = (self.selected, focus.clone());
        let logic: Entity<tg_ui_logic::PopupMenu> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, move |window, _| {
                let mut menu = tg_ui_logic::PopupMenu::new(enabled, selected);
                menu.open();
                window.focus(&focus_init);
                menu
            })
            .clone();
        if !logic.read(cx).is_open() {
            return div().into_any_element();
        }
        let pointer: Entity<Option<usize>> = window
            .use_keyed_state(
                ElementId::Name(format!("{}.pointer", self.id).into()),
                cx,
                |_, _| None,
            )
            .clone();
        // The pointer takes the highlight from the keyboard while it is over an item, as it does in the Kotlin app.
        let highlighted = (*pointer.read(cx))
            .or(self.hovered)
            .or(logic.read(cx).highlighted());
        let height = self.height_dp();
        let last = self.items.len().saturating_sub(1);

        let mut body = surface(&t, &colors)
            .debug_selector(|| "popup-menu".into())
            .py(t.dp(PAD_V))
            .px(t.dp(PAD_H));
        for (i, item) in self.items.iter().enumerate() {
            let painted = item.enabled && highlighted == Some(i);
            let (logic_c, on_choose, on_dismiss) = (
                logic.clone(),
                self.on_choose.clone(),
                self.on_dismiss.clone(),
            );
            let highlight = hsla(colors.highlight);
            let mut row: Stateful<_> = div()
                .id(ElementId::Name(format!("{}.item.{i}", self.id).into()))
                .debug_selector(move || format!("popup-menu.item.{i}"))
                .flex()
                .items_center()
                .flex_none()
                .whitespace_nowrap()
                .h(t.dp(ITEM_H))
                .px(t.dp(ITEM_PAD))
                .rounded(t.dp(ITEM_RADIUS))
                .when(painted, |d| d.bg(highlight))
                .child(text(item.label.clone()).color(if item.enabled {
                    colors.text
                } else {
                    colors.text_disabled
                }));
            if item.enabled {
                let pointer = pointer.clone();
                row = row
                    .on_hover(move |over, window, cx| {
                        pointer.update(cx, |p, _| {
                            if *over {
                                *p = Some(i);
                            } else if *p == Some(i) {
                                *p = None;
                            }
                        });
                        window.refresh();
                    })
                    .on_click(move |_, window, cx| {
                        let chosen = logic_c.update(cx, |m, _| m.click_item(i)).chosen;
                        window.refresh();
                        if let (Some(i), Some(f)) = (chosen, &on_choose) {
                            f(i, window, cx);
                        }
                        if let Some(f) = &on_dismiss {
                            f(window, cx);
                        }
                    });
            }
            body = body.child(row);
            if item.separator_after && i != last {
                body = body.child(
                    div()
                        .flex_none()
                        .h(t.dp(SEPARATOR_H))
                        .px(t.dp(ITEM_PAD))
                        .flex()
                        .items_center()
                        .child(div().w_full().h(t.dp(1.0)).bg(hsla(colors.separator))),
                );
            }
        }

        let (key_logic, key_choose, key_dismiss) = (
            logic.clone(),
            self.on_choose.clone(),
            self.on_dismiss.clone(),
        );
        let (out_logic, out_dismiss) = (logic.clone(), self.on_dismiss.clone());
        let body = body
            .id(ElementId::Name(format!("{}.menu", self.id).into()))
            .track_focus(&focus)
            .on_key_down(move |event, window, cx| {
                let Some(input) = nav_input(event) else {
                    return;
                };
                cx.stop_propagation();
                let (chosen, open) = key_logic.update(cx, |m, _| {
                    let r = m.handle(&input);
                    (r.chosen, m.is_open())
                });
                window.refresh();
                if let (Some(i), Some(f)) = (chosen, &key_choose) {
                    f(i, window, cx);
                }
                if !open && let Some(f) = &key_dismiss {
                    f(window, cx);
                }
            })
            .on_mouse_down_out(move |_, window, cx| {
                out_logic.update(cx, |m, _| m.click_outside());
                window.refresh();
                if let Some(f) = &out_dismiss {
                    f(window, cx);
                }
            })
            .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation());

        match self.anchor {
            None => body.into_any_element(),
            Some(trigger) => {
                // Width is not known before layout: slide in horizontally with the window's own snapping, and decide
                // above or below from the height, which is.
                let size = gpui::size(t.dp(160.0), t.dp(height));
                let (origin, flipped) = place(
                    (trigger.origin, trigger.size),
                    size,
                    window.viewport_size(),
                    t.dp(2.0),
                );
                let corner = if flipped {
                    Corner::BottomLeft
                } else {
                    Corner::TopLeft
                };
                let at = if flipped {
                    point(origin.x, origin.y + t.dp(height))
                } else {
                    origin
                };
                deferred(
                    anchored()
                        .position(at)
                        .anchor(corner)
                        .snap_to_window_with_margin(t.dp(4.0))
                        .child(body),
                )
                .with_priority(1)
                .into_any_element()
            }
        }
    }
}
