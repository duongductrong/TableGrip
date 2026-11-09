//! The workspace's own pill tabs: drag to reorder, scroll while held against an edge, middle-click to close.
//!
//! A port of the app's `TabDragState`, with the lazy row's layout reduced to widths and a scroll offset. The dragged
//! tab follows the pointer within the row, trades places with a neighbour once it covers that neighbour's middle,
//! scrolls the row while pushed against an edge, and glides into its slot on release.

/// How a pointer button on a tab acts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Button {
    Primary,
    Middle,
}

/// What a press on a tab asks the app to do.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TabAction {
    Select(usize),
    Close(usize),
}

/// A press on tab `index`: the primary button selects it (and a drag starts from it), the middle button closes it.
pub fn press(index: usize, button: Button) -> TabAction {
    match button {
        Button::Primary => TabAction::Select(index),
        Button::Middle => TabAction::Close(index),
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct TabRow {
    /// Width of each tab in order, plus the gap between neighbours.
    widths: Vec<f64>,
    gap: f64,
    viewport: f64,
    scroll: f64,
    /// The tab being dragged, by index in `widths`.
    dragged: Option<usize>,
    /// How far the dragged tab is drawn from its slot.
    offset: f64,
    /// Pixels per frame the row scrolls under the dragged tab while it is pushed against an edge.
    scroll_speed: f64,
    edge: f64,
    max_scroll_per_frame: f64,
    dragging: bool,
}

impl TabRow {
    /// A row at `density` (1.0 for a plain screen): the edge zone is 32 and the fastest scroll 10 pixels a frame, both
    /// scaled.
    pub fn new(widths: Vec<f64>, gap: f64, viewport: f64, density: f64) -> Self {
        TabRow {
            widths,
            gap,
            viewport,
            scroll: 0.0,
            dragged: None,
            offset: 0.0,
            scroll_speed: 0.0,
            edge: 32.0 * density,
            max_scroll_per_frame: 10.0 * density,
            dragging: false,
        }
    }

    pub fn len(&self) -> usize {
        self.widths.len()
    }

    pub fn is_empty(&self) -> bool {
        self.widths.is_empty()
    }

    pub fn scroll(&self) -> f64 {
        self.scroll
    }

    pub fn set_scroll(&mut self, scroll: f64) {
        self.scroll = scroll.clamp(0.0, self.max_scroll());
    }

    pub fn offset(&self) -> f64 {
        self.offset
    }

    pub fn scroll_speed(&self) -> f64 {
        self.scroll_speed
    }

    pub fn is_dragging(&self) -> bool {
        self.dragging
    }

    /// The tab being dragged (or still gliding home), by its current index.
    pub fn dragged(&self) -> Option<usize> {
        self.dragged
    }

    fn content(&self) -> f64 {
        self.widths.iter().sum::<f64>() + self.gap * self.widths.len().saturating_sub(1) as f64
    }

    fn max_scroll(&self) -> f64 {
        (self.content() - self.viewport).max(0.0)
    }

    /// Where tab `i` starts relative to the viewport's start (scrolling moves it).
    fn item_offset(&self, i: usize) -> f64 {
        self.widths[..i].iter().sum::<f64>() + self.gap * i as f64 - self.scroll
    }

    fn visible(&self, i: usize) -> bool {
        let start = self.item_offset(i);
        start + self.widths[i] > 0.0 && start < self.viewport
    }

    /// The pointer pressed tab `index` and started to drag it.
    pub fn start(&mut self, index: usize) {
        if self.dragged != Some(index) {
            self.offset = 0.0;
        }
        self.dragged = Some(index);
        self.dragging = true;
    }

    /// The pointer moved by `delta` along the row.
    pub fn drag(&mut self, delta: f64) -> Option<(usize, usize)> {
        self.offset += delta;
        self.follow_pointer()
    }

    /// The pointer was released; the tab glides home (the caller animates `offset` to 0 and then calls [`Self::settle`]).
    pub fn end(&mut self) {
        self.dragging = false;
        self.scroll_speed = 0.0;
    }

    /// The glide is over.
    pub fn settle(&mut self) {
        self.offset = 0.0;
        self.dragged = None;
    }

    /// One frame of edge scrolling: scrolls the row by the current speed and keeps the tab under the pointer. Returns
    /// a swap, if the tab traded places with a neighbour.
    pub fn frame(&mut self) -> Option<(usize, usize)> {
        if !self.dragging || self.scroll_speed == 0.0 {
            return None;
        }
        let before = self.scroll;
        self.set_scroll(before + self.scroll_speed);
        // Scrolling moves the tab's slot; shifting the offset back keeps the tab under the pointer.
        self.offset += self.scroll - before;
        self.follow_pointer()
    }

    /// Keeps the dragged tab inside the row and moves it past the neighbour it covers the middle of. Returns the swap
    /// as `(from, to)` indexes, which the app applies to its own list (the row already did to its widths).
    fn follow_pointer(&mut self) -> Option<(usize, usize)> {
        let index = self.dragged?;
        let width = self.widths[index];
        let slot_start = self.item_offset(index);
        let min = -slot_start;
        let max = self.viewport - width - slot_start;
        self.offset = self.offset.clamp(min, max.max(min));

        let start = slot_start + self.offset;
        let end = start + width;
        let push = if start < self.edge {
            start - self.edge
        } else if end > self.viewport - self.edge {
            end - (self.viewport - self.edge)
        } else {
            0.0
        };
        let can_back = self.scroll > 0.0;
        let can_forward = self.scroll < self.max_scroll();
        self.scroll_speed = if (push < 0.0 && can_back) || (push > 0.0 && can_forward) {
            push / self.edge * self.max_scroll_per_frame
        } else {
            0.0
        };

        let next = (index + 1 < self.len() && self.visible(index + 1)).then_some(index + 1);
        let previous = (index > 0 && self.visible(index - 1)).then(|| index - 1);
        let target = match (next, previous) {
            (Some(n), _) if end > self.item_offset(n) + self.widths[n] / 2.0 => n,
            (_, Some(p)) if start < self.item_offset(p) + self.widths[p] / 2.0 => p,
            _ => return None,
        };
        // Where the dragged tab's slot lands once the two trade places.
        let target_start = self.item_offset(target);
        let slot = if target > index {
            target_start + self.widths[target] - width
        } else {
            target_start
        };
        self.widths.swap(index, target);
        self.dragged = Some(target);
        self.offset += slot_start - slot;
        Some((index, target))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Applies swaps to a list of titles the way the app's `move` does.
    fn reorder(titles: &mut Vec<usize>, swap: Option<(usize, usize)>) {
        if let Some((from, to)) = swap {
            let tab = titles.remove(from);
            titles.insert(to, tab);
        }
    }

    /// A hand dragging `dx` in 20 small steps, then letting go.
    fn drag(row: &mut TabRow, titles: &mut Vec<usize>, index: usize, dx: f64) {
        row.start(index);
        for _ in 0..20 {
            let swap = row.drag(dx / 20.0);
            reorder(titles, swap);
        }
        row.end();
    }

    fn row(n: usize, viewport: f64) -> (TabRow, Vec<usize>) {
        (
            TabRow::new(vec![80.0; n], 4.0, viewport, 1.0),
            (0..n).collect(),
        )
    }

    #[test]
    fn dragging_right_past_the_neighbours_reorders() {
        let (mut row, mut titles) = row(3, 900.0);
        drag(&mut row, &mut titles, 0, 260.0);
        assert_eq!(titles, [1, 2, 0]);
    }

    #[test]
    fn dragging_left_past_a_neighbour_reorders() {
        let (mut row, mut titles) = row(3, 900.0);
        drag(&mut row, &mut titles, 2, -90.0);
        assert_eq!(titles, [0, 2, 1]);
    }

    #[test]
    fn a_short_drag_leaves_the_order_alone() {
        let (mut row, mut titles) = row(3, 900.0);
        drag(&mut row, &mut titles, 1, 15.0);
        assert_eq!(titles, [0, 1, 2]);
        assert_eq!(row.offset(), 15.0);
    }

    #[test]
    fn the_tab_cannot_leave_the_row() {
        let (mut row, mut titles) = row(3, 900.0);
        drag(&mut row, &mut titles, 0, -500.0);
        assert_eq!(titles, [0, 1, 2]);
        assert_eq!(row.offset(), 0.0);
    }

    #[test]
    fn a_swap_keeps_the_tab_under_the_pointer() {
        let (mut row, _) = row(3, 900.0);
        row.start(0);
        // Cover just over half of the neighbour: 80 + 4 + 40.
        let swap = row.drag(125.0);
        assert_eq!(swap, Some((0, 1)));
        // Its slot moved right by 84; the tab is still drawn where the pointer put it (125).
        assert_eq!(row.item_offset(1) + row.offset(), 125.0);
    }

    #[test]
    fn holding_against_the_edge_scrolls_the_row_and_carries_the_tab_along() {
        let (mut row, mut titles) = row(10, 400.0);
        row.start(0);
        for _ in 0..20 {
            let swap = row.drag(30.0);
            reorder(&mut titles, swap);
        }
        // The pointer is held against the right edge: every frame scrolls and may swap.
        let mut frames = 0;
        while row.scroll_speed() != 0.0 && frames < 2000 {
            let swap = row.frame();
            reorder(&mut titles, swap);
            frames += 1;
        }
        row.end();
        assert_eq!(*titles.last().unwrap(), 0);
        assert_eq!(titles[..9], (1..10).collect::<Vec<_>>()[..]);
        assert!(row.scroll() > 0.0);
    }

    #[test]
    fn edge_scrolling_is_faster_the_harder_the_push() {
        let (mut row, _) = row(10, 400.0);
        row.start(0);
        row.drag(310.0);
        let gentle = row.scroll_speed();
        row.drag(30.0);
        assert!(row.scroll_speed() > gentle && gentle > 0.0);
        assert!(row.scroll_speed() <= 10.0);
    }

    #[test]
    fn no_scrolling_where_the_row_cannot_scroll() {
        let (mut row, _) = row(3, 900.0);
        row.start(0);
        row.drag(600.0);
        assert_eq!(row.scroll_speed(), 0.0);
    }

    #[test]
    fn release_stops_the_scrolling_and_settling_clears_the_drag() {
        let (mut row, _) = row(10, 400.0);
        row.start(0);
        row.drag(310.0);
        row.end();
        assert_eq!(row.scroll_speed(), 0.0);
        assert!(!row.is_dragging());
        assert!(row.dragged().is_some());
        row.settle();
        assert_eq!((row.dragged(), row.offset()), (None, 0.0));
    }

    #[test]
    fn middle_click_closes_and_primary_selects() {
        assert_eq!(press(2, Button::Middle), TabAction::Close(2));
        assert_eq!(press(2, Button::Primary), TabAction::Select(2));
    }
}
