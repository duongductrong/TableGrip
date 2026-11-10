use crate::input::{Input, Key};

/// A non-editable ListComboBox: the app owns the selected index and is told of changes.
///
/// Verified against the Kotlin app: a click opens the list and a second click closes it; with the list open Up and
/// Down change the selection at once (there is no separate highlight to confirm), Enter and Escape close it and keep
/// what is selected; with it closed Up, Down and Enter do nothing and Space opens it; a disabled box ignores
/// everything; a click focuses it. Behaviour past the first and last item is not verified.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ListComboBox {
    pub len: usize,
    pub selected: usize,
    pub enabled: bool,
    open: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ComboResponse {
    /// The new selected index, when the input changed it.
    pub changed: Option<usize>,
    pub focus: bool,
}

impl ListComboBox {
    pub fn new(len: usize, selected: usize) -> Self {
        ListComboBox {
            len,
            selected,
            enabled: true,
            open: false,
        }
    }

    pub fn is_open(&self) -> bool {
        self.open
    }

    pub fn handle(&mut self, input: &Input) -> ComboResponse {
        if !self.enabled {
            return ComboResponse::default();
        }
        let mut response = ComboResponse::default();
        match input {
            Input::Click => {
                self.open = !self.open;
                response.focus = true;
            }
            Input::Key(Key::Space, mods) if mods.none() && !self.open => self.open = true,
            Input::Key(Key::Enter | Key::Escape, mods) if mods.none() => self.open = false,
            Input::Key(key @ (Key::Up | Key::Down), mods) if mods.none() && self.open => {
                let next = if *key == Key::Down {
                    (self.selected + 1).min(self.len.saturating_sub(1))
                } else {
                    self.selected.saturating_sub(1)
                };
                if next != self.selected {
                    self.selected = next;
                    response.changed = Some(next);
                }
            }
            _ => {}
        }
        response
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::Modifiers;

    fn key(k: Key) -> Input {
        Input::Key(k, Modifiers::default())
    }

    #[test]
    fn down_selects_at_once_only_while_open() {
        let mut c = ListComboBox::new(4, 0);
        assert_eq!(c.handle(&key(Key::Down)).changed, None);
        c.handle(&Input::Click);
        assert_eq!(c.handle(&key(Key::Down)).changed, Some(1));
        c.handle(&key(Key::Escape));
        assert!(!c.is_open());
        assert_eq!(c.selected, 1);
    }

    #[test]
    fn enter_does_not_open_but_space_does() {
        let mut c = ListComboBox::new(2, 0);
        c.handle(&key(Key::Enter));
        assert!(!c.is_open());
        c.handle(&key(Key::Space));
        assert!(c.is_open());
    }
}
