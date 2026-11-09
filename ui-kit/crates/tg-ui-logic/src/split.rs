/// Which window divider a saved position belongs to; each has a range that keeps both sides usable.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SplitKind {
    /// The explorer sidebar's divider.
    Sidebar,
    /// The agent panel's divider.
    Agent,
}

impl SplitKind {
    /// The saved positions that are restored as they were; anything else is brought to the nearest end.
    pub fn restore_range(self) -> (f64, f64) {
        match self {
            SplitKind::Sidebar => (0.1, 0.6),
            SplitKind::Agent => (0.3, 0.9),
        }
    }
}

/// A two-pane horizontal split whose divider is a ratio of the width.
///
/// Verified against the Kotlin app (Jewel's `HorizontalSplitLayout`): the first pane is `ratio` of the width, lifted to
/// its minimum and then pulled back so the second keeps its minimum; the layout writes the result back, so the
/// divider's position after layout is the clamped one. Saved positions are restored through [`SplitKind`]'s range.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Split {
    pub ratio: f64,
    pub first_min: f64,
    pub second_min: f64,
}

impl Split {
    pub fn new(ratio: f64, first_min: f64, second_min: f64) -> Self {
        Split {
            ratio,
            first_min,
            second_min,
        }
    }

    /// A split put back from a saved `ratio`.
    pub fn restored(kind: SplitKind, saved: f64, first_min: f64, second_min: f64) -> Self {
        let (low, high) = kind.restore_range();
        Split::new(saved.clamp(low, high), first_min, second_min)
    }

    /// The first pane's width in a split `total` wide.
    pub fn first_width(&self, total: f64) -> f64 {
        let wanted = (self.ratio * total).max(self.first_min);
        wanted
            .min((total - self.second_min).max(0.0))
            .clamp(0.0, total)
    }

    pub fn second_width(&self, total: f64) -> f64 {
        total - self.first_width(total)
    }

    /// Lays out in `total` and keeps the clamped position, as the Kotlin layout does.
    pub fn layout(&mut self, total: f64) {
        if total > 0.0 {
            self.ratio = self.first_width(total) / total;
        }
    }

    /// The divider dragged so the first pane ends at `x`.
    pub fn drag_to(&mut self, x: f64, total: f64) {
        if total > 0.0 {
            self.ratio = x / total;
            self.layout(total);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_pane_is_lifted_and_the_second_kept() {
        let s = Split::new(0.1, 220.0, 420.0);
        assert_eq!(s.first_width(800.0), 220.0);
        let s = Split::new(0.9, 220.0, 420.0);
        assert_eq!(s.first_width(800.0), 380.0);
        assert_eq!(s.second_width(800.0), 420.0);
    }

    #[test]
    fn layout_keeps_the_clamped_ratio() {
        let mut s = Split::new(0.9, 220.0, 420.0);
        s.layout(800.0);
        assert!((s.ratio - 0.475).abs() < 1e-9);
        s.drag_to(0.0, 800.0);
        assert!((s.first_width(800.0) - 220.0).abs() < 1e-9);
    }

    #[test]
    fn saved_positions_are_clamped_by_kind() {
        let at = |kind, saved| Split::restored(kind, saved, 0.0, 0.0).ratio;
        assert_eq!(at(SplitKind::Sidebar, 0.9), 0.6);
        assert_eq!(at(SplitKind::Sidebar, 0.02), 0.1);
        assert_eq!(at(SplitKind::Sidebar, 0.3), 0.3);
        assert_eq!(at(SplitKind::Agent, 0.99), 0.9);
        assert_eq!(at(SplitKind::Agent, 0.1), 0.3);
        assert_eq!(at(SplitKind::Agent, 0.5), 0.5);
    }

    #[test]
    fn a_window_too_small_for_both_minimums_degrades_without_panicking() {
        let s = Split::new(0.5, 220.0, 420.0);
        let w = s.first_width(500.0);
        assert!((0.0..=500.0).contains(&w));
    }
}
