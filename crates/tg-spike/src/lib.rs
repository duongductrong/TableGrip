//! Phase 0 spike: can GPUI carry TableGrip's look and behaviour? One island with a checkbox and a button drawn in
//! Graphite Dark (sizes and colours from `conformance/golden/graphite-dark.json`), their behaviour delegated to
//! `tg-ui-logic`, a shortcut bound through GPUI's keymap, and a custom title bar.
//!
//! This is throwaway code to answer the stack question; the real components start in Phase 1.

use gpui::{
    App, Context, FocusHandle, Hsla, KeyBinding, KeyDownEvent, Render, SharedString, Window,
    actions, div, prelude::*, px, rgb,
};
use tg_ui_logic::button::ButtonVariant;
use tg_ui_logic::{Button, Checkbox, Input, Key, Modifiers};

actions!(spike, [ToggleReadOnly, FocusNext, FocusPrev]);

/// Graphite Dark, as measured from the Kotlin app.
mod graphite_dark {
    pub const FRAME: u32 = 0x2B2D30;
    pub const ISLAND: u32 = 0x1E1F22;
    pub const ACCENT: u32 = 0x3574F0;
    pub const TEXT: u32 = 0xDFE1E5;
    pub const OUTLINED_BORDER: u32 = 0x4E5157;
    pub const BOX_BORDER: u32 = 0x5A5D63;
    pub const BOX_FILL: u32 = 0x2B2D30;
}

/// The keymap: `primary` is Cmd on macOS and Ctrl elsewhere.
pub fn bind_keys(cx: &mut App) {
    let primary = if cfg!(target_os = "macos") {
        "cmd"
    } else {
        "ctrl"
    };
    cx.bind_keys([
        KeyBinding::new(&format!("{primary}-k"), ToggleReadOnly, None),
        KeyBinding::new("tab", FocusNext, None),
        KeyBinding::new("shift-tab", FocusPrev, None),
    ]);
}

pub struct Spike {
    pub read_only: Checkbox,
    pub save: Button,
    pub saves: u32,
    pub checkbox_focus: FocusHandle,
    pub button_focus: FocusHandle,
}

impl Spike {
    pub fn new(cx: &mut Context<Self>) -> Self {
        Spike {
            read_only: Checkbox::new(false),
            save: Button::new(ButtonVariant::Default),
            saves: 0,
            checkbox_focus: cx.focus_handle().tab_index(1).tab_stop(true),
            button_focus: cx.focus_handle().tab_index(2).tab_stop(true),
        }
    }

    fn checkbox_input(&mut self, input: Input, window: &mut Window, cx: &mut Context<Self>) {
        if self.read_only.handle(&input).focus {
            window.focus(&self.checkbox_focus);
        }
        cx.notify();
    }

    fn button_input(&mut self, input: Input, window: &mut Window, cx: &mut Context<Self>) {
        let response = self.save.handle(&input);
        self.saves += response.activated as u32;
        if response.focus {
            window.focus(&self.button_focus);
        }
        cx.notify();
    }
}

/// A GPUI key press as `tg-ui-logic` input; None for keys the components don't use.
fn input_of(event: &KeyDownEvent) -> Option<Input> {
    let key = match event.keystroke.key.as_str() {
        "space" => Key::Space,
        "enter" => Key::Enter,
        _ => return None,
    };
    let m = &event.keystroke.modifiers;
    let primary = if cfg!(target_os = "macos") {
        m.platform
    } else {
        m.control
    };
    Some(Input::Key(
        key,
        Modifiers {
            primary,
            shift: m.shift,
            alt: m.alt,
        },
    ))
}

/// The 2dp ring drawn outside a focused control.
fn focus_ring(focused: bool) -> gpui::Div {
    div().rounded(px(6.)).border_2().border_color(if focused {
        rgb(graphite_dark::ACCENT).into()
    } else {
        Hsla::transparent_black()
    })
}

impl Render for Spike {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        use graphite_dark::*;
        let checked = self.read_only.checked;
        let checkbox_focused = self.checkbox_focus.is_focused(window);
        let button_focused = self.button_focus.is_focused(window);

        let checkbox = div()
            .id("checkbox")
            .debug_selector(|| "checkbox".into())
            .track_focus(&self.checkbox_focus)
            .h(px(24.))
            .flex()
            .items_center()
            .gap(px(6.))
            .px(px(4.))
            .on_click(
                cx.listener(|this, _, window, cx| this.checkbox_input(Input::Click, window, cx)),
            )
            .on_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
                if let Some(input) = input_of(event) {
                    this.checkbox_input(input, window, cx);
                }
            }))
            .child(
                focus_ring(checkbox_focused).child(
                    div()
                        .size(px(16.))
                        .rounded(px(3.))
                        .border_1()
                        .border_color(rgb(if checked { ACCENT } else { BOX_BORDER }))
                        .bg(rgb(if checked { ACCENT } else { BOX_FILL }))
                        .flex()
                        .items_center()
                        .justify_center()
                        .text_color(rgb(0xFFFFFF))
                        .text_size(px(11.))
                        .when(checked, |d| d.child("✓")),
                ),
            )
            .child("Read-only");

        let button = focus_ring(button_focused).child(
            div()
                .id("save")
                .debug_selector(|| "save".into())
                .track_focus(&self.button_focus)
                .h(px(28.))
                .w(px(72.))
                .rounded(px(4.))
                .bg(rgb(ACCENT))
                .text_color(rgb(0xFFFFFF))
                .flex()
                .items_center()
                .justify_center()
                .on_click(
                    cx.listener(|this, _, window, cx| this.button_input(Input::Click, window, cx)),
                )
                .on_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
                    if let Some(input) = input_of(event) {
                        this.button_input(input, window, cx);
                    }
                }))
                .child("Save"),
        );

        let outlined = div()
            .id("cancel")
            .h(px(28.))
            .w(px(72.))
            .rounded(px(4.))
            .border_1()
            .border_color(rgb(OUTLINED_BORDER))
            .hover(|d| d.bg(rgb(FRAME)))
            .flex()
            .items_center()
            .justify_center()
            .child("Cancel");

        div()
            .id("root")
            .on_action(cx.listener(|this, _: &ToggleReadOnly, _, cx| {
                this.read_only.checked = !this.read_only.checked;
                cx.notify();
            }))
            .on_action(cx.listener(|_, _: &FocusNext, window, _| window.focus_next()))
            .on_action(cx.listener(|_, _: &FocusPrev, window, _| window.focus_prev()))
            .size_full()
            .flex()
            .flex_col()
            .bg(rgb(FRAME))
            .font_family(".SystemUIFont")
            .text_size(px(13.))
            .text_color(rgb(TEXT))
            // The title bar: the frame colour, with room for macOS's traffic lights.
            .child(
                div()
                    .h(px(40.))
                    .pl(px(80.))
                    .flex()
                    .items_center()
                    .child(SharedString::from("TableGrip — GPUI spike")),
            )
            .child(
                div()
                    .flex_1()
                    .m(px(8.))
                    .mt_0()
                    .rounded(px(10.))
                    .bg(rgb(ISLAND))
                    .p(px(16.))
                    .flex()
                    .flex_col()
                    .gap(px(12.))
                    .child(div().flex().gap(px(12.)).child(button).child(outlined))
                    .child(checkbox)
                    .child(format!(
                        "Saved {} times · ⌘K / Ctrl+K toggles read-only · Tab moves focus",
                        self.saves
                    )),
            )
    }
}
