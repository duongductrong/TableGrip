//! TextField and TextArea (`TextField(state)`, `TextArea(state)` in the Kotlin app) on GPUI, over `tg_text::Editor`.
//!
//! [`TextInput`] is a view: create it with [`TextInput::field`] or [`TextInput::area`] and size it from outside (a
//! field is 28dp tall and as wide as its parent; an area fills its parent). It draws the fill, the 1dp border, the
//! focus ring or error outline (2dp, reaching 1dp outside the bounds), the text or placeholder, the selection, the
//! input method's marked text underlined, and a caret that blinks while focused. A field scrolls sideways to keep the
//! caret in view, an area wraps and scrolls down.
//!
//! Keys go to the editor first; the ones it doesn't use (`KeyResult::Bubble`) propagate to the parent, which hangs
//! submit, dismiss and list navigation on them. Typed text and the input method's marked text arrive through GPUI's
//! input handler ([`gpui::EntityInputHandler`]), so a Vietnamese Telex or VNI input method composes in place. Up and
//! Down in an area move by pixels in GPUI's text layout, keeping the caret's x across rows.

mod element;
mod layout;

use std::ops::Range;
use std::time::Duration;

use gpui::{
    App, Bounds, ClipboardItem, Context, CursorStyle, EntityInputHandler, FocusHandle, Focusable,
    Hsla, KeyDownEvent, MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent, Pixels, Point,
    Render, Task, UTF16Selection, Window, div, point, prelude::*, px,
};
use tg_text::{Clipboard, Editor, KeyResult, Options};
use tg_ui_logic::{Key, Modifiers};
use unicode_segmentation::UnicodeSegmentation;

use crate::theme::{ActiveTheme, ThemeExt, hsla};
use element::TextElement;
use layout::TextLayout;

/// Field metrics in dp, as measured from the Kotlin app's render.
const FIELD_HEIGHT: f32 = 28.0;
const FIELD_PADDING_X: f32 = 8.0;
/// From inside the border to the text's (trimmed) line box: 6.5dp from the field's top.
const FIELD_PADDING_TOP: f32 = 5.5;
const AREA_PADDING_X: f32 = 5.0;
const AREA_PADDING_Y: f32 = 4.0;
const RADIUS: f32 = 4.0;
const BORDER: f32 = 1.0;
/// The focus ring and error outline: 2dp, its outer edge 1dp outside the bounds.
const RING: f32 = 2.0;
const RING_OUTSIDE: f32 = 1.0;
const CARET_WIDTH: f32 = 2.0;
/// Compose's cursor blinks 500ms on, 500ms off.
const BLINK: Duration = Duration::from_millis(500);

/// A look to draw regardless of focus, for the gallery's static specimens.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextInputLook {
    /// Focus ring, focused border and selection drawn; no caret (it blinks).
    Focused,
}

/// A TextField or TextArea.
pub struct TextInput {
    editor: Editor,
    focus: FocusHandle,
    error: bool,
    look: Option<TextInputLook>,
    /// How far the text is scrolled: sideways in a field, down in an area.
    scroll: Point<Pixels>,
    /// The x Up and Down aim for in an area, kept across rows of different lengths.
    goal_x: Option<Pixels>,
    caret_on: bool,
    blink: Option<Task<()>>,
    selecting: bool,
    /// The last frame's layout and where its first row was drawn, for hit testing and the input method.
    layout: Option<TextLayout>,
    origin: Point<Pixels>,
}

impl TextInput {
    /// A single-line TextField holding `text`.
    pub fn field(text: &str, cx: &mut Context<Self>) -> Self {
        Self::new(text, Options::default(), cx)
    }

    /// A multi-line TextArea holding `text`.
    pub fn area(text: &str, cx: &mut Context<Self>) -> Self {
        Self::new(
            text,
            Options {
                multi_line: true,
                ..Options::default()
            },
            cx,
        )
    }

    pub fn new(text: &str, options: Options, cx: &mut Context<Self>) -> Self {
        let enabled = options.enabled;
        TextInput {
            editor: Editor::new(text, options),
            focus: cx.focus_handle().tab_stop(enabled),
            error: false,
            look: None,
            scroll: Point::default(),
            goal_x: None,
            caret_on: true,
            blink: None,
            selecting: false,
            layout: None,
            origin: Point::default(),
        }
    }

    pub fn placeholder(mut self, text: impl Into<String>) -> Self {
        self.editor = self.rebuilt(|o| o.placeholder = text.into());
        self
    }

    pub fn password(mut self) -> Self {
        self.editor = self.rebuilt(|o| o.password = true);
        self
    }

    pub fn read_only(mut self) -> Self {
        self.editor = self.rebuilt(|o| o.read_only = true);
        self
    }

    pub fn disabled(mut self) -> Self {
        self.editor = self.rebuilt(|o| o.enabled = false);
        self.focus = self.focus.tab_stop(false);
        self
    }

    /// The error outline (`Outline.Error`).
    pub fn error(mut self, error: bool) -> Self {
        self.error = error;
        self
    }

    pub fn look(mut self, look: TextInputLook) -> Self {
        self.look = Some(look);
        self
    }

    fn rebuilt(&self, change: impl FnOnce(&mut Options)) -> Editor {
        let mut options = self.editor.options().clone();
        change(&mut options);
        let mut editor = Editor::new(self.editor.text(), options);
        editor.place_caret(self.editor.selection().head, false);
        editor
    }

    pub fn set_error(&mut self, error: bool, cx: &mut Context<Self>) {
        self.error = error;
        cx.notify();
    }

    pub fn editor(&self) -> &Editor {
        &self.editor
    }

    /// The editor, to set it up before the field is shown; [`update_editor`](Self::update_editor) once it is.
    pub fn editor_mut(&mut self) -> &mut Editor {
        &mut self.editor
    }

    /// Changes the editor (sets text, selects, ...) and redraws.
    pub fn update_editor<R>(
        &mut self,
        cx: &mut Context<Self>,
        f: impl FnOnce(&mut Editor) -> R,
    ) -> R {
        let r = f(&mut self.editor);
        self.edited(cx);
        r
    }

    pub fn text(&self) -> &str {
        self.editor.text()
    }

    /// How far the text is scrolled: sideways in a field, down in an area.
    pub fn scroll(&self) -> Point<Pixels> {
        self.scroll
    }

    /// Whether the caret is in the "on" half of its blink (it's drawn only while focused with nothing selected).
    pub fn caret_visible(&self) -> bool {
        self.caret_on
    }

    fn multi_line(&self) -> bool {
        self.editor.options().multi_line
    }

    fn enabled(&self) -> bool {
        self.editor.options().enabled
    }

    fn focused(&self, window: &Window) -> bool {
        self.look == Some(TextInputLook::Focused)
            || (self.enabled() && self.focus.is_focused(window))
    }

    /// After anything that moves the caret or changes the text: the caret shows at once and blinks from there.
    fn edited(&mut self, cx: &mut Context<Self>) {
        self.caret_on = true;
        if self.blink.is_some() {
            self.start_blink(cx);
        }
        cx.notify();
    }

    fn start_blink(&mut self, cx: &mut Context<Self>) {
        self.caret_on = true;
        self.blink = Some(cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(BLINK).await;
                let alive = this.update(cx, |this, cx| {
                    this.caret_on = !this.caret_on;
                    cx.notify();
                });
                if alive.is_err() {
                    break;
                }
            }
        }));
    }

    /// The text as drawn: bullets in a password field, line breaks as spaces in a single-line one (one byte each, so
    /// offsets carry over).
    fn display_text(&self) -> String {
        let text = self.editor.display_text();
        if self.multi_line() {
            text.into_owned()
        } else {
            text.replace(['\n', '\r'], " ")
        }
    }

    /// Where text offset `offset` falls in the display text.
    fn display_offset(&self, offset: usize) -> usize {
        self.editor.display_offset(offset)
    }

    /// The text offset of display offset `offset` (a bullet's start in a password field).
    fn text_offset(&self, offset: usize) -> usize {
        if !self.editor.options().password {
            return offset;
        }
        let bullets = offset / '•'.len_utf8();
        self.editor
            .text()
            .grapheme_indices(true)
            .nth(bullets)
            .map_or(self.editor.text().len(), |(i, _)| i)
    }

    /// The text offset under `position` (window coordinates).
    fn offset_at(&self, position: Point<Pixels>) -> usize {
        match &self.layout {
            Some(layout) => {
                let local = position - self.origin;
                self.text_offset(layout.offset_at(local))
            }
            None => self.editor.text().len(),
        }
    }

    fn on_key_down(&mut self, event: &KeyDownEvent, _: &mut Window, cx: &mut Context<Self>) {
        let Some((key, mods)) = key_of(event) else {
            return;
        };
        let vertical = matches!(key, Key::Up | Key::Down);
        let handled =
            if vertical && self.multi_line() && !mods.primary && !mods.alt && self.enabled() {
                self.move_vertically(key == Key::Up, mods)
            } else {
                let mut clipboard = SystemClipboard(cx);
                self.editor.handle_key(key, mods, &mut clipboard) == KeyResult::Handled
            };
        if !vertical {
            self.goal_x = None;
        }
        if handled {
            cx.stop_propagation();
            self.edited(cx);
        }
    }

    /// Up or Down in an area: to the offset one row up or down at the same x in the laid-out text; the first or
    /// last row goes to the text's start or end. False (the key bubbles) when a plain arrow can't move.
    fn move_vertically(&mut self, up: bool, mods: Modifiers) -> bool {
        let Some(layout) = &self.layout else {
            return false;
        };
        let selection = self.editor.selection();
        let head = self.display_offset(selection.head);
        let at = layout.position(head);
        let x = self.goal_x.unwrap_or(at.x);
        let row = layout.row(head);
        let target = if up && row == 0 {
            0
        } else if !up && row + 1 >= layout.rows() {
            self.editor.text().len()
        } else {
            let dy = if up {
                -layout.line_height
            } else {
                layout.line_height
            };
            let y = at.y + dy + layout.line_height / 2.0;
            self.text_offset(layout.offset_at(point(x, y)))
        };
        let plain = !mods.shift;
        if plain && selection.is_empty() && target == selection.head {
            return false;
        }
        self.editor.place_caret(target, mods.shift);
        self.goal_x = Some(x);
        true
    }

    fn on_mouse_down(
        &mut self,
        event: &MouseDownEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.enabled() {
            return;
        }
        window.focus(&self.focus);
        let pos = self.offset_at(event.position);
        match event.click_count {
            2 => self.editor.select_word_at(pos),
            n if n >= 3 => self.editor.select_all(),
            _ => {
                self.selecting = true;
                self.editor.place_caret(pos, event.modifiers.shift);
            }
        }
        self.goal_x = None;
        self.edited(cx);
    }

    fn on_mouse_move(&mut self, event: &MouseMoveEvent, _: &mut Window, cx: &mut Context<Self>) {
        if self.selecting && event.pressed_button == Some(MouseButton::Left) {
            let pos = self.offset_at(event.position);
            self.editor.place_caret(pos, true);
            self.edited(cx);
        }
    }

    fn on_mouse_up(&mut self, _: &MouseUpEvent, _: &mut Window, _: &mut Context<Self>) {
        self.selecting = false;
    }
}

/// The key and modifiers of a key press, as `tg-text` names them; None for keys a field never handles.
fn key_of(event: &KeyDownEvent) -> Option<(Key, Modifiers)> {
    let k = &event.keystroke;
    let key = match k.key.as_str() {
        "space" => Key::Space,
        "enter" => Key::Enter,
        "tab" => Key::Tab,
        "escape" => Key::Escape,
        "backspace" => Key::Backspace,
        "delete" => Key::Delete,
        "home" => Key::Home,
        "end" => Key::End,
        "left" => Key::Left,
        "right" => Key::Right,
        "up" => Key::Up,
        "down" => Key::Down,
        "pageup" => Key::PageUp,
        "pagedown" => Key::PageDown,
        "f2" => Key::F2,
        s if s.len() == 1 && s.as_bytes()[0].is_ascii_alphabetic() => {
            Key::Letter(s.as_bytes()[0].to_ascii_uppercase() as char)
        }
        _ => return None,
    };
    let m = &k.modifiers;
    let primary = if cfg!(target_os = "macos") {
        m.platform
    } else {
        m.control
    };
    Some((
        key,
        Modifiers {
            primary,
            shift: m.shift,
            alt: m.alt,
        },
    ))
}

/// The system clipboard as `tg-text` reads and writes it.
struct SystemClipboard<'a>(&'a mut App);

impl Clipboard for SystemClipboard<'_> {
    fn read_text(&mut self) -> Option<String> {
        self.0.read_from_clipboard().and_then(|item| item.text())
    }

    fn write_text(&mut self, text: String) {
        self.0.write_to_clipboard(ClipboardItem::new_string(text));
    }
}

/// The colours a field draws with: the theme's restyled controls, or Int UI's (Graphite).
#[derive(Clone, Copy)]
pub(crate) struct FieldColors {
    pub fill: Hsla,
    pub border: Hsla,
    pub border_disabled: Hsla,
    pub border_focused: Hsla,
    pub ring_focused: Hsla,
    pub ring_error: Hsla,
    pub text: Hsla,
    pub text_disabled: Hsla,
    pub placeholder: Hsla,
    pub selection: Hsla,
}

impl FieldColors {
    pub fn of(t: &ActiveTheme) -> FieldColors {
        let theme = &t.theme;
        let ui = &theme.ui;
        let rgb = |v: u32| hsla(tg_theme::Rgba::rgb(v));
        // Int UI's own field colours, where a theme doesn't restyle controls.
        let (fill, border, border_disabled, muted) = if theme.colors.is_dark {
            (rgb(0x2B2D30), rgb(0x4E5157), rgb(0x4E5157), rgb(0x6F737A))
        } else {
            (rgb(0xFFFFFF), rgb(0xC9CCD6), rgb(0xDFE1E5), rgb(0xA8ADBD))
        };
        // Compose's text selection: its handle colour at 40%.
        let mut selection = rgb(0x4286F4);
        selection.a = 0.4;
        match &theme.colors.controls {
            Some(c) => FieldColors {
                fill: hsla(c.input_background),
                border: hsla(c.input_border),
                border_disabled: hsla(c.input_border),
                border_focused: hsla(c.focus),
                ring_focused: hsla(ui.outline_focused),
                ring_error: hsla(ui.outline_error),
                text: hsla(c.text),
                text_disabled: hsla(c.text_disabled),
                placeholder: hsla(c.text_muted),
                selection,
            },
            None => FieldColors {
                fill,
                border,
                border_disabled,
                border_focused: hsla(ui.outline_focused),
                ring_focused: hsla(ui.outline_focused),
                ring_error: hsla(ui.outline_error),
                text: hsla(ui.text),
                text_disabled: muted,
                placeholder: muted,
                selection,
            },
        }
    }
}

impl Focusable for TextInput {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Render for TextInput {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let focused = self.focused(window);
        // Blink only while really focused (not in a drawn look).
        let blinking = self.enabled() && self.focus.is_focused(window);
        match (blinking, self.blink.is_some()) {
            (true, false) => self.start_blink(cx),
            (false, true) => self.blink = None,
            _ => {}
        }

        let t = cx.theme().clone();
        let colors = FieldColors::of(&t);
        let enabled = self.enabled();
        let multi = self.multi_line();
        let border = if !enabled {
            colors.border_disabled
        } else if self.error {
            colors.ring_error
        } else if focused {
            colors.border_focused
        } else {
            colors.border
        };
        let ring = if self.error && enabled {
            Some(colors.ring_error)
        } else if focused {
            Some(colors.ring_focused)
        } else {
            None
        };
        let (pad_x, pad_top, pad_bottom) = if multi {
            (AREA_PADDING_X, AREA_PADDING_Y, AREA_PADDING_Y)
        } else {
            (FIELD_PADDING_X, FIELD_PADDING_TOP, 0.0)
        };

        div()
            .id("text-input")
            .key_context("TextInput")
            // GPUI focuses a tracked element on mouse down; a disabled field takes no focus.
            .when(enabled, |d| d.track_focus(&self.focus))
            .relative()
            .flex()
            .w_full()
            .items_start()
            .map(|d| {
                if multi {
                    d.h_full()
                } else {
                    d.h(t.dp(FIELD_HEIGHT))
                }
            })
            .px(t.dp(pad_x))
            .pt(t.dp(pad_top))
            .pb(t.dp(pad_bottom))
            .when(enabled, |d| d.bg(colors.fill).cursor(CursorStyle::IBeam))
            .border(t.dp(BORDER))
            .border_color(border)
            .rounded(t.dp(RADIUS))
            .font_family(t.ui_font.clone())
            .text_size(t.dp(t.theme.fonts.ui_size))
            .on_key_down(cx.listener(Self::on_key_down))
            .on_mouse_down(MouseButton::Left, cx.listener(Self::on_mouse_down))
            .on_mouse_move(cx.listener(Self::on_mouse_move))
            .on_mouse_up(MouseButton::Left, cx.listener(Self::on_mouse_up))
            .on_mouse_up_out(MouseButton::Left, cx.listener(Self::on_mouse_up))
            .child(TextElement {
                input: cx.entity(),
                colors,
                focused,
            })
            .when_some(ring, |d, color| {
                let out = t.dp(RING_OUTSIDE + BORDER);
                d.child(
                    div()
                        .absolute()
                        .top(-out)
                        .left(-out)
                        .right(-out)
                        .bottom(-out)
                        .border(t.dp(RING))
                        .border_color(color)
                        .rounded(t.dp(RADIUS + RING_OUTSIDE)),
                )
            })
    }
}

impl EntityInputHandler for TextInput {
    fn text_for_range(
        &mut self,
        range: Range<usize>,
        adjusted: &mut Option<Range<usize>>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<String> {
        let range = self.editor.range_from_utf16(range);
        adjusted.replace(self.editor.range_to_utf16(range.clone()));
        self.editor.text_for_range(range).map(str::to_owned)
    }

    fn selected_text_range(
        &mut self,
        _ignore_disabled_input: bool,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<UTF16Selection> {
        if !self.enabled() {
            return None;
        }
        let s = self.editor.selection();
        Some(UTF16Selection {
            range: self.editor.range_to_utf16(s.range()),
            reversed: s.reversed(),
        })
    }

    fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
        self.editor
            .marked_range()
            .map(|r| self.editor.range_to_utf16(r))
    }

    fn unmark_text(&mut self, _: &mut Window, cx: &mut Context<Self>) {
        self.editor.unmark_text();
        self.edited(cx);
    }

    fn replace_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let range = range.map(|r| self.editor.range_from_utf16(r));
        if self.editor.replace_text_in_range(range, text) {
            self.goal_x = None;
            self.edited(cx);
        }
    }

    fn replace_and_mark_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        selected: Option<Range<usize>>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let range = range.map(|r| self.editor.range_from_utf16(r));
        // The selection inside the new text comes in UTF-16 units of that text; the editor takes bytes.
        let selected = selected.map(|r| utf16_to_byte(text, r.start)..utf16_to_byte(text, r.end));
        if self
            .editor
            .replace_and_mark_text_in_range(range, text, selected)
        {
            self.goal_x = None;
            self.edited(cx);
        }
    }

    fn bounds_for_range(
        &mut self,
        range: Range<usize>,
        _element_bounds: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        let layout = self.layout.as_ref()?;
        let range = self.editor.range_from_utf16(range);
        let (a, b) = (
            layout.position(self.display_offset(range.start)),
            layout.position(self.display_offset(range.end)),
        );
        let bottom = b.y.max(a.y) + layout.line_height;
        Some(Bounds::from_corners(
            self.origin + a,
            self.origin + point(b.x.max(a.x + px(1.0)), bottom),
        ))
    }

    fn character_index_for_point(
        &mut self,
        position: Point<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<usize> {
        self.layout.as_ref()?;
        Some(self.editor.offset_to_utf16(self.offset_at(position)))
    }
}

/// The byte offset of `units` UTF-16 code units into `text`.
fn utf16_to_byte(text: &str, units: usize) -> usize {
    let mut counted = 0;
    for (i, c) in text.char_indices() {
        if counted >= units {
            return i;
        }
        counted += c.len_utf16();
    }
    text.len()
}
