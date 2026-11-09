//! The dialog frame (`AppDialog.kt`, `DialogTitleBar.kt`): a 32dp title bar on the frame colour with the title centred
//! on the *whole* bar (the wider of the two window-control insets on both sides, so it stays centred on the window),
//! then the content as an island inset 4dp from the window's edges, its corners concentric with the window's
//! (14dp minus the inset, at least 4dp). An inset of zero lays the content edge to edge on the panel colour.
//!
//! Esc closes the dialog once nothing inside it took the key (`tg_ui_logic::dialog`).

use std::rc::Rc;

use gpui::{
    AnyElement, App, Div, InteractiveElement, IntoElement, ParentElement, RenderOnce, SharedString,
    Styled, Window, div,
};
use tg_ui_logic::dialog::closes;

use crate::island::island;
use crate::keymap::key_event;
use crate::text::{TextColor, text};
use crate::theme::{ThemeExt, hsla};
use crate::window_chrome::{
    DIALOG_TITLE_BAR_HEIGHT, Os, SharedRegions, insets, interactive, title_bar,
};

/// Gap between a dialog's content island and its edges.
pub const DIALOG_ISLAND_GAP: f32 = 4.0;
/// Roughly the OS's own corner radius for dialogs.
const DIALOG_CORNER_RADIUS: f32 = 14.0;

/// The corner radius of the content island for an `inset`: concentric with the window's corners.
pub fn island_radius(inset: f32) -> f32 {
    (DIALOG_CORNER_RADIUS - inset).max(4.0)
}

/// The dialog's title, centred on the whole bar, semi-bold, one line.
pub fn dialog_title(title: impl Into<SharedString>, os: Os, fullscreen: bool) -> impl IntoElement {
    dialog_title_for(title, Some(os), fullscreen)
}

fn dialog_title_for(
    title: impl Into<SharedString>,
    os: Option<Os>,
    fullscreen: bool,
) -> DialogTitle {
    let i = os.map_or(
        crate::window_chrome::Insets {
            left: 0.0,
            right: 0.0,
        },
        |os| insets(os, fullscreen),
    );
    // The wider inset on both sides, so the title stays centred on the window and not on the free space.
    let inset = i.left.max(i.right);
    DialogTitle {
        title: title.into(),
        inset,
    }
}

#[derive(IntoElement)]
struct DialogTitle {
    title: SharedString,
    inset: f32,
}

impl RenderOnce for DialogTitle {
    fn render(self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme();
        div().max_w_full().px(t.dp(self.inset + 8.0)).child(
            // The title bar's own content colour (Jewel's), which does not follow the theme's text colour.
            text(self.title)
                .color(TextColor::Custom(tg_theme::Rgba::rgb(
                    if t.theme.is_dark() {
                        0xDFE1E5
                    } else {
                        0x000000
                    },
                )))
                .bold()
                .ellipsis(),
        )
    }
}

type CloseHandler = Rc<dyn Fn(&mut Window, &mut App)>;
type KeyHandler = Rc<dyn Fn(&gpui::KeyDownEvent, &mut Window, &mut App) -> bool>;

#[derive(IntoElement)]
pub struct AppDialog {
    os: Os,
    title: SharedString,
    title_bar: Option<AnyElement>,
    regions: SharedRegions,
    inset: f32,
    close_on_escape: bool,
    on_close: CloseHandler,
    on_key: Option<KeyHandler>,
    children: Vec<AnyElement>,
    base: Div,
    bare: bool,
}

/// A dialog frame titled `title`; `on_close` runs when Esc closes it. The window's own title bar is replaced by
/// ours, so open the window with [`window_options`](crate::window_chrome::window_options).
pub fn app_dialog(
    os: Os,
    title: impl Into<SharedString>,
    regions: &SharedRegions,
    on_close: impl Fn(&mut Window, &mut App) + 'static,
) -> AppDialog {
    AppDialog {
        os,
        title: title.into(),
        title_bar: None,
        regions: regions.clone(),
        inset: DIALOG_ISLAND_GAP,
        close_on_escape: true,
        on_close: Rc::new(on_close),
        on_key: None,
        children: Vec::new(),
        base: div(),
        bare: false,
    }
}

impl AppDialog {
    pub fn close_on_escape(mut self, on: bool) -> Self {
        self.close_on_escape = on;
        self
    }

    /// Leaves out the window's own controls and the room they take (the gallery draws only the frame).
    pub fn bare(mut self) -> Self {
        self.bare = true;
        self
    }

    /// Gap between the content and the window's edges; zero lays the content edge to edge, without an island.
    pub fn inset(mut self, dp: f32) -> Self {
        self.inset = dp;
        self
    }

    /// Replaces the centred title, e.g. to add a search field; mark clickable parts with
    /// [`interactive`](crate::window_chrome::interactive).
    pub fn title_bar(mut self, el: impl IntoElement) -> Self {
        self.title_bar = Some(el.into_any_element());
        self
    }

    /// Gets keys first; return true to keep Esc from closing.
    pub fn on_key(
        mut self,
        f: impl Fn(&gpui::KeyDownEvent, &mut Window, &mut App) -> bool + 'static,
    ) -> Self {
        self.on_key = Some(Rc::new(f));
        self
    }
}

impl ParentElement for AppDialog {
    fn extend(&mut self, elements: impl IntoIterator<Item = AnyElement>) {
        self.children.extend(elements);
    }
}

impl Styled for AppDialog {
    fn style(&mut self) -> &mut gpui::StyleRefinement {
        self.base.style()
    }
}

impl RenderOnce for AppDialog {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let t = cx.theme().clone();
        let fullscreen = window.is_fullscreen();
        let mut bar = title_bar(self.os, DIALOG_TITLE_BAR_HEIGHT, &self.regions);
        if self.bare {
            bar = bar.bare();
        }
        let title = self.title_bar.unwrap_or_else(|| {
            let os = if self.bare { None } else { Some(self.os) };
            dialog_title_for(self.title, os, fullscreen).into_any_element()
        });
        let bar = bar.title(title);
        let inset = self.inset;
        let body = div().flex_1().min_h_0().w_full();
        let body = if inset == 0.0 {
            body.bg(hsla(t.theme.colors.islands.island))
                .children(self.children)
        } else {
            body.pl(t.dp(inset)).pr(t.dp(inset)).pb(t.dp(inset)).child(
                island()
                    .size_full()
                    .radius(island_radius(inset))
                    .children(self.children),
            )
        };
        let (on_close, on_key, close_on_escape) =
            (self.on_close, self.on_key, self.close_on_escape);
        self.base
            .id("app-dialog")
            .flex()
            .flex_col()
            .size_full()
            .bg(hsla(t.theme.colors.islands.frame))
            .on_key_down(move |event, window, cx| {
                // This runs after whatever is inside took its turn: a key it took never gets here.
                let taken = on_key.as_ref().is_some_and(|f| f(event, window, cx));
                let ke = key_event(&event.keystroke, true);
                if closes(&ke, taken, close_on_escape) {
                    cx.stop_propagation();
                    on_close(window, cx);
                }
            })
            .child(bar)
            .child(body)
    }
}

/// A button, field or menu in the dialog's title bar that keeps its clicks.
pub fn dialog_interactive(
    regions: &SharedRegions,
    id: impl Into<SharedString>,
    el: impl IntoElement,
) -> Div {
    interactive(regions, id, el)
}
