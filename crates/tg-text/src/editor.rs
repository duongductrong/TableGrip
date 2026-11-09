use std::borrow::Cow;
use std::ops::Range;

use tg_ui_logic::{Key, Modifiers};

use crate::clipboard::Clipboard;
use crate::history::{Edit, EditKind, History};
use crate::ime::Composition;
use crate::segment;

/// The caret and selection: `head` is where the caret is, `anchor` where the selection started. Equal when nothing is
/// selected. Both are byte offsets on grapheme boundaries.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Selection {
    pub anchor: usize,
    pub head: usize,
}

impl Selection {
    pub fn caret(pos: usize) -> Self {
        Selection {
            anchor: pos,
            head: pos,
        }
    }

    pub fn range(&self) -> Range<usize> {
        self.anchor.min(self.head)..self.anchor.max(self.head)
    }

    pub fn is_empty(&self) -> bool {
        self.anchor == self.head
    }

    /// Whether the caret is at the selection's start (the selection was made leftwards).
    pub fn reversed(&self) -> bool {
        self.head < self.anchor
    }
}

/// Which platform's editing keys apply. Word movement is Option+arrow on macOS and Ctrl+arrow elsewhere (`primary`
/// in [`Modifiers`]); on macOS Cmd+arrow goes to the line's or the text's ends.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Platform {
    Mac,
    Other,
}

impl Platform {
    pub fn current() -> Self {
        if cfg!(target_os = "macos") {
            Platform::Mac
        } else {
            Platform::Other
        }
    }
}

/// How a field behaves. The default is an enabled, editable single-line field for this platform.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Options {
    /// A TextArea: Enter and Tab insert, Up and Down move between lines.
    pub multi_line: bool,
    /// Shows a bullet per character, copies bullets, and moves by the whole text instead of by words.
    pub password: bool,
    /// Selection and copy work; edits, cut, paste and undo don't (their keys go to the parent).
    pub read_only: bool,
    /// A disabled field takes no input at all.
    pub enabled: bool,
    /// Shown while the text is empty.
    pub placeholder: String,
    pub platform: Platform,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            multi_line: false,
            password: false,
            read_only: false,
            enabled: true,
            placeholder: String::new(),
            platform: Platform::current(),
        }
    }
}

/// What a key press did. `Bubble` keys belong to the field's parent: the app hangs list navigation, submit and dismiss
/// on them (Enter, Escape, Up/Down at the ends, and so on).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyResult {
    Handled,
    Bubble,
}

/// The editing model of a TextField or TextArea.
#[derive(Clone, Debug)]
pub struct Editor {
    pub(crate) text: String,
    pub(crate) selection: Selection,
    pub(crate) options: Options,
    pub(crate) history: History,
    /// The text an input method is composing, shown marked (underlined) and not yet part of undo history.
    pub(crate) marked: Option<Range<usize>>,
    pub(crate) composition: Option<Composition>,
    /// The column Up and Down keep aiming for across lines of different lengths.
    column: Option<usize>,
}

const BULLET: char = '•';

impl Editor {
    /// A field holding `text` with the caret at its end, as a field starts out.
    pub fn new(text: impl Into<String>, options: Options) -> Self {
        let text = text.into();
        Editor {
            selection: Selection::caret(text.len()),
            text,
            options,
            history: History::default(),
            marked: None,
            composition: None,
            column: None,
        }
    }

    pub fn text(&self) -> &str {
        &self.text
    }

    pub fn options(&self) -> &Options {
        &self.options
    }

    pub fn selection(&self) -> Selection {
        self.selection
    }

    pub fn selected_text(&self) -> &str {
        &self.text[self.selection.range()]
    }

    /// Whether edits are allowed (enabled and not read-only).
    pub fn editable(&self) -> bool {
        self.options.enabled && !self.options.read_only
    }

    pub fn placeholder_visible(&self) -> bool {
        self.text.is_empty() && !self.options.placeholder.is_empty()
    }

    /// The text as drawn: bullets in a password field.
    pub fn display_text(&self) -> Cow<'_, str> {
        if self.options.password {
            Cow::Owned(mask(&self.text))
        } else {
            Cow::Borrowed(&self.text)
        }
    }

    /// Where `pos` in the text falls in [`display_text`](Self::display_text).
    pub fn display_offset(&self, pos: usize) -> usize {
        if self.options.password {
            segment::grapheme_count(&self.text[..pos]) * BULLET.len_utf8()
        } else {
            pos
        }
    }

    /// Replaces the whole text as a program would (loading a value): one undoable step, caret at the end.
    pub fn set_text(&mut self, text: &str) {
        self.unmark_text();
        self.replace(0..self.text.len(), text, EditKind::Other);
    }

    /// Moves the caret to `pos` (snapped to a grapheme boundary), extending the selection when `extend` is set: a
    /// click, or a Shift+click.
    pub fn place_caret(&mut self, pos: usize, extend: bool) {
        self.unmark_text();
        let pos = segment::floor_boundary(&self.text, pos);
        self.move_to(pos, extend);
    }

    /// Selects the word at `pos`, as a double-click does. A password field selects everything.
    pub fn select_word_at(&mut self, pos: usize) {
        self.unmark_text();
        if self.options.password {
            return self.select_all();
        }
        let (start, end) = segment::word_at(&self.text, segment::floor_boundary(&self.text, pos));
        self.selection = Selection {
            anchor: start,
            head: end,
        };
        self.column = None;
    }

    pub fn select_all(&mut self) {
        self.unmark_text();
        self.selection = Selection {
            anchor: 0,
            head: self.text.len(),
        };
        self.column = None;
    }

    /// Text typed on the keyboard (or committed by an input method in one go). Replaces the selection.
    pub fn insert_text(&mut self, text: &str) -> bool {
        if !self.editable() {
            return false;
        }
        self.unmark_text();
        self.insert_typed(text);
        true
    }

    pub fn copy(&mut self, clipboard: &mut dyn Clipboard) {
        if self.selection.is_empty() {
            return;
        }
        let selected = self.selected_text();
        // The app's password field copies what it shows: bullets.
        let copied = if self.options.password {
            mask(selected)
        } else {
            selected.to_owned()
        };
        clipboard.write_text(copied);
    }

    pub fn cut(&mut self, clipboard: &mut dyn Clipboard) -> bool {
        if !self.editable() || self.selection.is_empty() {
            return false;
        }
        self.unmark_text();
        self.copy(clipboard);
        self.replace(self.selection.range(), "", EditKind::Other);
        true
    }

    /// Pastes the clipboard's text as it is: a single-line field keeps line breaks, as the app's does.
    pub fn paste(&mut self, clipboard: &mut dyn Clipboard) -> bool {
        if !self.editable() {
            return false;
        }
        self.unmark_text();
        match clipboard.read_text() {
            Some(text) if !text.is_empty() => {
                self.replace(self.selection.range(), &text, EditKind::Other);
                true
            }
            _ => false,
        }
    }

    pub fn can_undo(&self) -> bool {
        self.history.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.history.can_redo()
    }

    /// Undoes the last step and restores the selection it started from.
    pub fn undo(&mut self) -> bool {
        self.unmark_text();
        let Some(edit) = self.history.undo() else {
            return false;
        };
        self.text
            .replace_range(edit.start..edit.start + edit.inserted.len(), &edit.removed);
        self.selection = edit.before;
        self.column = None;
        true
    }

    pub fn redo(&mut self) -> bool {
        self.unmark_text();
        let Some(edit) = self.history.redo() else {
            return false;
        };
        self.text
            .replace_range(edit.start..edit.start + edit.removed.len(), &edit.inserted);
        self.selection = edit.after;
        self.column = None;
        true
    }

    /// Ends the current undo group, so the next edit is a step of its own (say after a pause in typing).
    pub fn break_undo_group(&mut self) {
        self.history.seal();
    }

    /// A key press while the field has focus. Typed characters arrive through [`insert_text`](Self::insert_text) or
    /// the input-method calls, not here: a letter key without a shortcut modifier bubbles.
    pub fn handle_key(
        &mut self,
        key: Key,
        mods: Modifiers,
        clipboard: &mut dyn Clipboard,
    ) -> KeyResult {
        if !self.options.enabled {
            return KeyResult::Bubble;
        }
        let handled = match key {
            Key::Left | Key::Right => self.horizontal(key == Key::Right, mods),
            Key::Up | Key::Down => self.vertical(key == Key::Up, mods),
            Key::Home | Key::End => {
                let end = key == Key::End;
                let pos = self.selection.head;
                let target = match (mods.primary, end) {
                    (true, false) => 0,
                    (true, true) => self.text.len(),
                    (false, false) => segment::line_start(&self.text, pos),
                    (false, true) => segment::line_end(&self.text, pos),
                };
                self.unmark_text();
                self.move_to(target, mods.shift);
                true
            }
            Key::Backspace | Key::Delete if self.editable() => {
                self.unmark_text();
                self.delete(key == Key::Delete, mods);
                true
            }
            Key::Enter | Key::Tab => {
                let insert = self.options.multi_line
                    && self.editable()
                    && !mods.primary
                    && !mods.alt
                    && (key == Key::Enter || !mods.shift);
                if insert {
                    self.unmark_text();
                    self.insert_typed(if key == Key::Enter { "\n" } else { "\t" });
                }
                insert
            }
            Key::Letter(letter) if mods.primary && !mods.alt => {
                self.shortcut(letter, mods.shift, clipboard)
            }
            _ => false,
        };
        if handled {
            KeyResult::Handled
        } else {
            KeyResult::Bubble
        }
    }

    fn shortcut(&mut self, letter: char, shift: bool, clipboard: &mut dyn Clipboard) -> bool {
        let editable = self.editable();
        match (letter, shift) {
            ('A', false) => self.select_all(),
            ('C', false) => self.copy(clipboard),
            ('X', false) if editable => {
                self.cut(clipboard);
            }
            ('V', false) if editable => {
                self.paste(clipboard);
            }
            ('Z', false) if editable => {
                self.undo();
            }
            ('Z', true) | ('Y', false) if editable => {
                self.redo();
            }
            _ => return false,
        }
        true
    }

    /// The word-movement modifier: Option on macOS, Ctrl (`primary`) elsewhere.
    fn word_modifier(&self, mods: Modifiers) -> bool {
        match self.options.platform {
            Platform::Mac => mods.alt && !mods.primary,
            Platform::Other => mods.primary && !mods.alt,
        }
    }

    /// Cmd on macOS: arrows go to the ends of the line (Left/Right) or the text (Up/Down).
    fn line_modifier(&self, mods: Modifiers) -> bool {
        self.options.platform == Platform::Mac && mods.primary && !mods.alt
    }

    fn word_left(&self, pos: usize) -> usize {
        if self.options.password {
            0
        } else {
            segment::prev_word_start(&self.text, pos)
        }
    }

    fn word_right(&self, pos: usize) -> usize {
        if self.options.password {
            self.text.len()
        } else {
            segment::next_word_end(&self.text, pos)
        }
    }

    fn horizontal(&mut self, right: bool, mods: Modifiers) -> bool {
        let sel = self.selection;
        let pos = sel.head;
        let plain = !mods.shift && !mods.primary && !mods.alt;
        let target = if self.line_modifier(mods) {
            if right {
                segment::line_end(&self.text, pos)
            } else {
                segment::line_start(&self.text, pos)
            }
        } else if self.word_modifier(mods) {
            if right {
                self.word_right(pos)
            } else {
                self.word_left(pos)
            }
        } else if !mods.shift && !sel.is_empty() {
            // An arrow collapses a selection to the side it points to.
            if right {
                sel.range().end
            } else {
                sel.range().start
            }
        } else if right {
            segment::next_grapheme(&self.text, pos)
        } else {
            segment::prev_grapheme(&self.text, pos)
        };
        // A plain arrow that can't move the caret is the parent's: the app moves through a list with it.
        if plain && sel.is_empty() && target == pos {
            return false;
        }
        self.unmark_text();
        self.move_to(target, mods.shift);
        true
    }

    fn vertical(&mut self, up: bool, mods: Modifiers) -> bool {
        let sel = self.selection;
        let pos = sel.head;
        let text = &self.text;
        let plain = !mods.shift && !mods.primary && !mods.alt;
        let mac = self.options.platform == Platform::Mac;
        let mut column = None;
        let target = if self.line_modifier(mods) {
            if up { 0 } else { text.len() }
        } else if mac && mods.alt {
            // Option+Up/Down: to the start or end of the line, then of the one before or after.
            let (start, end) = (segment::line_start(text, pos), segment::line_end(text, pos));
            match up {
                true if pos == start && pos > 0 => segment::line_start(text, pos - 1),
                true => start,
                false if pos == end && pos < text.len() => segment::line_end(text, pos + 1),
                false => end,
            }
        } else if !self.options.multi_line {
            if up { 0 } else { text.len() }
        } else {
            let start = segment::line_start(text, pos);
            let col = self
                .column
                .unwrap_or_else(|| segment::grapheme_count(&text[start..pos]));
            column = Some(col);
            if up {
                if start == 0 {
                    0
                } else {
                    segment::at_column(text, segment::line_start(text, start - 1), col)
                }
            } else {
                let end = segment::line_end(text, pos);
                if end == text.len() {
                    end
                } else {
                    segment::at_column(text, end + 1, col)
                }
            }
        };
        if plain && sel.is_empty() && target == pos {
            return false;
        }
        self.unmark_text();
        self.move_to(target, mods.shift);
        self.column = column;
        true
    }

    fn delete(&mut self, forward: bool, mods: Modifiers) {
        let sel = self.selection;
        if !sel.is_empty() {
            return self.replace(sel.range(), "", EditKind::Other);
        }
        let pos = sel.head;
        let text = &self.text;
        if forward {
            let end = if self.word_modifier(mods) {
                self.word_right(pos)
            } else if self.line_modifier(mods) {
                segment::line_end(text, pos)
            } else {
                segment::next_grapheme(text, pos)
            };
            if end > pos {
                self.replace(pos..end, "", EditKind::DeleteForward);
            }
        } else {
            let start = if self.word_modifier(mods) {
                self.word_left(pos)
            } else if self.line_modifier(mods) {
                segment::line_start(text, pos)
            } else {
                let start = segment::prev_grapheme(text, pos);
                let grapheme = &text[start..pos];
                if segment::deletes_whole(grapheme) {
                    start
                } else {
                    pos - grapheme.chars().next_back().map_or(0, char::len_utf8)
                }
            };
            if start < pos {
                self.replace(start..pos, "", EditKind::DeleteBackward);
            }
        }
    }

    /// Typed text over the selection: typing merges in undo, typing over a selection doesn't.
    pub(crate) fn insert_typed(&mut self, text: &str) {
        let kind = if self.selection.is_empty() {
            EditKind::Typing
        } else {
            EditKind::Other
        };
        self.replace(self.selection.range(), text, kind);
    }

    fn move_to(&mut self, pos: usize, extend: bool) {
        self.selection = Selection {
            anchor: if extend { self.selection.anchor } else { pos },
            head: pos,
        };
        self.column = None;
    }

    /// Replaces `range` with `text`, leaves the caret after it and records the step.
    pub(crate) fn replace(&mut self, range: Range<usize>, text: &str, kind: EditKind) {
        let before = self.selection;
        let removed = self.text[range.clone()].to_owned();
        self.text.replace_range(range.clone(), text);
        self.selection = Selection::caret(range.start + text.len());
        self.column = None;
        self.history.record(Edit {
            start: range.start,
            removed,
            inserted: text.to_owned(),
            before,
            after: self.selection,
            kind,
        });
    }
}

/// A bullet per character as the user sees characters.
fn mask(text: &str) -> String {
    std::iter::repeat_n(BULLET, segment::grapheme_count(text)).collect()
}
