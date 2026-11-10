//! The always-visible vertical scrollbar the app's lists use (8dp track, a rounded 6dp thumb) and a scrollable
//! container that shows it. Geometry and track clicks follow `tg_ui_logic::Scrollbar`: a click on the track jumps
//! the thumb's top to the click, a drag on the thumb moves it with the pointer.

use std::rc::Rc;

use gpui::{
    App, Bounds, ElementId, Entity, InteractiveElement, IntoElement, MouseButton, ParentElement,
    Pixels, RenderOnce, ScrollHandle, SharedString, StatefulInteractiveElement, Styled, Window,
    canvas, div, point, px,
};
use tg_theme::Rgba;
use tg_ui_logic::scrollbar::TrackClick;

use crate::theme::{ThemeExt, hsla};

pub const TRACK: f32 = 8.0;
const THUMB_INSET: f32 = 1.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ScrollbarColors {
    pub thumb: Rgba,
    pub thumb_hovered: Rgba,
    /// A rim round the dark thumb, 1dp wide, beside the fill rather than under it. Light thumbs have none.
    pub rim: Option<Rgba>,
}

/// The thumb follows only light or dark, never the theme's own palette; it is grey (dark) or black (light) at a fixed alpha.
pub fn scrollbar_colors(theme: &tg_theme::Theme) -> ScrollbarColors {
    if theme.is_dark() {
        ScrollbarColors {
            thumb: Rgba::argb(0x597F7F7F),
            thumb_hovered: Rgba::argb(0x8F7D7D7D),
            rim: Some(Rgba::argb(0x59242425)),
        }
    } else {
        ScrollbarColors {
            thumb: Rgba::argb(0x33000000),
            thumb_hovered: Rgba::argb(0x80000000),
            rim: None,
        }
    }
}

pub type OnScroll = Rc<dyn Fn(f64, &mut Window, &mut App)>;

/// A vertical scrollbar `track` dp long for `content` dp seen through `viewport` dp, scrolled `offset` dp. Fill the
/// height of its parent, which must be `relative()`: it sits on the right edge.
#[derive(IntoElement)]
pub struct Scrollbar {
    id: SharedString,
    viewport: f64,
    content: f64,
    offset: f64,
    hovered: bool,
    on_scroll: Option<OnScroll>,
}

pub fn scrollbar(
    id: impl Into<SharedString>,
    viewport: f64,
    content: f64,
    offset: f64,
) -> Scrollbar {
    Scrollbar {
        id: id.into(),
        viewport,
        content,
        offset,
        hovered: false,
        on_scroll: None,
    }
}

impl Scrollbar {
    /// Paint the thumb as under the pointer, for a static specimen.
    pub fn hovered(mut self, hovered: bool) -> Self {
        self.hovered = hovered;
        self
    }

    pub fn on_scroll(mut self, f: impl Fn(f64, &mut Window, &mut App) + 'static) -> Self {
        self.on_scroll = Some(Rc::new(f));
        self
    }
}

fn logic(s: &Scrollbar) -> tg_ui_logic::Scrollbar {
    let mut l =
        tg_ui_logic::Scrollbar::new(s.viewport, s.content, s.viewport, TrackClick::JumpToSpot);
    l.set_offset(s.offset);
    l
}

/// What the track knows between frames: where it was laid out, whether the pointer is over it and, while the thumb
/// is held, where the pointer took hold of it (dp along the track) and the scroll offset then.
#[derive(Default)]
struct Geo {
    bounds: Bounds<Pixels>,
    hovered: bool,
    grab: Option<(f32, f64)>,
}

impl RenderOnce for Scrollbar {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let colors = scrollbar_colors(&t.theme);
        let l = logic(&self);
        let track = div()
            .id(ElementId::Name(self.id.clone()))
            .debug_selector({
                let id = self.id.to_string();
                move || id.clone()
            })
            .absolute()
            .top_0()
            .right_0()
            .bottom_0()
            .w(t.dp(TRACK));
        if !l.scrollable() {
            return track;
        }
        let geo: Entity<Geo> = window
            .use_keyed_state(
                ElementId::Name(format!("{}.geo", self.id).into()),
                cx,
                |_, _| Geo::default(),
            )
            .clone();
        let (hovered, held) = {
            let g = geo.read(cx);
            (g.hovered, g.grab.is_some())
        };
        let hot = self.hovered || hovered || held;
        let (start, len, max) = (l.thumb_start(), l.thumb_len(), l.max());
        let free = l.track - len;
        let one = t.dp(1.0);
        let offset = self.offset;
        let (down_geo, move_geo, up_geo, hover_geo, bounds_geo) = (
            geo.clone(),
            geo.clone(),
            geo.clone(),
            geo.clone(),
            geo.clone(),
        );
        let (on_down, on_move) = (self.on_scroll.clone(), self.on_scroll.clone());
        let along =
            move |geo: &Entity<Geo>, y: Pixels, cx: &App| (y - geo.read(cx).bounds.origin.y) / one;
        let viewport = self.viewport;
        let content = self.content;
        track
            .on_hover(move |h, window, cx| {
                hover_geo.update(cx, |g, _| g.hovered = *h);
                window.refresh();
            })
            .on_mouse_down(MouseButton::Left, move |event, window, cx| {
                let y = f64::from(along(&down_geo, event.position.y, cx));
                if y >= start && y < start + len {
                    down_geo.update(cx, |g, _| g.grab = Some((y as f32, offset)));
                } else {
                    let mut l = tg_ui_logic::Scrollbar::new(
                        viewport,
                        content,
                        viewport,
                        TrackClick::JumpToSpot,
                    );
                    l.set_offset(offset);
                    l.click_track(y);
                    if let Some(f) = &on_down {
                        f(l.offset, window, cx);
                    }
                }
                window.refresh();
            })
            .on_mouse_move(move |event, window, cx| {
                let Some((from, base)) = move_geo.read(cx).grab else {
                    return;
                };
                if event.pressed_button != Some(MouseButton::Left) {
                    return;
                }
                let y = f64::from(along(&move_geo, event.position.y, cx));
                let next = (base + (y - f64::from(from)) / free * max).clamp(0.0, max);
                if let Some(f) = &on_move {
                    f(next, window, cx);
                }
            })
            .on_mouse_up(MouseButton::Left, move |_, window, cx| {
                up_geo.update(cx, |g, _| g.grab = None);
                window.refresh();
            })
            .child(
                canvas(
                    move |bounds, _, cx| bounds_geo.update(cx, |g, _| g.bounds = bounds),
                    |_, _, _, _| {},
                )
                .absolute()
                .size_full(),
            )
            .child({
                let fill = hsla(if hot {
                    colors.thumb_hovered
                } else {
                    colors.thumb
                });
                let thumb = div()
                    .absolute()
                    .left(t.dp(THUMB_INSET))
                    .right(t.dp(THUMB_INSET))
                    .top(t.dp(start as f32))
                    .h(t.dp(len as f32))
                    .rounded(t.dp(TRACK / 2.0 - THUMB_INSET));
                match colors.rim {
                    Some(rim) => thumb.border(t.dp(1.0)).border_color(hsla(rim)).child(
                        div()
                            .size_full()
                            .rounded(t.dp(TRACK / 2.0 - THUMB_INSET - 1.0))
                            .bg(fill),
                    ),
                    None => thumb.bg(fill),
                }
            })
    }
}

/// A scrollable container: the wheel scrolls it and the scrollbar shows where it is. Give it a size; children are its
/// content, laid out in a column.
#[derive(IntoElement)]
pub struct ScrollArea {
    id: SharedString,
    content: gpui::Div,
}

pub fn scroll_area(id: impl Into<SharedString>, content: gpui::Div) -> ScrollArea {
    ScrollArea {
        id: id.into(),
        content,
    }
}

impl RenderOnce for ScrollArea {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let handle: ScrollHandle = window
            .use_keyed_state(
                ElementId::Name(format!("{}.handle", self.id).into()),
                cx,
                |_, _| ScrollHandle::new(),
            )
            .read(cx)
            .clone();
        let one = t.dp(1.0);
        let viewport = (handle.bounds().size.height / one) as f64;
        let max = (handle.max_offset().height / one) as f64;
        let offset = (-handle.offset().y / one) as f64;
        if viewport <= 0.0 {
            // Not laid out yet: ask for another frame to read the sizes from.
            window.request_animation_frame();
        }
        let set = handle.clone();
        div()
            .relative()
            .size_full()
            .child(
                self.content
                    .id(ElementId::Name(self.id.clone()))
                    .size_full()
                    .overflow_y_scroll()
                    .track_scroll(&handle)
                    .pr(t.dp(TRACK + 2.0)),
            )
            .child(
                scrollbar(
                    format!("{}.track", self.id),
                    viewport,
                    viewport + max,
                    offset,
                )
                .on_scroll(move |v, window, _| {
                    set.set_offset(point(px(0.0), -(one * v as f32)));
                    window.refresh();
                }),
            )
    }
}
