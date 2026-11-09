//! Input-method composition, modelled on the calls an OS input method makes (macOS `NSTextInputClient`, which GPUI's
//! `InputHandler` mirrors): set marked text, replace it, commit it, or cancel it.
//!
//! A Vietnamese Telex or VNI input method marks the word being typed (`tiê`, then `tiế`, ...) and commits it on space.
//! Others rewrite the end of the word in place without marking it; both end in the same text and the same undo step.

use std::ops::Range;

use crate::editor::{Editor, Selection};
use crate::history::{Edit, EditKind};
use crate::segment;

/// A composition in progress: what it replaced, so cancelling can restore it and committing can record one undo step.
#[derive(Clone, Debug)]
pub(crate) struct Composition {
    start: usize,
    /// The end of the composed text so far.
    end: usize,
    removed: String,
    before: Selection,
}

impl Editor {
    /// The marked (composing) text's range, if an input method is composing.
    pub fn marked_range(&self) -> Option<Range<usize>> {
        self.marked.clone()
    }

    pub fn is_composing(&self) -> bool {
        self.composition.is_some()
    }

    /// The text in `range`, or None when it's out of bounds or not on character boundaries.
    pub fn text_for_range(&self, range: Range<usize>) -> Option<&str> {
        self.text.get(range)
    }

    /// Sets the marked text, replacing `range`, or else the current marked text, or else the selection. `selected` is
    /// where the caret (or selection) goes within the new text; the end when None. Empty text ends the composition.
    pub fn replace_and_mark_text_in_range(
        &mut self,
        range: Option<Range<usize>>,
        text: &str,
        selected: Option<Range<usize>>,
    ) -> bool {
        if !self.editable() {
            return false;
        }
        let range = self.clamp(
            range
                .or_else(|| self.marked.clone())
                .unwrap_or(self.selection.range()),
        );
        match &mut self.composition {
            None => {
                self.composition = Some(Composition {
                    start: range.start,
                    end: range.end,
                    removed: self.text[range.clone()].to_owned(),
                    before: self.selection,
                });
            }
            // A replacement reaching past the composed text takes original text with it, which cancelling restores.
            Some(c) => {
                if range.start < c.start {
                    c.removed.insert_str(0, &self.text[range.start..c.start]);
                    c.start = range.start;
                }
                if range.end > c.end {
                    c.removed.push_str(&self.text[c.end..range.end]);
                    c.end = range.end;
                }
            }
        }
        self.text.replace_range(range.clone(), text);
        let end = range.start + text.len();
        if let Some(c) = &mut self.composition {
            c.end = c.end + text.len() - range.len();
        }
        if text.is_empty() {
            self.marked = None;
            self.selection = Selection::caret(range.start);
            self.finish_composition();
            return true;
        }
        self.marked = Some(range.start..end);
        self.selection = match selected {
            Some(s) => {
                let at = |i: usize| segment::floor_boundary(text, i.min(text.len())) + range.start;
                Selection {
                    anchor: at(s.start),
                    head: at(s.end),
                }
            }
            None => Selection::caret(end),
        };
        true
    }

    /// Inserts committed text, replacing `range`, or else the marked text, or else the selection; ends any
    /// composition. An input method that rewrites the word just typed (no marking) passes the range it rewrites.
    pub fn replace_text_in_range(&mut self, range: Option<Range<usize>>, text: &str) -> bool {
        if !self.editable() {
            return false;
        }
        if self.composition.is_some() {
            let range = self.clamp(
                range
                    .or_else(|| self.marked.clone())
                    .unwrap_or(self.selection.range()),
            );
            self.replace_and_mark_text_in_range(Some(range.clone()), text, None);
            self.marked = None;
            self.selection = Selection::caret(range.start + text.len());
            self.finish_composition();
            return true;
        }
        match range {
            None => self.insert_typed(text),
            Some(range) => {
                let range = self.clamp(range);
                self.replace(range, text, EditKind::Typing);
            }
        }
        true
    }

    /// Commits the marked text as it is. Moving the caret, clicking elsewhere and most keys do this first.
    pub fn unmark_text(&mut self) {
        if self.composition.is_some() {
            self.marked = None;
            self.finish_composition();
        }
    }

    /// Drops the marked text and restores what the composition replaced, with the selection from before it.
    pub fn cancel_composition(&mut self) {
        let Some(c) = self.composition.take() else {
            return;
        };
        self.marked = None;
        self.text.replace_range(c.start..c.end, &c.removed);
        self.selection = c.before;
    }

    /// Records the finished composition as one undo step: typing, or a replacement when it replaced a selection.
    fn finish_composition(&mut self) {
        let Some(c) = self.composition.take() else {
            return;
        };
        let inserted = self.text[c.start..c.end].to_owned();
        if inserted.is_empty() && c.removed.is_empty() {
            return;
        }
        let kind = if c.removed.is_empty() {
            EditKind::Typing
        } else {
            EditKind::Other
        };
        self.history.record(Edit {
            start: c.start,
            removed: c.removed,
            inserted,
            before: c.before,
            after: self.selection,
            kind,
        });
    }

    /// A range snapped onto character boundaries within the text.
    fn clamp(&self, range: Range<usize>) -> Range<usize> {
        let floor = |mut i: usize| {
            i = i.min(self.text.len());
            while !self.text.is_char_boundary(i) {
                i -= 1;
            }
            i
        };
        let (start, end) = (floor(range.start), floor(range.end));
        start.min(end)..end.max(start)
    }

    /// `pos` (a byte offset) in UTF-16 code units, as macOS and Windows input methods count.
    pub fn offset_to_utf16(&self, pos: usize) -> usize {
        self.text[..pos.min(self.text.len())].encode_utf16().count()
    }

    /// The byte offset of `units` UTF-16 code units in, rounded down to a character boundary.
    pub fn offset_from_utf16(&self, units: usize) -> usize {
        let mut counted = 0;
        for (i, c) in self.text.char_indices() {
            if counted + c.len_utf16() > units {
                return i;
            }
            counted += c.len_utf16();
        }
        self.text.len()
    }

    pub fn range_to_utf16(&self, range: Range<usize>) -> Range<usize> {
        self.offset_to_utf16(range.start)..self.offset_to_utf16(range.end)
    }

    pub fn range_from_utf16(&self, range: Range<usize>) -> Range<usize> {
        self.offset_from_utf16(range.start)..self.offset_from_utf16(range.end)
    }
}
