//! The check mark, the indeterminate dash and the radio dot, painted as filled paths so they are crisp at any zoom.

use gpui::{Bounds, PathBuilder, Pixels, Point, Styled, Window, canvas, point, px};

use crate::theme::ActiveTheme;
use tg_theme::Rgba;

/// A line from [a] to [b] with round caps, as a polygon.
fn stadium(a: Point<Pixels>, b: Point<Pixels>, radius: Pixels) -> Vec<Point<Pixels>> {
    let (dx, dy) = (f32::from(b.x - a.x), f32::from(b.y - a.y));
    let angle = dy.atan2(dx);
    let r = f32::from(radius);
    let mut points = Vec::new();
    let steps = 12;
    // The cap around [b] sweeps from the left of the direction to its right, the one around [a] back again.
    for (centre, from) in [
        (b, angle - std::f32::consts::FRAC_PI_2),
        (a, angle + std::f32::consts::FRAC_PI_2),
    ] {
        for i in 0..=steps {
            let t = from + std::f32::consts::PI * i as f32 / steps as f32;
            points.push(point(
                centre.x + px(r * t.cos()),
                centre.y + px(r * t.sin()),
            ));
        }
    }
    points
}

fn fill_polygon(window: &mut Window, points: &[Point<Pixels>], color: gpui::Hsla) {
    let mut path = PathBuilder::fill();
    path.add_polygon(points, true);
    if let Ok(path) = path.build() {
        window.paint_path(path, color);
    }
}

/// A canvas element that paints [lines] (dp coordinates from the element's top left, [width] dp wide, round caps) in [color].
fn lines(
    t: &ActiveTheme,
    lines: &'static [&'static [(f32, f32)]],
    width: f32,
    color: Rgba,
) -> gpui::Canvas<()> {
    let zoom = t.zoom();
    let color = crate::theme::hsla(color);
    canvas(
        |_, _, _| (),
        move |bounds: Bounds<Pixels>, _, window, _| {
            let at = |(x, y): (f32, f32)| {
                point(
                    bounds.origin.x + px(x * zoom),
                    bounds.origin.y + px(y * zoom),
                )
            };
            for line in lines {
                for pair in line.windows(2) {
                    fill_polygon(
                        window,
                        &stadium(at(pair[0]), at(pair[1]), px(zoom * width / 2.0)),
                        color,
                    );
                }
            }
        },
    )
}

/// The check mark inside a 16dp box. Fitted to the Kotlin app's render: 2dp strokes through these points.
pub(crate) fn check(t: &ActiveTheme, color: Rgba) -> gpui::Canvas<()> {
    lines(t, &[&[(4.0, 8.5), (7.0, 11.5), (12.5, 5.0)]], 1.9, color)
}

/// The indeterminate dash inside a 16dp box: a rounded bar, which GPUI draws with exact anti-aliasing where its
/// paths are only multisampled. Positioned for a child of the box, which lays out inside the box's 1dp border.
pub(crate) fn dash(t: &ActiveTheme, color: Rgba) -> gpui::Div {
    gpui::div()
        .absolute()
        .left(t.dp(1.57))
        .top(t.dp(6.0))
        .w(t.dp(10.87))
        .h(t.dp(2.0))
        .rounded(t.dp(1.0))
        .bg(crate::theme::hsla(color))
}
