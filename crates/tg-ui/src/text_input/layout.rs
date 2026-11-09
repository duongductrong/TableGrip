//! The shaped text of a field and the geometry built on it: where an offset is drawn, which offset is under a point,
//! and the rectangles a selection covers. Offsets here are into the *display* text (bullets in a password field).

use gpui::{Bounds, Pixels, Point, WrappedLine, point, px, size};

/// One hard line of the display text, shaped and wrapped.
pub(crate) struct Line {
    /// Its first byte in the display text.
    pub start: usize,
    pub shaped: WrappedLine,
    /// The top of its first row, from the top of the first line.
    pub y: Pixels,
}

/// The display text laid out in rows `line_height` apart, the first row's top at y = 0.
pub(crate) struct TextLayout {
    pub lines: Vec<Line>,
    pub line_height: Pixels,
}

impl TextLayout {
    pub fn new(shaped: impl IntoIterator<Item = WrappedLine>, line_height: Pixels) -> Self {
        let mut lines = Vec::new();
        let (mut start, mut y) = (0, px(0.0));
        for shaped in shaped {
            let len = shaped.len();
            let rows = shaped.wrap_boundaries().len() + 1;
            lines.push(Line { start, shaped, y });
            start += len + 1;
            y += line_height * rows as f32;
        }
        TextLayout { lines, line_height }
    }

    /// The number of rows, counting wrapped ones.
    pub fn rows(&self) -> usize {
        self.lines
            .iter()
            .map(|l| l.shaped.wrap_boundaries().len() + 1)
            .sum::<usize>()
            .max(1)
    }

    pub fn height(&self) -> Pixels {
        self.line_height * self.rows() as f32
    }

    /// The widest row.
    pub fn width(&self) -> Pixels {
        self.lines
            .iter()
            .map(|l| l.shaped.width())
            .fold(px(0.0), Pixels::max)
    }

    /// The line holding `offset`: the last one starting at or before it.
    fn line_of(&self, offset: usize) -> Option<&Line> {
        self.lines.iter().rev().find(|l| l.start <= offset)
    }

    /// The top-left of the row position of `offset`: x where the caret is drawn, y the row's top.
    pub fn position(&self, offset: usize) -> Point<Pixels> {
        let Some(line) = self.line_of(offset) else {
            return point(px(0.0), px(0.0));
        };
        let local = (offset - line.start).min(line.shaped.len());
        let p = line
            .shaped
            .position_for_index(local, self.line_height)
            .unwrap_or_else(|| point(line.shaped.width(), px(0.0)));
        point(p.x, line.y + p.y)
    }

    /// The row `offset` is on, from 0.
    pub fn row(&self, offset: usize) -> usize {
        (self.position(offset).y / self.line_height).floor() as usize
    }

    /// The offset closest to `p`; points above or below the text go to its first or last row.
    pub fn offset_at(&self, p: Point<Pixels>) -> usize {
        let Some(first) = self.lines.first() else {
            return 0;
        };
        let line = self
            .lines
            .iter()
            .rev()
            .find(|l| l.y <= p.y)
            .unwrap_or(first);
        let height = line.shaped.size(self.line_height).height;
        let y = (p.y - line.y).clamp(px(0.0), height - px(0.01));
        let local = match line
            .shaped
            .closest_index_for_position(point(p.x, y), self.line_height)
        {
            Ok(i) | Err(i) => i,
        };
        line.start + local
    }

    /// The rectangles, one per row, that `range` covers, each `line_height` tall from its row's top.
    pub fn selection_rects(&self, range: std::ops::Range<usize>) -> Vec<Bounds<Pixels>> {
        let mut rects = Vec::new();
        if range.is_empty() {
            return rects;
        }
        for line in &self.lines {
            let end = line.start + line.shaped.len();
            let (s, e) = (range.start.max(line.start), range.end.min(end));
            if s > e || (s == e && range.end <= end) {
                continue;
            }
            let (a, b) = (self.position(s), self.position(e));
            let row_end = line.shaped.width();
            let mut y = a.y;
            let mut x = a.x;
            while y < b.y {
                rects.push(Bounds::new(
                    point(x, y),
                    size((row_end - x).max(px(0.0)), self.line_height),
                ));
                y += self.line_height;
                x = px(0.0);
            }
            if b.x > x {
                rects.push(Bounds::new(point(x, y), size(b.x - x, self.line_height)));
            }
        }
        rects
    }
}
