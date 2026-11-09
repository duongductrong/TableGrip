use crate::input::Key;

/// Jewel's tab strip as the dialogs and the console's result tabs use it: one selected tab, closable or not, scrolling
/// sideways when the tabs outgrow the strip.
///
/// Verified against the Kotlin app:
/// - a click selects a tab and does not give the strip keyboard focus;
/// - with focus, Left and Right move the selection to the previous or next tab and wrap at the ends, Home and End go to
///   the first and last; modifiers make no difference, and Enter, Space, Up, Down and Delete do nothing;
/// - the selected tab is scrolled into view, aligned to the edge it came from;
/// - the strip is a single Tab stop.
///
/// Closing is the app's decision (the strip only reports the click); [`TabStrip::close`] is the rule the console and
/// dialogs follow: the selection stays on its tab, or moves to the one before when the closed tab was it or earlier,
/// except at the very start.
#[derive(Clone, Debug, PartialEq)]
pub struct TabStrip {
    widths: Vec<f64>,
    viewport: f64,
    offset: f64,
    selected: Option<usize>,
}

impl TabStrip {
    /// Tabs of the given `widths` in a strip `viewport` wide, `selected` first (scrolled into view).
    pub fn new(widths: Vec<f64>, viewport: f64, selected: Option<usize>) -> Self {
        let mut strip = TabStrip {
            widths,
            viewport,
            offset: 0.0,
            selected: None,
        };
        if let Some(i) = selected {
            strip.select(i);
        }
        strip
    }

    pub fn len(&self) -> usize {
        self.widths.len()
    }

    pub fn is_empty(&self) -> bool {
        self.widths.is_empty()
    }

    pub fn selected(&self) -> Option<usize> {
        self.selected
    }

    pub fn offset(&self) -> f64 {
        self.offset
    }

    fn content_width(&self) -> f64 {
        self.widths.iter().sum()
    }

    fn max_offset(&self) -> f64 {
        (self.content_width() - self.viewport).max(0.0)
    }

    fn span(&self, index: usize) -> (f64, f64) {
        let start: f64 = self.widths[..index].iter().sum();
        (start, start + self.widths[index])
    }

    /// Scrolls by `dx` (the wheel or a trackpad), within the content.
    pub fn scroll_by(&mut self, dx: f64) {
        self.offset = (self.offset + dx).clamp(0.0, self.max_offset());
    }

    pub fn set_viewport(&mut self, viewport: f64) {
        self.viewport = viewport;
        self.offset = self.offset.clamp(0.0, self.max_offset());
    }

    /// Whether tab `index` lies wholly inside the strip.
    pub fn is_visible(&self, index: usize) -> bool {
        index < self.len() && {
            let (start, end) = self.span(index);
            start >= self.offset && end <= self.offset + self.viewport
        }
    }

    /// Selects `index` and scrolls the least that brings it fully into view.
    pub fn select(&mut self, index: usize) {
        if index >= self.len() {
            return;
        }
        self.selected = Some(index);
        let (start, end) = self.span(index);
        if start < self.offset {
            self.offset = start;
        } else if end > self.offset + self.viewport {
            self.offset = end - self.viewport;
        }
        self.offset = self.offset.clamp(0.0, self.max_offset());
    }

    /// A click on tab `index`.
    pub fn click(&mut self, index: usize) {
        self.select(index);
    }

    /// A key press while the strip has focus; true when the strip used it.
    pub fn key(&mut self, key: Key) -> bool {
        let len = self.len();
        if len == 0 {
            return false;
        }
        let at = self.selected.unwrap_or(0);
        let target = match key {
            Key::Right => (at + 1) % len,
            Key::Left => (at + len - 1) % len,
            Key::Home => 0,
            Key::End => len - 1,
            _ => return false,
        };
        self.select(target);
        true
    }

    /// Removes tab `index` (its close button was clicked) and settles the selection.
    pub fn close(&mut self, index: usize) {
        if index >= self.len() {
            return;
        }
        self.widths.remove(index);
        self.selected = match self.selected {
            _ if self.widths.is_empty() => None,
            Some(sel) if sel >= index && sel > 0 => Some(sel - 1),
            other => other,
        };
        self.offset = self.offset.clamp(0.0, self.max_offset());
        if let Some(sel) = self.selected {
            self.select(sel);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strip(n: usize, viewport: f64, selected: Option<usize>) -> TabStrip {
        TabStrip::new(vec![75.0; n], viewport, selected)
    }

    #[test]
    fn arrows_wrap_and_home_end_jump() {
        let mut s = strip(3, 400.0, Some(0));
        s.key(Key::Left);
        assert_eq!(s.selected(), Some(2));
        s.key(Key::Right);
        assert_eq!(s.selected(), Some(0));
        s.key(Key::End);
        assert_eq!(s.selected(), Some(2));
        s.key(Key::Home);
        assert_eq!(s.selected(), Some(0));
        assert!(!s.key(Key::Enter));
    }

    #[test]
    fn the_selected_tab_is_scrolled_in_from_the_side_it_came_from() {
        let s = strip(10, 200.0, Some(8));
        assert!(s.is_visible(8));
        assert_eq!(s.offset(), 9.0 * 75.0 - 200.0);
        let mut s = strip(10, 200.0, Some(9));
        assert!(s.is_visible(9));
        s.key(Key::Home);
        assert_eq!(s.offset(), 0.0);
        s.key(Key::Left);
        assert!(s.is_visible(9));
    }

    #[test]
    fn closing_follows_the_app_rule() {
        let case = |selected, close| {
            let mut s = strip(3, 400.0, Some(selected));
            s.close(close);
            s.selected()
        };
        assert_eq!(case(2, 2), Some(1));
        assert_eq!(case(2, 1), Some(1));
        assert_eq!(case(1, 1), Some(0));
        assert_eq!(case(0, 0), Some(0));
        assert_eq!(case(0, 1), Some(0));
        let mut s = strip(1, 400.0, Some(0));
        s.close(0);
        assert_eq!(s.selected(), None);
    }

    #[test]
    fn scrolling_stays_within_the_content() {
        let mut s = strip(10, 200.0, Some(0));
        s.scroll_by(-50.0);
        assert_eq!(s.offset(), 0.0);
        s.scroll_by(10_000.0);
        assert_eq!(s.offset(), 550.0);
    }
}
