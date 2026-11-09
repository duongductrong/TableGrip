//! The explorer's lazily loaded tree (`tg_ui_logic::LazyTree`), virtualised with GPUI's `uniform_list`: only the rows
//! in view are built, so ten thousand rows scroll as cheaply as ten. The flattened rows are rebuilt only when a node
//! opens or closes or the owner hands over new items (a lazy node's children arriving).
//!
//! Metrics are the explorer's (`ExplorerPanel.explorerTreeStyle`): 26dp rows, the selection a 4dp-round fill inset 6dp
//! from the sides, content padded 4dp inside it, 16dp per level, a 16dp chevron 4dp before the label on nodes, and,
//! with [`Tree::leaf_inset`], leaves pushed right by the chevron's room so their labels line up with their siblings'.
//! The selection fills with the focused or the unfocused selection colour.
//!
//! Pointer: a click selects, Shift+click extends, Cmd/Ctrl+click toggles, a double click flips a node and activates
//! the row. Keys, with focus: the arrows, Home/End, PageUp/PageDown, Cmd/Ctrl+A, as `LazyTree` defines them.

use std::collections::HashSet;
use std::rc::Rc;

use gpui::{
    App, ElementId, Entity, FocusHandle, InteractiveElement, IntoElement, KeyDownEvent,
    MouseButton, MouseDownEvent, ParentElement, RenderOnce, ScrollStrategy, SharedString, Styled,
    UniformListScrollHandle, Window, div, point, prelude::*, uniform_list,
};
use tg_theme::{Rgba, Theme};
use tg_ui_logic::input::{Key, Modifiers};
pub use tg_ui_logic::{Item, TreeEvent};
use tg_ui_logic::{LazyTree, Row};

use crate::icon::icon;
use crate::interact::focus_handle;
use crate::scrollbar::{TRACK, scrollbar};
use crate::text::text;
use crate::theme::{ThemeExt, hsla};

pub const ROW_H: f32 = 26.0;
const ROW_INSET: f32 = 6.0;
const CONTENT_PAD: f32 = 4.0;
const INDENT: f32 = 16.0;
const CHEVRON: f32 = 16.0;
const CHEVRON_GAP: f32 = 4.0;
const RADIUS: f32 = 4.0;

/// Selection fills: Int UI's unless the theme styles controls.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TreeColors {
    pub selected_focused: Rgba,
    pub selected: Rgba,
}

pub fn tree_colors(theme: &Theme) -> TreeColors {
    let dark = theme.is_dark();
    let pick = |d: u32, l: u32| Rgba::rgb(if dark { d } else { l });
    match theme.colors.controls.as_ref() {
        Some(c) => TreeColors {
            selected_focused: c.selection,
            selected: c.selection_inactive,
        },
        None => TreeColors {
            selected_focused: pick(0x2E436E, 0xD4E2FF),
            selected: pick(0x43454A, 0xDFE1E5),
        },
    }
}

pub type Items = Rc<Vec<Item<SharedString>>>;
pub type OnTreeEvent = Rc<dyn Fn(TreeEvent<SharedString>, &mut Window, &mut App)>;

#[derive(IntoElement)]
pub struct Tree {
    id: SharedString,
    items: Items,
    leaf_inset: bool,
    open: Vec<SharedString>,
    selected: Option<SharedString>,
    focused_look: Option<bool>,
    on_event: Option<OnTreeEvent>,
}

/// A tree over `items`, which the owner replaces (a new `Rc`) when lazy children arrive.
pub fn tree(id: impl Into<SharedString>, items: Items) -> Tree {
    Tree {
        id: id.into(),
        items,
        leaf_inset: false,
        open: Vec::new(),
        selected: None,
        focused_look: None,
        on_event: None,
    }
}

impl Tree {
    /// Push leaves right by a chevron's room, as the explorer does.
    pub fn leaf_inset(mut self) -> Self {
        self.leaf_inset = true;
        self
    }

    /// Nodes open when the tree first shows.
    pub fn initially_open(mut self, keys: impl IntoIterator<Item = SharedString>) -> Self {
        self.open = keys.into_iter().collect();
        self
    }

    /// The row selected when the tree first shows.
    pub fn initially_selected(mut self, key: impl Into<SharedString>) -> Self {
        self.selected = Some(key.into());
        self
    }

    /// Draw the selection as focused or not regardless of real focus, for a static specimen.
    pub fn focused_look(mut self, focused: bool) -> Self {
        self.focused_look = Some(focused);
        self
    }

    /// Nodes opening (load their children), closing, and rows activated by a double click.
    pub fn on_event(
        mut self,
        f: impl Fn(TreeEvent<SharedString>, &mut Window, &mut App) + 'static,
    ) -> Self {
        self.on_event = Some(Rc::new(f));
        self
    }
}

/// The tree's state across frames.
pub struct TreeState {
    pub tree: LazyTree<SharedString>,
    items: Items,
    rows: Rc<Vec<Row<SharedString>>>,
    stale: bool,
    scroll: UniformListScrollHandle,
    /// The viewport's height in dp at the last layout.
    viewport: f32,
}

impl TreeState {
    fn rows(&mut self) -> Rc<Vec<Row<SharedString>>> {
        if self.stale {
            self.rows = Rc::new(self.tree.rows(&self.items));
            self.stale = false;
        }
        self.rows.clone()
    }

    fn set_items(&mut self, items: &Items) {
        if !Rc::ptr_eq(&self.items, items) {
            self.items = items.clone();
            self.stale = true;
        }
    }

    /// Runs `f` on the logic and marks the rows stale when a node opened or closed.
    fn act<R>(
        &mut self,
        f: impl FnOnce(&mut LazyTree<SharedString>, &[Item<SharedString>]) -> R,
    ) -> R {
        let items = self.items.clone();
        let open = self.tree.open_keys().count();
        let r = f(&mut self.tree, &items);
        if self.tree.open_keys().count() != open {
            self.stale = true;
        }
        r
    }

    fn reveal_active(&mut self) {
        let rows = self.rows();
        if let Some(i) = self
            .tree
            .active()
            .and_then(|a| rows.iter().position(|r| &r.key == a))
        {
            self.scroll.scroll_to_item(i, ScrollStrategy::Top);
        }
    }
}

fn tree_key(event: &KeyDownEvent) -> Option<(Key, Modifiers)> {
    let key = match event.keystroke.key.as_str() {
        "up" => Key::Up,
        "down" => Key::Down,
        "left" => Key::Left,
        "right" => Key::Right,
        "home" => Key::Home,
        "end" => Key::End,
        "pageup" => Key::PageUp,
        "pagedown" => Key::PageDown,
        "a" => Key::Letter('A'),
        _ => return None,
    };
    Some((key, mods(&event.keystroke.modifiers)))
}

fn mods(m: &gpui::Modifiers) -> Modifiers {
    Modifiers {
        primary: if cfg!(target_os = "macos") {
            m.platform
        } else {
            m.control
        },
        shift: m.shift,
        alt: m.alt,
    }
}

fn emit(
    on_event: &Option<OnTreeEvent>,
    events: Vec<TreeEvent<SharedString>>,
    window: &mut Window,
    cx: &mut App,
) {
    if let Some(f) = on_event {
        for e in events {
            f(e, window, cx);
        }
    }
}

impl RenderOnce for Tree {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let colors = tree_colors(&t.theme);
        let zoom = t.zoom();
        let focus: FocusHandle =
            focus_handle(window, cx, &format!("{}.focus", self.id).into(), true);
        let (items, open, selected) =
            (self.items.clone(), self.open.clone(), self.selected.clone());
        let state: Entity<TreeState> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, move |_, _| {
                let mut tree = LazyTree::new(1);
                tree.open_nodes(open);
                if let Some(key) = selected {
                    tree.click(&key);
                }
                TreeState {
                    tree,
                    items,
                    rows: Rc::new(Vec::new()),
                    stale: true,
                    scroll: UniformListScrollHandle::new(),
                    viewport: 0.0,
                }
            })
            .clone();
        let focused = self
            .focused_look
            .unwrap_or_else(|| focus.is_focused(window));
        let (rows, scroll, viewport) = state.update(cx, |s, _| {
            s.set_items(&self.items);
            // Focus with nothing selected selects the first row.
            if focused && s.tree.selected().next().is_none() && s.tree.active().is_none() {
                s.act(|tree, items| tree.focus(items));
            }
            (s.rows(), s.scroll.clone(), s.viewport)
        });
        let fill = hsla(if focused {
            colors.selected_focused
        } else {
            colors.selected
        });
        let leaf_inset = self.leaf_inset;
        let on_event = self.on_event.clone();
        let id = self.id.clone();
        let list_state = state.clone();
        let list_focus = focus.clone();
        let list_rows = rows.clone();
        let list = uniform_list(
            ElementId::Name(format!("{}.list", self.id).into()),
            rows.len(),
            move |range, _, cx| {
                let t = cx.theme().clone();
                let tree_state = list_state.read(cx);
                let selected: HashSet<&SharedString> = tree_state.tree.selected().collect();
                range
                    .map(|ix| {
                        let row = &list_rows[ix];
                        let is_selected = selected.contains(&row.key);
                        let key = row.key.clone();
                        let (click_state, click_focus, on_event) =
                            (list_state.clone(), list_focus.clone(), on_event.clone());
                        let label_inset = if !row.node && leaf_inset {
                            CHEVRON + CHEVRON_GAP
                        } else {
                            0.0
                        };
                        div()
                            .id(ElementId::Name(format!("{id}.row.{ix}").into()))
                            .debug_selector({
                                let key = key.clone();
                                move || format!("tree.row.{key}")
                            })
                            .h(t.dp(ROW_H))
                            .w_full()
                            .px(t.dp(ROW_INSET))
                            .on_mouse_down(
                                MouseButton::Left,
                                move |e: &MouseDownEvent, window, cx| {
                                    window.focus(&click_focus);
                                    let events = click_state.update(cx, |s, _| {
                                        if e.click_count == 2 {
                                            s.act(|tree, items| tree.double_click(items, &key))
                                        } else {
                                            s.act(|tree, items| {
                                                tree.click_with(items, &key, mods(&e.modifiers))
                                            });
                                            Vec::new()
                                        }
                                    });
                                    emit(&on_event, events, window, cx);
                                    window.refresh();
                                },
                            )
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .size_full()
                                    .rounded(t.dp(RADIUS))
                                    .when(is_selected, |d| d.bg(fill))
                                    .pl(t.dp(CONTENT_PAD + INDENT * row.depth as f32))
                                    .pr(t.dp(CONTENT_PAD))
                                    .when(row.node, |d| {
                                        d.child(
                                            div().flex_none().mr(t.dp(CHEVRON_GAP)).child(
                                                icon(if row.open {
                                                    "General.ChevronDown"
                                                } else {
                                                    "General.ChevronRight"
                                                })
                                                .size(CHEVRON),
                                            ),
                                        )
                                    })
                                    .child(
                                        div()
                                            .ml(t.dp(label_inset))
                                            .whitespace_nowrap()
                                            .child(text(row.key.clone())),
                                    ),
                            )
                    })
                    .collect()
            },
        )
        .size_full()
        .track_scroll(scroll.clone());
        let content = rows.len() as f32 * ROW_H;
        let offset = -f32::from(scroll.0.borrow().base_handle.offset().y) / zoom;
        let (key_state, size_state, bar_scroll) = (state.clone(), state.clone(), scroll.clone());
        let on_event = self.on_event.clone();
        div()
            .id(ElementId::Name(self.id.clone()))
            .debug_selector(|| "tree".into())
            .track_focus(&focus)
            .relative()
            .size_full()
            .child(list)
            .when(viewport > 0.0 && content > viewport, |d| {
                d.child(
                    div()
                        .absolute()
                        .top_0()
                        .right_0()
                        .bottom_0()
                        .w(t.dp(TRACK))
                        .child(
                            scrollbar(
                                format!("{}.bar", self.id),
                                viewport as f64,
                                content as f64,
                                offset as f64,
                            )
                            .on_scroll(move |v, window, _| {
                                bar_scroll
                                    .0
                                    .borrow()
                                    .base_handle
                                    .set_offset(point(gpui::px(0.0), -gpui::px(v as f32 * zoom)));
                                window.refresh();
                            }),
                        ),
                )
            })
            .child(
                gpui::canvas(
                    move |bounds, window, cx| {
                        let h = f32::from(bounds.size.height) / zoom;
                        let changed = size_state.update(cx, |s, _| {
                            s.tree.page = ((h / ROW_H) as usize).max(1);
                            let changed = (s.viewport - h).abs() > 0.01;
                            s.viewport = h;
                            changed
                        });
                        if changed {
                            // A refresh during drawing is dropped; ask for the next frame instead.
                            window.request_animation_frame();
                        }
                    },
                    |_, _, _, _| {},
                )
                .absolute()
                .size_full(),
            )
            .on_key_down(move |event, window, cx| {
                let Some((key, m)) = tree_key(event) else {
                    return;
                };
                let (used, events) = key_state.update(cx, |s, _| {
                    let mut events = Vec::new();
                    let used = s.act(|tree, items| tree.key(items, key, m, &mut events));
                    if used {
                        s.reveal_active();
                    }
                    (used, events)
                });
                if used {
                    cx.stop_propagation();
                    emit(&on_event, events, window, cx);
                    window.refresh();
                }
            })
    }
}
