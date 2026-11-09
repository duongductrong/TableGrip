//! A text button in the link colour (`Link(text, onClick)`): underlined while hovered, a 2dp ring in the focus colour
//! drawn outside its bounds while focused, the disabled text colour when disabled. Behaviour comes from
//! `tg_ui_logic::Link`.

use std::rc::Rc;

use gpui::{
    App, ElementId, FocusHandle, IntoElement, KeyDownEvent, RenderOnce, SharedString, Window, div,
    prelude::*,
};
use tg_ui_logic::{Input, Key, Modifiers};

use crate::theme::{ThemeExt, hsla};

/// Runs when the link is activated.
pub type OnClick = Rc<dyn Fn(&mut Window, &mut App)>;

/// A look to draw regardless of the pointer and focus, for the gallery's static specimens.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkLook {
    Hovered,
    Focused,
}

#[derive(IntoElement)]
pub struct Link {
    id: SharedString,
    label: SharedString,
    enabled: bool,
    on_click: Option<OnClick>,
    look: Option<LinkLook>,
}

/// A link labelled [label]; [id] keeps its focus across frames and names it for tests (`debug_selector`).
pub fn link(id: impl Into<SharedString>, label: impl Into<SharedString>) -> Link {
    Link {
        id: id.into(),
        label: label.into(),
        enabled: true,
        on_click: None,
        look: None,
    }
}

impl Link {
    pub fn on_click(mut self, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        self.on_click = Some(Rc::new(f));
        self
    }

    pub fn enabled(mut self, enabled: bool) -> Self {
        self.enabled = enabled;
        self
    }

    pub fn look(mut self, look: LinkLook) -> Self {
        self.look = Some(look);
        self
    }
}

/// The focus ring's stroke and outer corner radius, in dp, as measured from the Kotlin app's render.
const RING_WIDTH: f32 = 2.0;
const RING_RADIUS: f32 = 3.5;

fn handle(enabled: bool, input: &Input) -> tg_ui_logic::link::LinkResponse {
    let mut logic = tg_ui_logic::Link::new();
    logic.enabled = enabled;
    logic.handle(input)
}

pub(crate) fn key_input(event: &KeyDownEvent) -> Option<Input> {
    let key = match event.keystroke.key.as_str() {
        "space" => Key::Space,
        "enter" => Key::Enter,
        _ => return None,
    };
    let m = &event.keystroke.modifiers;
    Some(Input::Key(
        key,
        Modifiers {
            primary: if cfg!(target_os = "macos") {
                m.platform
            } else {
                m.control
            },
            shift: m.shift,
            alt: m.alt,
        },
    ))
}

impl RenderOnce for Link {
    fn render(self, window: &mut Window, cx: &mut App) -> impl IntoElement {
        let focus: FocusHandle = window
            .use_keyed_state(ElementId::Name(self.id.clone()), cx, |_, cx| {
                cx.focus_handle()
            })
            .read(cx)
            .clone()
            .tab_stop(self.enabled);
        let focused =
            self.look == Some(LinkLook::Focused) || (self.enabled && focus.is_focused(window));
        let hovered = self.look == Some(LinkLook::Hovered);
        let t = cx.theme().clone();
        let ui = &t.theme.ui;
        let (size, line) = (
            t.dp(t.theme.fonts.ui_size),
            t.dp(t.theme.fonts.ui_line_height),
        );
        let trim = crate::text::leading_trim(cx, &t.ui_font, size, line);
        let color = if self.enabled {
            ui.link
        } else {
            ui.link_disabled
        };
        let ring = t.dp(RING_WIDTH);
        let enabled = self.enabled;
        let on_click = self.on_click;

        let selector = self.id.to_string();
        let mut el = div()
            .id(ElementId::Name(self.id))
            .debug_selector(move || selector)
            .relative()
            .font_family(t.ui_font.clone())
            .text_size(size)
            .line_height(line)
            .my(-trim)
            .text_color(hsla(color))
            .when(hovered, |d| d.underline())
            .child(self.label)
            .when(focused, |d| {
                d.child(
                    div()
                        .absolute()
                        // Around the trimmed text box, as Compose lays the link out.
                        .top(trim - ring)
                        .left(-ring)
                        .right(-ring)
                        .bottom(trim - ring)
                        .border(ring)
                        .border_color(hsla(ui.outline_focused))
                        .rounded(t.dp(RING_RADIUS)),
                )
            });
        if enabled {
            let key_click = on_click.clone();
            el = el
                .track_focus(&focus)
                .cursor_pointer()
                .hover(|s| s.underline())
                .on_click({
                    let focus = focus.clone();
                    move |_, window, cx| {
                        let r = handle(true, &Input::Click);
                        if r.focus {
                            window.focus(&focus);
                        }
                        if r.activated
                            && let Some(f) = &on_click
                        {
                            f(window, cx);
                        }
                    }
                })
                .on_key_down(move |event, window, cx| {
                    if let Some(input) = key_input(event)
                        && handle(true, &input).activated
                    {
                        cx.stop_propagation();
                        if let Some(f) = &key_click {
                            f(window, cx);
                        }
                    }
                });
        }
        el
    }
}
