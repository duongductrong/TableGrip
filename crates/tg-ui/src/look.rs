//! A look to draw regardless of the pointer and keyboard focus, for the gallery's static specimens: a screenshot can
//! hold one pointer and one focused control, so the states that cannot all be real are painted from a look instead.

/// Interaction state to draw a control in. Pressing implies focus, as in the Kotlin app, where a press takes focus.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Look {
    pub hovered: bool,
    pub pressed: bool,
    pub focused: bool,
}

impl Look {
    pub const HOVERED: Look = Look {
        hovered: true,
        pressed: false,
        focused: false,
    };
    pub const FOCUSED: Look = Look {
        hovered: false,
        pressed: false,
        focused: true,
    };
    pub const PRESSED: Look = Look {
        hovered: true,
        pressed: true,
        focused: true,
    };
}
