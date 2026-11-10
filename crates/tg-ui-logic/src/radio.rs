use crate::input::{Input, Key};

/// A group of radio rows the way the app uses them: it owns which row is selected and each row only reports clicks.
///
/// Verified against the Kotlin app: every row is its own Tab stop, Space or Enter selects the focused row, a click
/// selects and focuses a row, and the arrow keys do nothing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RadioGroup {
    pub rows: usize,
    pub selected: Option<usize>,
    /// The row that has focus, when the group does.
    pub focused: Option<usize>,
}

/// What handling an input asks of the host.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct RadioResponse {
    /// The row that was just selected by this input.
    pub chosen: Option<usize>,
    /// The group wants focus (a click focuses its row).
    pub focus: bool,
}

impl RadioGroup {
    pub fn new(rows: usize, selected: Option<usize>) -> Self {
        RadioGroup {
            rows,
            selected,
            focused: None,
        }
    }

    /// A click on `row`.
    pub fn click(&mut self, row: usize) -> RadioResponse {
        if row >= self.rows {
            return RadioResponse::default();
        }
        self.selected = Some(row);
        self.focused = Some(row);
        RadioResponse {
            chosen: Some(row),
            focus: true,
        }
    }

    /// Focus lands on the group (Tab in, a focus request): the first row takes it.
    pub fn focus_first(&mut self) {
        self.focused = (self.rows > 0).then_some(0);
    }

    /// Tab (`forward`) or Shift+Tab inside the group; true while focus stays in the group.
    pub fn tab(&mut self, forward: bool) -> bool {
        let Some(i) = self.focused else { return false };
        let next = if forward {
            i + 1
        } else {
            match i.checked_sub(1) {
                Some(p) => p,
                None => return false,
            }
        };
        if next >= self.rows {
            return false;
        }
        self.focused = Some(next);
        true
    }

    /// A key while a row has focus.
    pub fn handle(&mut self, input: &Input) -> RadioResponse {
        match (input, self.focused) {
            (Input::Click, _) => RadioResponse::default(),
            (Input::Key(Key::Space | Key::Enter, mods), Some(row)) if mods.none() => {
                self.selected = Some(row);
                RadioResponse {
                    chosen: Some(row),
                    focus: false,
                }
            }
            _ => RadioResponse::default(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::Modifiers;

    fn space() -> Input {
        Input::Key(Key::Space, Modifiers::default())
    }

    #[test]
    fn space_selects_the_focused_row_after_tabbing_to_it() {
        let mut g = RadioGroup::new(3, None);
        g.focus_first();
        assert!(g.tab(true));
        assert_eq!(g.handle(&space()).chosen, Some(1));
        assert_eq!(g.selected, Some(1));
    }

    #[test]
    fn arrows_move_nothing() {
        let mut g = RadioGroup::new(3, Some(0));
        g.focus_first();
        g.handle(&Input::Key(Key::Down, Modifiers::default()));
        assert_eq!((g.selected, g.focused), (Some(0), Some(0)));
    }

    #[test]
    fn tab_leaves_after_the_last_row() {
        let mut g = RadioGroup::new(2, None);
        g.focus_first();
        assert!(g.tab(true));
        assert!(!g.tab(true));
    }

    #[test]
    fn a_click_selects_and_focuses_its_row() {
        let mut g = RadioGroup::new(3, Some(0));
        let r = g.click(2);
        assert_eq!((g.selected, g.focused, r.focus), (Some(2), Some(2), true));
    }
}
