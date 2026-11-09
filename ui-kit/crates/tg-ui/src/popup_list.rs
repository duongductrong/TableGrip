//! Hand-drawn popup list rows, as the model picker and chat history draw them: the owner takes Up, Down and Enter, a
//! row paints in the highlight colour when it is the keyboard highlight or under the pointer, and a click picks it
//! (`tg_ui_logic::PopupList`).

use std::rc::Rc;

use gpui::{
    App, ElementId, Entity, FocusHandle, InteractiveElement, IntoElement, ParentElement,
    RenderOnce, SharedString, Styled, Window, div, prelude::*,
};

use crate::popup::{nav_input, popup_colors};
use crate::text::text;
use crate::theme::{ThemeExt, hsla};

pub const ROW_H: f32 = 28.0;
const ROW_PAD: f32 = 8.0;

pub type OnPick = Rc<dyn Fn(usize, &mut Window, &mut App)>;

#[derive(IntoElement)]
pub struct PopupList {
    id: SharedString,
    rows: Vec<SharedString>,
    hovered: Option<usize>,
    on_pick: Option<OnPick>,
}

pub fn popup_list(id: impl Into<SharedString>, rows: Vec<SharedString>) -> PopupList {
    PopupList {
        id: id.into(),
        rows,
        hovered: None,
        on_pick: None,
    }
}

impl PopupList {
    /// Paint row `index` as under the pointer, for a static specimen.
    pub fn hovered(mut self, index: usize) -> Self {
        self.hovered = Some(index);
        self
    }

    pub fn on_pick(mut self, f: impl Fn(usize, &mut Window, &mut App) + 'static) -> Self {
        self.on_pick = Some(Rc::new(f));
        self
    }
}

impl RenderOnce for PopupList {
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
        let rows = self.rows.len();
        let logic: Entity<tg_ui_logic::PopupList> = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, move |_, _| {
                tg_ui_logic::PopupList::new(rows)
            })
            .clone();
        let hovered_look = self.hovered;
        let highlight = hsla(colors.highlight);
        let mut list = div()
            .id(ElementId::Name(self.id.clone()))
            .debug_selector(|| "popup-list".into())
            .flex()
            .flex_col()
            .track_focus(&focus);
        for (i, label) in self.rows.iter().enumerate() {
            let painted = logic.read(cx).painted(i) || hovered_look == Some(i);
            let (hover_logic, click_logic, on_pick) =
                (logic.clone(), logic.clone(), self.on_pick.clone());
            list = list.child(
                div()
                    .id(ElementId::Name(format!("{}.row.{i}", self.id).into()))
                    .debug_selector(move || format!("popup-list.row.{i}"))
                    .flex()
                    .items_center()
                    .flex_none()
                    .w_full()
                    .h(t.dp(ROW_H))
                    .px(t.dp(ROW_PAD))
                    .when(painted, |d| d.bg(highlight))
                    .on_hover(move |hovered, window, cx| {
                        hover_logic.update(cx, |l, _| l.hover(hovered.then_some(i)));
                        window.refresh();
                    })
                    .on_click(move |_, window, cx| {
                        click_logic.update(cx, |l, _| l.click(i));
                        window.refresh();
                        if let Some(f) = &on_pick {
                            f(i, window, cx);
                        }
                    })
                    .child(text(label.clone()).color(colors.text)),
            );
        }
        let key_logic = logic.clone();
        let on_pick = self.on_pick.clone();
        list.on_key_down(move |event, window, cx| {
            let Some(input) = nav_input(event) else {
                return;
            };
            cx.stop_propagation();
            let picked = key_logic.update(cx, |l, _| {
                l.handle(&input);
                l.picked()
            });
            window.refresh();
            if matches!(
                input,
                tg_ui_logic::Input::Key(tg_ui_logic::input::Key::Enter, _)
            ) && let (Some(i), Some(f)) = (picked, &on_pick)
            {
                f(i, window, cx);
            }
        })
    }
}
