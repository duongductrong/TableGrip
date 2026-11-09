//! Undo and redo, grouped the way the Kotlin app groups them (observed through `text-field.json`):
//!
//! - typing merges with the typing just before it when it continues where that ended, so `ab`, ` `, `cd` undo at
//!   once, but typing elsewhere, after a line break, or a line break itself, starts a new step;
//! - Backspaces (by character or word) merge with each other, and forward Deletes with each other, while they stay
//!   contiguous; switching direction starts a new step;
//! - paste, cut, deleting a selection and typing over one are always a step of their own;
//! - moving the caret does not break a group by itself: only where the next edit lands matters.
//!
//! An input method's composition is recorded once, when it's committed, as typing; an input method that rewrites the
//! end of the word just typed (instead of marking it) merges into that typing, so a whole Vietnamese word undoes at once.

use crate::editor::Selection;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EditKind {
    Typing,
    DeleteBackward,
    DeleteForward,
    /// Never merges either way.
    Other,
}

/// `removed` was replaced by `inserted` at `start`.
#[derive(Clone, Debug)]
pub struct Edit {
    pub start: usize,
    pub removed: String,
    pub inserted: String,
    pub before: Selection,
    pub after: Selection,
    pub kind: EditKind,
}

impl Edit {
    fn merge(&mut self, next: &Edit) -> bool {
        match (self.kind, next.kind) {
            (EditKind::Typing, EditKind::Typing) => {
                let end = self.start + self.inserted.len();
                let continues = next.start >= self.start && next.start + next.removed.len() == end;
                if !continues || self.inserted.ends_with('\n') || next.inserted == "\n" {
                    return false;
                }
                self.inserted.truncate(next.start - self.start);
                self.inserted.push_str(&next.inserted);
            }
            (EditKind::DeleteBackward, EditKind::DeleteBackward) => {
                if next.start + next.removed.len() != self.start {
                    return false;
                }
                self.removed.insert_str(0, &next.removed);
                self.start = next.start;
            }
            (EditKind::DeleteForward, EditKind::DeleteForward) => {
                if next.start != self.start {
                    return false;
                }
                self.removed.push_str(&next.removed);
            }
            _ => return false,
        }
        self.after = next.after;
        true
    }
}

/// How many steps undo keeps.
const LIMIT: usize = 1000;

#[derive(Clone, Debug, Default)]
pub struct History {
    undo: Vec<Edit>,
    redo: Vec<Edit>,
    sealed: bool,
}

impl History {
    pub fn record(&mut self, edit: Edit) {
        self.redo.clear();
        let merged = !self.sealed && self.undo.last_mut().is_some_and(|last| last.merge(&edit));
        self.sealed = false;
        if !merged {
            if self.undo.len() == LIMIT {
                self.undo.remove(0);
            }
            self.undo.push(edit);
        }
    }

    /// The next edit starts a new step whatever it is.
    pub fn seal(&mut self) {
        self.sealed = true;
    }

    pub fn undo(&mut self) -> Option<Edit> {
        let edit = self.undo.pop()?;
        self.redo.push(edit.clone());
        self.sealed = true;
        Some(edit)
    }

    pub fn redo(&mut self) -> Option<Edit> {
        let edit = self.redo.pop()?;
        self.undo.push(edit.clone());
        self.sealed = true;
        Some(edit)
    }

    pub fn can_undo(&self) -> bool {
        !self.undo.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.redo.is_empty()
    }
}
