//! Which rows of a long, fixed-row-height list exist at all: only those in or near the viewport are built.

use std::ops::Range;

/// The row indexes to build for a viewport `viewport` tall scrolled `offset` down, `overscan` extra rows each side.
pub fn visible_range(
    offset: f64,
    viewport: f64,
    row_height: f64,
    rows: usize,
    overscan: usize,
) -> Range<usize> {
    if rows == 0 || row_height <= 0.0 || viewport <= 0.0 {
        return 0..0;
    }
    let first = (offset.max(0.0) / row_height).floor() as usize;
    let end = ((offset.max(0.0) + viewport) / row_height).ceil() as usize;
    first.saturating_sub(overscan).min(rows)..(end + overscan).min(rows)
}

/// The furthest the content can scroll.
pub fn max_offset(viewport: f64, row_height: f64, rows: usize) -> f64 {
    (row_height * rows as f64 - viewport).max(0.0)
}

/// The smallest scroll that brings row `index` fully into view from `offset`, or `offset` if it already is.
pub fn reveal(index: usize, offset: f64, viewport: f64, row_height: f64, rows: usize) -> f64 {
    let top = index as f64 * row_height;
    let bottom = top + row_height;
    let wanted = if top < offset {
        top
    } else if bottom > offset + viewport {
        bottom - viewport
    } else {
        offset
    };
    wanted.clamp(0.0, max_offset(viewport, row_height, rows))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_the_rows_in_view_plus_overscan() {
        assert_eq!(visible_range(0.0, 100.0, 20.0, 1000, 0), 0..5);
        assert_eq!(visible_range(50.0, 100.0, 20.0, 1000, 0), 2..8);
        assert_eq!(visible_range(50.0, 100.0, 20.0, 1000, 2), 0..10);
    }

    #[test]
    fn never_runs_past_the_rows() {
        assert_eq!(visible_range(900.0, 400.0, 20.0, 50, 3), 42..50);
        assert_eq!(visible_range(0.0, 100.0, 20.0, 0, 3), 0..0);
        assert_eq!(visible_range(0.0, 100.0, 20.0, 3, 0), 0..3);
    }

    #[test]
    fn reveal_scrolls_the_least() {
        assert_eq!(reveal(2, 0.0, 100.0, 20.0, 50), 0.0);
        assert_eq!(reveal(5, 0.0, 100.0, 20.0, 50), 20.0);
        assert_eq!(reveal(1, 60.0, 100.0, 20.0, 50), 20.0);
        assert_eq!(reveal(49, 0.0, 100.0, 20.0, 50), 900.0);
    }
}
