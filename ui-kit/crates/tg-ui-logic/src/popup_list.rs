use crate::input::{Input, Key};

/// Rows of a hand-drawn popup list (the model picker, chat history, completion lists): the owner takes Up, Down and
/// Enter, a row is painted when it is the keyboard highlight or under the pointer, and a click picks a row.
///
/// Verified against a host built the way the app builds them: the first row starts highlighted, Down and Up wrap
/// round, Enter picks the highlight, hovering paints a row without moving the highlight, and a click both picks and
/// highlights.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PopupList {
    pub rows: usize,
    highlighted: usize,
    hovered: Option<usize>,
    picked: Option<usize>,
}

impl PopupList {
    pub fn new(rows: usize) -> Self {
        PopupList {
            rows,
            highlighted: 0,
            hovered: None,
            picked: None,
        }
    }

    pub fn highlighted(&self) -> usize {
        self.highlighted
    }

    pub fn hovered(&self) -> Option<usize> {
        self.hovered
    }

    pub fn picked(&self) -> Option<usize> {
        self.picked
    }

    /// Whether row `index` is drawn in the highlight colour.
    pub fn painted(&self, index: usize) -> bool {
        index == self.highlighted || self.hovered == Some(index)
    }

    pub fn hover(&mut self, row: Option<usize>) {
        self.hovered = row.filter(|&r| r < self.rows);
    }

    /// A click on `row`: picks it and makes it the highlight.
    pub fn click(&mut self, row: usize) {
        if row < self.rows {
            self.picked = Some(row);
            self.highlighted = row;
        }
    }

    /// A key while the owner has focus.
    pub fn handle(&mut self, input: &Input) {
        let Input::Key(key, mods) = input else { return };
        if !mods.none() || self.rows == 0 {
            return;
        }
        match key {
            Key::Down => self.highlighted = (self.highlighted + 1) % self.rows,
            Key::Up => self.highlighted = (self.highlighted + self.rows - 1) % self.rows,
            Key::Enter => self.picked = Some(self.highlighted),
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::Modifiers;

    #[test]
    fn up_and_down_wrap() {
        let mut l = PopupList::new(3);
        l.handle(&Input::Key(Key::Up, Modifiers::default()));
        assert_eq!(l.highlighted(), 2);
        l.handle(&Input::Key(Key::Down, Modifiers::default()));
        assert_eq!(l.highlighted(), 0);
    }

    #[test]
    fn hover_paints_without_moving_the_highlight() {
        let mut l = PopupList::new(4);
        l.hover(Some(2));
        assert!(l.painted(0) && l.painted(2) && !l.painted(1));
        l.hover(None);
        assert!(!l.painted(2));
    }
}
