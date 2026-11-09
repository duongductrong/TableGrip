/// What a click on the track does.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum TrackClick {
    /// Jewel's default: a page (one viewport) towards the click.
    #[default]
    Page,
    /// The app's always-visible style in KeyBrowserView: the thumb's top goes to the click.
    JumpToSpot,
}

/// A vertical scrollbar over content taller than its viewport. Lengths are in any one unit (dp); `offset` is how far
/// the content is scrolled, `0..=max()`.
///
/// Verified against the Kotlin app: with `Page`, a click on the track is ignored until the pointer has been over the
/// bar, then moves one viewport towards the click (clamped); with `JumpToSpot` the thumb's top lands on the click
/// (clamped), whether or not the pointer was over the bar first.
#[derive(Clone, Debug, PartialEq)]
pub struct Scrollbar {
    pub viewport: f64,
    pub content: f64,
    pub track: f64,
    pub offset: f64,
    pub behavior: TrackClick,
    hovered: bool,
}

impl Scrollbar {
    pub fn new(viewport: f64, content: f64, track: f64, behavior: TrackClick) -> Self {
        Scrollbar {
            viewport,
            content,
            track,
            offset: 0.0,
            behavior,
            hovered: false,
        }
    }

    /// The furthest the content can scroll.
    pub fn max(&self) -> f64 {
        (self.content - self.viewport).max(0.0)
    }

    /// Whether there is anything to scroll.
    pub fn scrollable(&self) -> bool {
        self.max() > 0.0
    }

    /// The thumb's length: the track in the proportion the viewport has to the content.
    pub fn thumb_len(&self) -> f64 {
        if self.scrollable() {
            self.track * self.viewport / self.content
        } else {
            self.track
        }
    }

    /// Where the thumb starts along the track.
    pub fn thumb_start(&self) -> f64 {
        if self.scrollable() {
            self.offset / self.max() * (self.track - self.thumb_len())
        } else {
            0.0
        }
    }

    /// How far scrolled, as a share of the way to the end (0 when nothing scrolls).
    pub fn progress(&self) -> f64 {
        if self.scrollable() {
            self.offset / self.max()
        } else {
            0.0
        }
    }

    pub fn set_offset(&mut self, offset: f64) {
        self.offset = offset.clamp(0.0, self.max());
    }

    pub fn pointer_enter(&mut self) {
        self.hovered = true;
    }

    pub fn pointer_leave(&mut self) {
        self.hovered = false;
    }

    /// A primary click at `y` along the track.
    pub fn click_track(&mut self, y: f64) {
        if !self.scrollable() {
            return;
        }
        match self.behavior {
            TrackClick::Page => {
                if !self.hovered {
                    return;
                }
                if y < self.thumb_start() {
                    self.set_offset(self.offset - self.viewport);
                } else if y >= self.thumb_start() + self.thumb_len() {
                    self.set_offset(self.offset + self.viewport);
                }
            }
            TrackClick::JumpToSpot => {
                let free = self.track - self.thumb_len();
                self.set_offset(y / free * self.max());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bar(content: f64, behavior: TrackClick) -> Scrollbar {
        Scrollbar::new(200.0, content, 200.0, behavior)
    }

    #[test]
    fn the_thumb_is_the_visible_share_of_the_track() {
        let mut b = bar(1000.0, TrackClick::Page);
        assert_eq!(b.thumb_len(), 40.0);
        assert_eq!(b.thumb_start(), 0.0);
        b.set_offset(b.max());
        assert_eq!(b.thumb_start() + b.thumb_len(), 200.0);
    }

    #[test]
    fn content_that_fits_has_a_full_track_thumb_and_no_scroll() {
        let b = bar(100.0, TrackClick::Page);
        assert!(!b.scrollable());
        assert_eq!(
            (b.thumb_len(), b.thumb_start(), b.progress()),
            (200.0, 0.0, 0.0)
        );
    }

    #[test]
    fn a_page_click_needs_the_pointer_over_the_bar() {
        let mut b = bar(1000.0, TrackClick::Page);
        b.click_track(100.0);
        assert_eq!(b.offset, 0.0);
        b.pointer_enter();
        b.click_track(100.0);
        assert_eq!(b.offset, 200.0);
    }

    #[test]
    fn paging_clamps_at_both_ends() {
        let mut b = bar(400.0, TrackClick::Page);
        b.pointer_enter();
        b.click_track(150.0);
        b.click_track(150.0);
        assert_eq!(b.offset, 200.0);
        b.set_offset(0.0);
        b.click_track(0.0);
        assert_eq!(b.offset, 0.0);
    }

    #[test]
    fn a_jump_puts_the_thumb_top_at_the_click() {
        let mut b = bar(1000.0, TrackClick::JumpToSpot);
        b.click_track(100.0);
        assert_eq!(b.thumb_start().round(), 100.0);
        assert_eq!((b.progress() * 100.0).round(), 63.0);
    }
}
