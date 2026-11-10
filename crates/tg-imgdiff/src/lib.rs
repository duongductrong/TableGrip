//! Compares a gallery capture with the Kotlin app's reference render of the same specimen.
//!
//! - **Plain pixels** (outside every region) must match: a pixel differs when its CIEDE2000 ΔE exceeds
//!   [Thresholds::delta_e], and the specimen fails when more than [Thresholds::max_diff_percent] of them differ.
//! - **Text regions** (`text:` tags in the reference's JSON) are compared by ink, because Skia and the platform text
//!   renderer never rasterise glyphs identically: the ink's bounding box must sit within [Thresholds::text_px] of the
//!   reference's on every side, and its colour (the mean of the most strongly inked pixels) within
//!   [Thresholds::text_delta_e].
//! - **Icon regions** use the plain rule with [Thresholds::icon_delta_e] and [Thresholds::icon_max_diff_percent].
//! - **Skip regions** are ignored.
//!
//! Both images must be sRGB (the gallery converts its captures) and the same size.

pub mod color;

use std::path::Path;

use image::{Rgb, RgbImage};
use serde::Deserialize;

use color::{Lab, delta_e, lab};

#[derive(Clone, Copy, Debug)]
pub struct Thresholds {
    pub delta_e: f64,
    pub max_diff_percent: f64,
    pub text_px: i64,
    pub text_delta_e: f64,
    pub icon_delta_e: f64,
    pub icon_max_diff_percent: f64,
}

impl Default for Thresholds {
    fn default() -> Self {
        Thresholds {
            delta_e: 2.0,
            max_diff_percent: 0.5,
            text_px: 3,
            text_delta_e: 6.0,
            icon_delta_e: 5.0,
            icon_max_diff_percent: 5.0,
        }
    }
}

/// `reference/<theme>/<name>.json`, written by the Kotlin `GoldenTest`.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Meta {
    #[serde(default)]
    pub theme: String,
    #[serde(default)]
    pub regions: Vec<Region>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Region {
    pub tag: String,
    /// `text`, `icon` or `skip`.
    pub kind: String,
    /// x, y, width, height in pixels of the render.
    pub rect: [i64; 4],
}

/// A rectangle in pixels: left, top, right and bottom (exclusive).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rect {
    pub l: i64,
    pub t: i64,
    pub r: i64,
    pub b: i64,
}

impl Rect {
    fn of(rect: [i64; 4]) -> Rect {
        Rect {
            l: rect[0],
            t: rect[1],
            r: rect[0] + rect[2],
            b: rect[1] + rect[3],
        }
    }

    fn grow(self, by: i64, w: u32, h: u32) -> Rect {
        Rect {
            l: (self.l - by).max(0),
            t: (self.t - by).max(0),
            r: (self.r + by).min(w as i64),
            b: (self.b + by).min(h as i64),
        }
    }

    fn contains(&self, x: i64, y: i64) -> bool {
        x >= self.l && x < self.r && y >= self.t && y < self.b
    }
}

#[derive(Clone, Debug)]
pub struct RegionResult {
    pub tag: String,
    pub kind: String,
    pub passed: bool,
    pub detail: String,
}

#[derive(Clone, Debug)]
pub struct Comparison {
    pub passed: bool,
    pub size_mismatch: Option<String>,
    pub plain_pixels: u64,
    pub diff_pixels: u64,
    pub diff_percent: f64,
    pub max_delta_e: f64,
    pub regions: Vec<RegionResult>,
    /// The reference greyed out, differing plain pixels in red, region outlines on top.
    pub diff_image: RgbImage,
}

pub fn load(path: &Path) -> Result<RgbImage, String> {
    image::open(path)
        .map(|i| i.into_rgb8())
        .map_err(|e| format!("{}: {e}", path.display()))
}

pub fn compare(
    reference: &RgbImage,
    actual: &RgbImage,
    meta: &Meta,
    th: &Thresholds,
) -> Comparison {
    let (w, h) = reference.dimensions();
    let mut diff_image = RgbImage::from_fn(w, h, |x, y| {
        let p = reference.get_pixel(x, y).0;
        let g = ((p[0] as u32 + p[1] as u32 + p[2] as u32) / 3 / 3 + 170) as u8;
        Rgb([g, g, g])
    });
    if actual.dimensions() != (w, h) {
        return Comparison {
            passed: false,
            size_mismatch: Some(format!(
                "reference is {w}×{h}, capture is {}×{}",
                actual.width(),
                actual.height()
            )),
            plain_pixels: 0,
            diff_pixels: 0,
            diff_percent: 100.0,
            max_delta_e: f64::INFINITY,
            regions: Vec::new(),
            diff_image,
        };
    }

    let pixel = |img: &RgbImage, x: i64, y: i64| img.get_pixel(x as u32, y as u32).0;
    // Text may spill a little past the Kotlin node's bounds; keep that margin out of the plain comparison.
    let excluded: Vec<Rect> = meta
        .regions
        .iter()
        .map(|r| {
            let grow = if r.kind == "text" { th.text_px } else { 0 };
            Rect::of(r.rect).grow(grow, w, h)
        })
        .collect();

    let (mut plain, mut differ, mut max_de) = (0u64, 0u64, 0f64);
    for y in 0..h as i64 {
        for x in 0..w as i64 {
            if excluded.iter().any(|r| r.contains(x, y)) {
                continue;
            }
            plain += 1;
            let de = delta_e(lab(pixel(reference, x, y)), lab(pixel(actual, x, y)));
            max_de = max_de.max(de);
            if de > th.delta_e {
                differ += 1;
                diff_image.put_pixel(x as u32, y as u32, Rgb([230, 30, 30]));
            }
        }
    }
    let diff_percent = if plain == 0 {
        0.0
    } else {
        differ as f64 * 100.0 / plain as f64
    };

    let mut regions = Vec::new();
    for region in &meta.regions {
        let rect = Rect::of(region.rect);
        let result = match region.kind.as_str() {
            "text" => text_region(reference, actual, rect.grow(th.text_px, w, h), th),
            "icon" => icon_region(reference, actual, rect, th),
            _ => (true, "skipped".to_owned()),
        };
        let outline = match region.kind.as_str() {
            "text" if result.0 => Rgb([60, 120, 230]),
            "icon" if result.0 => Rgb([40, 170, 90]),
            _ if !result.0 => Rgb([230, 30, 30]),
            _ => Rgb([150, 150, 150]),
        };
        draw_rect(&mut diff_image, rect, outline);
        regions.push(RegionResult {
            tag: region.tag.clone(),
            kind: region.kind.clone(),
            passed: result.0,
            detail: result.1,
        });
    }

    Comparison {
        passed: diff_percent <= th.max_diff_percent && regions.iter().all(|r| r.passed),
        size_mismatch: None,
        plain_pixels: plain,
        diff_pixels: differ,
        diff_percent,
        max_delta_e: max_de,
        regions,
        diff_image,
    }
}

/// The ink in [rect]: its bounding box and the mean colour of its most strongly inked tenth.
fn ink(img: &RgbImage, rect: Rect) -> Option<(Rect, Lab)> {
    // The background is the most common colour on the rectangle's edge.
    let mut counts = std::collections::HashMap::<[u8; 3], u32>::new();
    for x in rect.l..rect.r {
        for y in [rect.t, rect.b - 1] {
            *counts
                .entry(img.get_pixel(x as u32, y as u32).0)
                .or_default() += 1;
        }
    }
    for y in rect.t..rect.b {
        for x in [rect.l, rect.r - 1] {
            *counts
                .entry(img.get_pixel(x as u32, y as u32).0)
                .or_default() += 1;
        }
    }
    let bg = lab(counts.into_iter().max_by_key(|(_, n)| *n)?.0);
    let mut inked = Vec::new();
    let mut bbox: Option<Rect> = None;
    for y in rect.t..rect.b {
        for x in rect.l..rect.r {
            let c = lab(img.get_pixel(x as u32, y as u32).0);
            let de = delta_e(bg, c);
            if de > 12.0 {
                inked.push((de, c));
                let b = bbox.get_or_insert(Rect {
                    l: x,
                    t: y,
                    r: x + 1,
                    b: y + 1,
                });
                b.l = b.l.min(x);
                b.t = b.t.min(y);
                b.r = b.r.max(x + 1);
                b.b = b.b.max(y + 1);
            }
        }
    }
    let bbox = bbox?;
    inked.sort_by(|a, b| b.0.total_cmp(&a.0));
    let top = &inked[..inked.len().div_ceil(10)];
    let n = top.len() as f64;
    let mean = Lab {
        l: top.iter().map(|(_, c)| c.l).sum::<f64>() / n,
        a: top.iter().map(|(_, c)| c.a).sum::<f64>() / n,
        b: top.iter().map(|(_, c)| c.b).sum::<f64>() / n,
    };
    Some((bbox, mean))
}

fn text_region(
    reference: &RgbImage,
    actual: &RgbImage,
    rect: Rect,
    th: &Thresholds,
) -> (bool, String) {
    match (ink(reference, rect), ink(actual, rect)) {
        (None, None) => (true, "no ink in either".to_owned()),
        (Some(_), None) => (false, "no ink in the capture".to_owned()),
        (None, Some(_)) => (false, "ink in the capture only".to_owned()),
        (Some((rb, rc)), Some((ab, ac))) => {
            let d = [ab.l - rb.l, ab.t - rb.t, ab.r - rb.r, ab.b - rb.b];
            let de = delta_e(rc, ac);
            let ok = d.iter().all(|v| v.abs() <= th.text_px) && de <= th.text_delta_e;
            (
                ok,
                format!(
                    "ink box Δ left {} top {} right {} bottom {} px; ink ΔE {de:.1}",
                    d[0], d[1], d[2], d[3]
                ),
            )
        }
    }
}

fn icon_region(
    reference: &RgbImage,
    actual: &RgbImage,
    rect: Rect,
    th: &Thresholds,
) -> (bool, String) {
    let (mut total, mut differ) = (0u64, 0u64);
    for y in rect.t..rect.b {
        for x in rect.l..rect.r {
            total += 1;
            let a = lab(reference.get_pixel(x as u32, y as u32).0);
            let b = lab(actual.get_pixel(x as u32, y as u32).0);
            differ += (delta_e(a, b) > th.icon_delta_e) as u64;
        }
    }
    let pct = differ as f64 * 100.0 / total.max(1) as f64;
    (
        pct <= th.icon_max_diff_percent,
        format!("{pct:.2}% of pixels differ"),
    )
}

fn draw_rect(img: &mut RgbImage, rect: Rect, color: Rgb<u8>) {
    let (w, h) = img.dimensions();
    let r = rect.grow(0, w, h);
    for x in r.l..r.r {
        for y in [r.t, r.b - 1] {
            if y >= 0 && y < h as i64 {
                img.put_pixel(x as u32, y as u32, color);
            }
        }
    }
    for y in r.t..r.b {
        for x in [r.l, r.r - 1] {
            if x >= 0 && x < w as i64 {
                img.put_pixel(x as u32, y as u32, color);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn canvas(color: [u8; 3]) -> RgbImage {
        RgbImage::from_pixel(40, 20, Rgb(color))
    }

    #[test]
    fn identical_images_pass() {
        let a = canvas([30, 31, 34]);
        let c = compare(&a, &a, &Meta::default(), &Thresholds::default());
        assert!(c.passed);
        assert_eq!(c.diff_pixels, 0);
    }

    #[test]
    fn one_level_off_passes_and_a_wrong_colour_fails() {
        let a = canvas([0x35, 0x74, 0xF0]);
        assert!(
            compare(
                &a,
                &canvas([0x34, 0x74, 0xF0]),
                &Meta::default(),
                &Thresholds::default()
            )
            .passed
        );
        assert!(
            !compare(
                &a,
                &canvas([0x4D, 0x78, 0xCC]),
                &Meta::default(),
                &Thresholds::default()
            )
            .passed
        );
    }

    #[test]
    fn text_is_compared_by_ink() {
        let mut a = canvas([30, 31, 34]);
        let mut b = canvas([30, 31, 34]);
        for x in 10..20 {
            for y in 5..12 {
                a.put_pixel(x, y, Rgb([223, 225, 229]));
            }
        }
        // Same ink, one pixel to the right and with an anti-aliased edge: passes.
        for x in 11..21 {
            for y in 5..12 {
                b.put_pixel(x, y, Rgb([223, 225, 229]));
            }
        }
        b.put_pixel(10, 8, Rgb([120, 120, 125]));
        let meta = Meta {
            theme: String::new(),
            regions: vec![Region {
                tag: "text:x".into(),
                kind: "text".into(),
                rect: [8, 3, 16, 12],
            }],
        };
        assert!(compare(&a, &b, &meta, &Thresholds::default()).passed);
        // Ink in the wrong colour fails.
        let mut c = a.clone();
        for x in 10..20 {
            for y in 5..12 {
                c.put_pixel(x, y, Rgb([219, 92, 92]));
            }
        }
        assert!(!compare(&a, &c, &meta, &Thresholds::default()).passed);
    }

    #[test]
    fn size_mismatch_fails() {
        let c = compare(
            &canvas([0, 0, 0]),
            &RgbImage::new(10, 10),
            &Meta::default(),
            &Thresholds::default(),
        );
        assert!(!c.passed && c.size_mismatch.is_some());
    }
}
