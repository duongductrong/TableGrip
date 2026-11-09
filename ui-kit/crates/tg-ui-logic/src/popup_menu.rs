use crate::input::{Input, Key};

/// A PopupMenu as the app uses it: the app owns the open flag, items are selectable (some disabled) and separators
/// are drawn between them but are not items. No submenus.
///
/// Verified against the Kotlin app: opening highlights the selected item (the first when none is), Up and Down move
/// the highlight over enabled items and wrap, Enter or Space chooses the highlighted item and closes, Escape or a
/// click outside closes without choosing, a click on an enabled item chooses and closes, a click on a disabled item
/// does nothing, and Home and End do nothing. Typing other keys does nothing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PopupMenu {
    enabled: Vec<bool>,
    selected: Option<usize>,
    open: bool,
    highlighted: Option<usize>,
}

/// What handling an input asks of the host.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MenuResponse {
    /// The item that was chosen by this input: run its action. The menu has closed.
    pub chosen: Option<usize>,
}

impl PopupMenu {
    /// A menu whose items are enabled or not; `selected` marks the current choice.
    pub fn new(enabled: Vec<bool>, selected: Option<usize>) -> Self {
        PopupMenu {
            enabled,
            selected,
            open: false,
            highlighted: None,
        }
    }

    /// How many items the menu has.
    pub fn len(&self) -> usize {
        self.enabled.len()
    }

    pub fn is_empty(&self) -> bool {
        self.enabled.is_empty()
    }

    pub fn is_open(&self) -> bool {
        self.open
    }

    pub fn highlighted(&self) -> Option<usize> {
        self.highlighted
    }

    pub fn open(&mut self) {
        self.open = true;
        self.highlighted = self
            .selected
            .or(Some(0))
            .filter(|&i| i < self.enabled.len());
    }

    pub fn close(&mut self) {
        self.open = false;
        self.highlighted = None;
    }

    /// A click on item `index`.
    pub fn click_item(&mut self, index: usize) -> MenuResponse {
        if !self.open || self.enabled.get(index) != Some(&true) {
            return MenuResponse::default();
        }
        self.close();
        MenuResponse {
            chosen: Some(index),
        }
    }

    /// A click anywhere outside the menu.
    pub fn click_outside(&mut self) {
        self.close();
    }

    /// The next enabled item after the highlight in `step` (+1 or -1) direction, wrapping round.
    fn step(&self, forward: bool) -> Option<usize> {
        let len = self.enabled.len();
        let from = self
            .highlighted
            .unwrap_or(if forward { len - 1 } else { 0 });
        (1..=len)
            .map(|n| {
                if forward {
                    (from + n) % len
                } else {
                    (from + len - n % len) % len
                }
            })
            .find(|&i| self.enabled[i])
    }

    /// A key while the menu is open.
    pub fn handle(&mut self, input: &Input) -> MenuResponse {
        if !self.open || self.enabled.is_empty() {
            return MenuResponse::default();
        }
        let Input::Key(key, mods) = input else {
            return MenuResponse::default();
        };
        if !mods.none() {
            return MenuResponse::default();
        }
        match key {
            Key::Escape => self.close(),
            Key::Down => self.highlighted = self.step(true).or(self.highlighted),
            Key::Up => self.highlighted = self.step(false).or(self.highlighted),
            Key::Enter | Key::Space => {
                if let Some(i) = self.highlighted.filter(|&i| self.enabled[i]) {
                    self.close();
                    return MenuResponse { chosen: Some(i) };
                }
            }
            _ => {}
        }
        MenuResponse::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::Modifiers;

    fn key(k: Key) -> Input {
        Input::Key(k, Modifiers::default())
    }

    fn menu(enabled: &[bool], selected: Option<usize>) -> PopupMenu {
        let mut m = PopupMenu::new(enabled.to_vec(), selected);
        m.open();
        m
    }

    #[test]
    fn down_skips_disabled_items_and_wraps() {
        let mut m = menu(&[true, false, true], Some(0));
        m.handle(&key(Key::Down));
        assert_eq!(m.highlighted(), Some(2));
        m.handle(&key(Key::Down));
        assert_eq!(m.highlighted(), Some(0));
    }

    #[test]
    fn up_from_the_first_wraps_to_the_last() {
        let mut m = menu(&[true; 4], Some(0));
        m.handle(&key(Key::Up));
        assert_eq!(m.highlighted(), Some(3));
    }

    #[test]
    fn opening_highlights_the_selected_item() {
        assert_eq!(menu(&[true; 4], Some(2)).highlighted(), Some(2));
        assert_eq!(menu(&[true; 4], None).highlighted(), Some(0));
    }

    #[test]
    fn enter_chooses_and_closes() {
        let mut m = menu(&[true; 3], Some(0));
        m.handle(&key(Key::Down));
        assert_eq!(m.handle(&key(Key::Enter)).chosen, Some(1));
        assert!(!m.is_open());
    }

    #[test]
    fn a_disabled_highlight_chooses_nothing() {
        let mut m = menu(&[false, true], None);
        assert_eq!(m.handle(&key(Key::Enter)).chosen, None);
        assert!(m.is_open());
    }

    #[test]
    fn disabled_clicks_are_ignored_and_outside_clicks_close() {
        let mut m = menu(&[true, false], Some(0));
        assert_eq!(m.click_item(1).chosen, None);
        assert!(m.is_open());
        m.click_outside();
        assert!(!m.is_open());
    }
}
