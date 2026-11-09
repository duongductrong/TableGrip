/// A key, named by what it does rather than by a platform's key code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Key {
    Space,
    Enter,
    Tab,
    Escape,
    Backspace,
    Delete,
    Home,
    End,
    Left,
    Right,
    Up,
    Down,
    /// A letter key, upper case.
    Letter(char),
}

impl Key {
    /// The key a scenario file names, such as `Space` or `A`.
    pub fn from_name(name: &str) -> Option<Key> {
        Some(match name {
            "Space" => Key::Space,
            "Enter" => Key::Enter,
            "Tab" => Key::Tab,
            "Escape" => Key::Escape,
            "Backspace" => Key::Backspace,
            "Delete" => Key::Delete,
            "Home" => Key::Home,
            "End" => Key::End,
            "Left" => Key::Left,
            "Right" => Key::Right,
            "Up" => Key::Up,
            "Down" => Key::Down,
            _ => {
                let mut chars = name.chars();
                match (chars.next(), chars.next()) {
                    (Some(c), None) if c.is_ascii_uppercase() => Key::Letter(c),
                    _ => return None,
                }
            }
        })
    }
}

/// Modifiers held with a key. `primary` is Cmd on macOS and Ctrl elsewhere; the rendering layer maps it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Modifiers {
    pub primary: bool,
    pub shift: bool,
    pub alt: bool,
}

impl Modifiers {
    pub fn none(self) -> bool {
        self == Modifiers::default()
    }
}

/// Input that reaches a control.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Input {
    /// A primary-button click inside the control.
    Click,
    /// A key press while the control has focus.
    Key(Key, Modifiers),
    /// Text committed by the keyboard or an input method.
    Text(String),
}
