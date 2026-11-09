use crate::input::{Input, Key};

/// A text button in the link colour (Jewel's `Link(text, onClick)` in the Kotlin app), as `conformance/scenarios/link.json`
/// observes it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Link {
    pub enabled: bool,
    /// The pointer is over the link; it changes the look (underline), never the behaviour.
    pub hovered: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LinkResponse {
    /// The link was activated: run its action.
    pub activated: bool,
    pub focus: bool,
}

impl Default for Link {
    fn default() -> Self {
        Link::new()
    }
}

impl Link {
    pub fn new() -> Self {
        Link {
            enabled: true,
            hovered: false,
        }
    }

    /// Only an enabled link takes focus, by click or Tab.
    pub fn focusable(&self) -> bool {
        self.enabled
    }

    /// A click activates an enabled link and focuses it; so do Space and Enter while focused, with or without
    /// modifiers (unlike a button, a link has no shortcut of its own to give way to). Each click of a double click
    /// counts.
    pub fn handle(&mut self, input: &Input) -> LinkResponse {
        if !self.enabled {
            return LinkResponse::default();
        }
        LinkResponse {
            activated: matches!(input, Input::Click | Input::Key(Key::Space | Key::Enter, _)),
            focus: matches!(input, Input::Click),
        }
    }

    pub fn hover(&mut self, inside: bool) {
        self.hovered = inside;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::Modifiers;

    #[test]
    fn disabled_links_ignore_everything() {
        let mut link = Link {
            enabled: false,
            hovered: false,
        };
        assert_eq!(link.handle(&Input::Click), LinkResponse::default());
        assert!(!link.focusable());
    }

    #[test]
    fn modified_enter_still_fires() {
        let mods = Modifiers {
            primary: true,
            ..Modifiers::default()
        };
        assert!(Link::new().handle(&Input::Key(Key::Enter, mods)).activated);
    }
}
