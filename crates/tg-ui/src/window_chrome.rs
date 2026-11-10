//! The decorated window: a title bar drawn on the frame colour, with what each OS adds to it.
//!
//! * **macOS:** the system draws the traffic lights over the bar's left end (`window_options` positions them), so the
//!   bar leaves [`MAC_LIGHTS`] dp free there; in fullscreen they go away and so does the gap. Double-click zooms or
//!   minimises as the system setting says.
//! * **Windows:** the bar draws the caption buttons (minimise, maximise or restore, close) at its right end and
//!   marks them with `WindowControlArea`, so the system's hit test gives snap layouts and native clicks.
//! * **Linux:** client-side decorations; the same three caption buttons, wired to GPUI's window calls.
//!
//! The bar drags the window by pressing anywhere that is not an [`interactive`](TitleBarRegions) element. Those
//! elements register their bounds in *window* pixels, the way `DialogTitleBar.kt` tells the JetBrains Runtime, and
//! [`TitleBarRegions::is_interactive`] is what decides between "drag" and "click".

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use gpui::{
    AnyElement, App, Bounds, Div, ElementId, Hsla, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Pixels, Point, RenderOnce, SharedString, Styled, TitlebarOptions, Window,
    WindowControlArea, WindowDecorations, WindowOptions, canvas, div, point, prelude::*, px,
};
use tg_theme::Rgba;

use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};

/// Jewel's default title bar height, which is also what macOS lays the traffic lights out by.
pub const TITLE_BAR_HEIGHT: f32 = 40.0;
/// A dialog's title bar.
pub const DIALOG_TITLE_BAR_HEIGHT: f32 = 32.0;
/// Space the traffic lights take at the bar's left end.
pub const MAC_LIGHTS: f32 = 78.0;
/// One caption button's width on Windows (the system's own).
pub const CAPTION_WIDTH: f32 = 46.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Os {
    Mac,
    Windows,
    Linux,
}

impl Os {
    pub fn current() -> Os {
        if cfg!(target_os = "macos") {
            Os::Mac
        } else if cfg!(target_os = "windows") {
            Os::Windows
        } else {
            Os::Linux
        }
    }

    /// Whether the title bar draws its own caption buttons.
    pub fn draws_caption_buttons(self) -> bool {
        self != Os::Mac
    }
}

/// What the window's own controls take from the bar's two ends, in dp.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Insets {
    pub left: f32,
    pub right: f32,
}

pub fn insets(os: Os, fullscreen: bool) -> Insets {
    match os {
        Os::Mac => Insets {
            left: if fullscreen { 0.0 } else { MAC_LIGHTS },
            right: 0.0,
        },
        Os::Windows | Os::Linux => Insets {
            left: 0.0,
            right: 3.0 * CAPTION_WIDTH,
        },
    }
}

/// The options to open a decorated window with: the title bar is ours, so the system's is hidden or transparent.
pub fn window_options(os: Os, title: impl Into<SharedString>, bar_height: f32) -> WindowOptions {
    let title = title.into();
    WindowOptions {
        titlebar: Some(TitlebarOptions {
            title: Some(title),
            appears_transparent: true,
            // The lights are about 14 high; centre them on the bar.
            traffic_light_position: (os == Os::Mac)
                .then(|| point(px(13.0), px((bar_height - 14.0) / 2.0))),
        }),
        window_decorations: (os == Os::Linux).then_some(WindowDecorations::Client),
        ..Default::default()
    }
}

/// An axis-aligned rectangle in window pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PxRect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl PxRect {
    pub fn contains(&self, x: f32, y: f32) -> bool {
        x >= self.x && x < self.x + self.w && y >= self.y && y < self.y + self.h
    }
}

impl From<Bounds<Pixels>> for PxRect {
    fn from(b: Bounds<Pixels>) -> PxRect {
        PxRect {
            x: b.origin.x.into(),
            y: b.origin.y.into(),
            w: b.size.width.into(),
            h: b.size.height.into(),
        }
    }
}

/// Where a title bar's interactive elements are, in window pixels. Press anywhere else on the bar and the window drags.
#[derive(Default)]
pub struct TitleBarRegions {
    bounds: HashMap<SharedString, PxRect>,
}

impl TitleBarRegions {
    pub fn is_interactive(&self, x: f32, y: f32) -> bool {
        self.bounds.values().any(|r| r.contains(x, y))
    }

    pub fn set(&mut self, owner: impl Into<SharedString>, rect: PxRect) {
        self.bounds.insert(owner.into(), rect);
    }

    pub fn remove(&mut self, owner: &str) {
        self.bounds.remove(owner);
    }

    /// Every registered rectangle, for tests and debugging.
    pub fn debug_rects(&self) -> Vec<(SharedString, PxRect)> {
        self.bounds.iter().map(|(k, v)| (k.clone(), *v)).collect()
    }

    pub fn len(&self) -> usize {
        self.bounds.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bounds.is_empty()
    }
}

/// The regions a bar shares with the elements marked interactive in it.
pub type SharedRegions = Rc<RefCell<TitleBarRegions>>;

/// Keeps clicks on `el` instead of letting them drag the window; put it on buttons, fields and menus.
pub fn interactive(
    regions: &SharedRegions,
    id: impl Into<SharedString>,
    el: impl IntoElement,
) -> Div {
    let id: SharedString = id.into();
    let regions = regions.clone();
    div().relative().child(el).child(
        canvas(
            |bounds, _, _| bounds,
            move |_, bounds, _, _| {
                regions.borrow_mut().set(id.clone(), bounds.into());
            },
        )
        .absolute()
        .inset_0(),
    )
}

/// What a press on a title bar does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Press {
    /// An interactive element has it.
    Click,
    /// Moves the window.
    Drag,
    /// A double click on the bare bar: zoom or minimise.
    ZoomToggle,
}

/// Decides what a left press at window pixel (`x`, `y`) with `click_count` clicks does.
pub fn press_on_bar(regions: &TitleBarRegions, x: f32, y: f32, click_count: usize) -> Press {
    if regions.is_interactive(x, y) {
        Press::Click
    } else if click_count == 2 {
        Press::ZoomToggle
    } else {
        Press::Drag
    }
}

/// A title bar: [left] content after the traffic-light gap, the [title] centred on the whole bar, [right] content
/// before the caption buttons.
#[derive(IntoElement)]
pub struct TitleBar {
    os: Os,
    height: f32,
    title: Option<AnyElement>,
    left: Option<AnyElement>,
    right: Option<AnyElement>,
    regions: SharedRegions,
    fullscreen: bool,
    background: Option<Rgba>,
    controls: bool,
}

/// A bar of `height` dp for `os`; share `regions` with the [`interactive`] elements placed in it.
pub fn title_bar(os: Os, height: f32, regions: &SharedRegions) -> TitleBar {
    TitleBar {
        os,
        height,
        title: None,
        left: None,
        right: None,
        regions: regions.clone(),
        fullscreen: false,
        background: None,
        controls: true,
    }
}

impl TitleBar {
    pub fn title(mut self, title: impl IntoElement) -> Self {
        self.title = Some(title.into_any_element());
        self
    }

    pub fn left(mut self, el: impl IntoElement) -> Self {
        self.left = Some(el.into_any_element());
        self
    }

    pub fn right(mut self, el: impl IntoElement) -> Self {
        self.right = Some(el.into_any_element());
        self
    }

    /// Leaves out the window's own controls and the room they take (the gallery draws only the bar's content).
    pub fn bare(mut self) -> Self {
        self.controls = false;
        self
    }

    /// The window is fullscreen: macOS has hidden the traffic lights.
    pub fn fullscreen(mut self, fullscreen: bool) -> Self {
        self.fullscreen = fullscreen;
        self
    }
}

/// The centred title of a main window: info colour.
pub fn bar_title(title: impl Into<SharedString>) -> impl IntoElement {
    text(title).color(TextColor::Info)
}

impl RenderOnce for TitleBar {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let inset = if self.controls {
            insets(self.os, self.fullscreen || window.is_fullscreen())
        } else {
            Insets {
                left: 0.0,
                right: 0.0,
            }
        };
        let regions = self.regions.clone();
        let os = self.os;
        let background = self.background.unwrap_or(t.theme.colors.islands.frame);
        div()
            .id("title-bar")
            .debug_selector(|| "title-bar".into())
            .relative()
            .flex()
            .items_center()
            .flex_none()
            .w_full()
            .h(t.dp(self.height))
            .bg(hsla(background))
            .window_control_area(WindowControlArea::Drag)
            .on_mouse_down(MouseButton::Left, move |event, window, _| {
                let (x, y): (f32, f32) = (event.position.x.into(), event.position.y.into());
                match press_on_bar(&regions.borrow(), x, y, event.click_count) {
                    Press::Click => {}
                    Press::ZoomToggle => match os {
                        Os::Mac => window.titlebar_double_click(),
                        _ => window.zoom_window(),
                    },
                    Press::Drag => window.start_window_move(),
                }
            })
            .child(
                div()
                    .flex()
                    .flex_1()
                    .min_w_0()
                    .items_center()
                    .pl(t.dp(inset.left + 4.0))
                    .children(self.left),
            )
            .child(
                div()
                    .flex()
                    .flex_none()
                    .items_center()
                    .pr(t.dp(6.0))
                    .children(self.right),
            )
            .when(self.controls && os.draws_caption_buttons(), |d| {
                d.child(caption_buttons(os, self.height, window.is_maximized(), cx))
            })
            .when_some(self.title, |d, title| {
                d.child(
                    div()
                        .absolute()
                        .inset_0()
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(title),
                )
            })
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Caption {
    Minimize,
    Maximize,
    Close,
}

fn caption_buttons(os: Os, height: f32, maximized: bool, cx: &App) -> Div {
    let t = cx.theme();
    div()
        .flex()
        .flex_none()
        .h_full()
        .children(
            [Caption::Minimize, Caption::Maximize, Caption::Close]
                .map(|kind| caption_button(os, kind, height, maximized, cx)),
        )
        .w(t.dp(3.0 * CAPTION_WIDTH))
}

fn caption_button(os: Os, kind: Caption, _height: f32, maximized: bool, cx: &App) -> AnyElement {
    let t = cx.theme();
    let hover = if kind == Caption::Close {
        Rgba::rgb(0xE81123)
    } else {
        t.theme.colors.islands.stripe_hover
    };
    let (fg, fg_hover): (Hsla, Hsla) = (
        hsla(t.theme.ui.text),
        if kind == Caption::Close {
            hsla(Rgba::rgb(0xFFFFFF))
        } else {
            hsla(t.theme.ui.text)
        },
    );
    let area = match kind {
        Caption::Minimize => WindowControlArea::Min,
        Caption::Maximize => WindowControlArea::Max,
        Caption::Close => WindowControlArea::Close,
    };
    let id = match kind {
        Caption::Minimize => "caption.minimize",
        Caption::Maximize => "caption.maximize",
        Caption::Close => "caption.close",
    };
    let glyph_size = t.dp(10.0);
    div()
        .id(ElementId::Name(id.into()))
        .debug_selector(move || id.into())
        .flex()
        .items_center()
        .justify_center()
        .h_full()
        .w(t.dp(CAPTION_WIDTH))
        .window_control_area(area)
        .hover(move |s| s.bg(hsla(hover)))
        // On Windows the system's hit test acts on the area; elsewhere GPUI's window calls do.
        .when(os != Os::Windows, |d| {
            d.cursor_pointer().on_click(move |_, window, _| match kind {
                Caption::Minimize => window.minimize_window(),
                Caption::Maximize => window.zoom_window(),
                Caption::Close => window.remove_window(),
            })
        })
        .child(
            canvas(
                |bounds, _, _| bounds,
                move |_, bounds, window, _| {
                    paint_glyph(kind, maximized, bounds, glyph_size, fg, window)
                },
            )
            .size(glyph_size),
        )
        .text_color(fg_hover)
        .into_any_element()
}

/// The caption glyphs, 10dp: a line, a square (two when restoring), a cross.
fn paint_glyph(
    kind: Caption,
    maximized: bool,
    bounds: Bounds<Pixels>,
    size: Pixels,
    color: Hsla,
    window: &mut Window,
) {
    use gpui::PathBuilder;
    let stroke = |window: &mut Window, points: &[Point<Pixels>], close: bool| {
        let mut b = PathBuilder::stroke(px(1.0));
        b.move_to(points[0]);
        for p in &points[1..] {
            b.line_to(*p);
        }
        if close {
            b.close();
        }
        if let Ok(path) = b.build() {
            window.paint_path(path, color);
        }
    };
    let o = bounds.origin;
    let s: f32 = size.into();
    let at = |x: f32, y: f32| point(o.x + px(x), o.y + px(y));
    match kind {
        Caption::Minimize => stroke(window, &[at(0.0, s / 2.0), at(s, s / 2.0)], false),
        Caption::Maximize if maximized => {
            let (a, b) = (s - 2.0, 2.0);
            stroke(window, &[at(0.0, b), at(a, b), at(a, s), at(0.0, s)], true);
            stroke(
                window,
                &[at(b, b), at(b, 0.0), at(s, 0.0), at(s, a), at(a, a)],
                false,
            );
        }
        Caption::Maximize => stroke(
            window,
            &[
                at(0.5, 0.5),
                at(s - 0.5, 0.5),
                at(s - 0.5, s - 0.5),
                at(0.5, s - 0.5),
            ],
            true,
        ),
        Caption::Close => {
            stroke(window, &[at(0.0, 0.0), at(s, s)], false);
            stroke(window, &[at(s, 0.0), at(0.0, s)], false);
        }
    }
}
