/// How long the pointer must rest on a target before its tooltip shows, in milliseconds. Measured on the Kotlin app:
/// nothing at 450 ms, shown at 500 ms.
pub const SHOW_DELAY_MS: u64 = 500;

/// A tooltip's timing: it appears after the pointer has rested on the target for [`SHOW_DELAY_MS`], and goes away
/// the moment the pointer leaves or the target is clicked. Keyboard focus never shows it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Tooltip {
    /// Milliseconds the pointer has rested on the target, while it is over it.
    hovering_for: Option<u64>,
    shown: bool,
}

impl Tooltip {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn shown(&self) -> bool {
        self.shown
    }

    /// The pointer entered the target.
    pub fn pointer_enter(&mut self) {
        if self.hovering_for.is_none() {
            self.hovering_for = Some(0);
        }
    }

    /// The pointer left the target.
    pub fn pointer_leave(&mut self) {
        self.hovering_for = None;
        self.shown = false;
    }

    /// The target was clicked: the tooltip gets out of the way.
    pub fn press(&mut self) {
        self.shown = false;
        // A click starts the wait over only if the pointer stays; Kotlin shows nothing more until it re-enters.
        self.hovering_for = None;
    }

    /// Time passes.
    pub fn advance(&mut self, ms: u64) {
        if let Some(t) = self.hovering_for.as_mut() {
            *t += ms;
            if *t >= SHOW_DELAY_MS {
                self.shown = true;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shows_after_the_delay_only() {
        let mut t = Tooltip::new();
        t.pointer_enter();
        t.advance(SHOW_DELAY_MS - 1);
        assert!(!t.shown());
        t.advance(1);
        assert!(t.shown());
    }

    #[test]
    fn leaving_hides_and_cancels() {
        let mut t = Tooltip::new();
        t.pointer_enter();
        t.advance(100);
        t.pointer_leave();
        t.advance(2000);
        assert!(!t.shown());
    }

    #[test]
    fn nothing_without_the_pointer() {
        let mut t = Tooltip::new();
        t.advance(5000);
        assert!(!t.shown());
    }
}
