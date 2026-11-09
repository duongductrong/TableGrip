//! Colours as the theme files and the Kotlin app treat them: 8-bit sRGB with alpha, parsed from the two hex notations
//! the app accepts, and mixed the way Compose's `lerp` mixes them (in Oklab), so resolved tokens match to the bit.

use std::fmt;

/// An sRGB colour with straight (not premultiplied) alpha, 8 bits per channel.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Rgba {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl Rgba {
    pub const WHITE: Rgba = Rgba::rgb(0xFFFFFF);
    pub const BLACK: Rgba = Rgba::rgb(0x000000);

    /// An opaque colour from `0xRRGGBB`.
    pub const fn rgb(hex: u32) -> Rgba {
        Rgba {
            r: (hex >> 16) as u8,
            g: (hex >> 8) as u8,
            b: hex as u8,
            a: 0xFF,
        }
    }

    /// A colour from `0xAARRGGBB`, the way Compose writes colour literals.
    pub const fn argb(hex: u32) -> Rgba {
        Rgba {
            a: (hex >> 24) as u8,
            r: (hex >> 16) as u8,
            g: (hex >> 8) as u8,
            b: hex as u8,
        }
    }

    /// This colour with alpha [alpha] (0–1), rounded as Compose's `Color.copy(alpha = …)` does.
    pub fn with_alpha(self, alpha: f32) -> Rgba {
        Rgba {
            a: unit_to_u8(alpha),
            ..self
        }
    }

    /// `#RRGGBB`, or `#RRGGBBAA` when not opaque: the theme files' own notation.
    pub fn to_hex(self) -> String {
        if self.a == 0xFF {
            format!("#{:02X}{:02X}{:02X}", self.r, self.g, self.b)
        } else {
            format!("#{:02X}{:02X}{:02X}{:02X}", self.r, self.g, self.b, self.a)
        }
    }

    /// Relative luminance (0–1) of the colour, ignoring alpha, as Compose's `Color.luminance()` computes it.
    pub fn luminance(self) -> f32 {
        let lin = |c: u8| srgb_eotf(c as f32 / 255.0);
        (0.2126 * lin(self.r) + 0.7152 * lin(self.g) + 0.0722 * lin(self.b)).clamp(0.0, 1.0)
    }
}

impl fmt::Debug for Rgba {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_hex())
    }
}

/// `#RRGGBB` or `#RRGGBBAA` (alpha last, as VS Code and Zed write it), also `#RGB` and `#RGBA`; None when it isn't
/// one. Theme files use this notation. Note it differs from [parse_hex_color], which reads eight digits as
/// `#AARRGGBB`.
pub fn parse_theme_color(text: &str) -> Option<Rgba> {
    let hex = text.trim();
    let hex = hex.strip_prefix('#').unwrap_or(hex);
    let hex: String = if hex.len() == 3 || hex.len() == 4 {
        hex.chars().flat_map(|c| [c, c]).collect()
    } else {
        hex.to_owned()
    };
    let value = parse_hex_digits(&hex)?;
    match hex.len() {
        6 => Some(Rgba::rgb(value as u32)),
        8 => Some(Rgba {
            r: (value >> 24) as u8,
            g: (value >> 16) as u8,
            b: (value >> 8) as u8,
            a: value as u8,
        }),
        _ => None,
    }
}

/// `#RRGGBB` or `#AARRGGBB` (the `#` optional), the notation of the settings' `accentColor`; None when it isn't one.
pub fn parse_hex_color(text: &str) -> Option<Rgba> {
    let hex = text.trim();
    let hex = hex.strip_prefix('#').unwrap_or(hex);
    let value = parse_hex_digits(hex)?;
    match hex.len() {
        6 => Some(Rgba::rgb(value as u32)),
        8 => Some(Rgba::argb(value as u32)),
        _ => None,
    }
}

fn parse_hex_digits(hex: &str) -> Option<u64> {
    if hex.is_empty() || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    u64::from_str_radix(hex, 16).ok()
}

/// Black or white, whichever reads better on [background].
pub fn readable_on(background: Rgba) -> Rgba {
    let l = background.luminance();
    let on_white = 1.05 / (l + 0.05);
    let on_black = (l + 0.05) / 0.05;
    if on_white >= on_black {
        Rgba::WHITE
    } else {
        Rgba::BLACK
    }
}

/// The colour [fraction] of the way from [start] to [stop], interpolated in Oklab as Compose's `lerp` does.
///
/// This follows Compose step by step rather than textbook Oklab, because every detail decides the last bit of some
/// resolved token: Compose reaches Oklab through D50 XYZ (its `Connector` adapts sRGB to D50 with Bradford, and its
/// Oklab M1 folds the D50 to D65 adaptation back in), takes cube roots with `fastCbrt`, stores the Oklab colours as
/// half floats with a 10-bit alpha, and computes its matrices in f32.
pub fn lerp(start: Rgba, stop: Rgba, fraction: f32) -> Rgba {
    let s = to_oklab(start);
    let e = to_oklab(stop);
    let t = fraction.clamp(0.0, 1.0);
    // Compose's `lerp(Float, Float, Float)`, then `UncheckedColor`: halves without clamping, alpha clamped.
    let mix = |a: f32, b: f32| (1.0 - t) * a + t * b;
    let l = through_half(mix(s[0], e[0]));
    let a = through_half(mix(s[1], e[1]));
    let b = through_half(mix(s[2], e[2]));
    let alpha = alpha10(mix(s[3], e[3]));
    from_oklab([l, a, b, alpha])
}

/// [color] as `color.convert(ColorSpaces.Oklab)` holds it: half-float L, a, b and a 10-bit alpha.
fn to_oklab(color: Rgba) -> [f32; 4] {
    let m = matrices();
    // `Connector.transformToColor`: sRGB adapted to D50 gives XYZ, then `Oklab.xyzaToColor`.
    let rgb = [color.r, color.g, color.b].map(|c| srgb_eotf_f64(c as f32 / 255.0));
    let xyz = mul3x3_float3(&m.srgb_to_xyz_d50, rgb);
    let lms = mul3x3_float3(&m.m1, xyz).map(fast_cbrt);
    let lab = mul3x3_float3(&m.m2, lms);
    // `Color(…, Oklab)` clamps to the space's range before packing.
    [
        through_half(lab[0].clamp(0.0, 1.0)),
        through_half(lab[1].clamp(-0.5, 0.5)),
        through_half(lab[2].clamp(-0.5, 0.5)),
        alpha10(color.a as f32 / 255.0),
    ]
}

/// `convert(ColorSpaces.Srgb)` of an Oklab colour: `Oklab.toXy`/`toZ`, then sRGB adapted to D50's `xyzaToColor`.
fn from_oklab([ok_l, ok_a, ok_b, alpha]: [f32; 4]) -> Rgba {
    let m = matrices();
    let lab = [
        ok_l.clamp(0.0, 1.0),
        ok_a.clamp(-0.5, 0.5),
        ok_b.clamp(-0.5, 0.5),
    ];
    let lms = mul3x3_float3(&m.inverse_m2, lab).map(|v| v * v * v);
    let xyz = mul3x3_float3(&m.inverse_m1, lms);
    let rgb = mul3x3_float3(&m.xyz_d50_to_srgb, xyz).map(srgb_oetf_f64);
    Rgba {
        r: unit_to_u8(rgb[0]),
        g: unit_to_u8(rgb[1]),
        b: unit_to_u8(rgb[2]),
        a: unit_to_u8(alpha),
    }
}

/// A 0–1 channel to 8 bits, clamped and rounded half up as Compose packs sRGB colours.
fn unit_to_u8(v: f32) -> u8 {
    (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u8
}

/// Compose's 10-bit alpha for colours outside sRGB.
fn alpha10(alpha: f32) -> f32 {
    ((alpha.clamp(0.0, 1.0) * 1023.0 + 0.5) as i32) as f32 / 1023.0
}

/// sRGB decoding in f32, for [Rgba::luminance].
fn srgb_eotf(v: f32) -> f32 {
    if v >= 0.04045 {
        ((v + 0.055) / 1.055).powf(2.4)
    } else {
        v / 12.92
    }
}

/// Compose's sRGB transfer parameters: `TransferParameters(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)`.
const SRGB_G: f64 = 2.4;
const SRGB_A: f64 = 1.0 / 1.055;
const SRGB_B: f64 = 0.055 / 1.055;
const SRGB_C: f64 = 1.0 / 12.92;
const SRGB_D: f64 = 0.04045;

/// `Rgb.eotfFunc` for sRGB (Compose's `response`, in f64 on a clamped input), rounded to f32 as Compose does.
fn srgb_eotf_f64(v: f32) -> f32 {
    let x = (v as f64).clamp(0.0, 1.0);
    let y = if x >= SRGB_D {
        (SRGB_A * x + SRGB_B).powf(SRGB_G)
    } else {
        SRGB_C * x
    };
    y as f32
}

/// `Rgb.oetfFunc` for sRGB (Compose's `rcpResponse`, in f64, clamped after), rounded to f32 as Compose does.
fn srgb_oetf_f64(v: f32) -> f32 {
    let x = v as f64;
    let y = if x >= SRGB_D * SRGB_C {
        (x.powf(1.0 / SRGB_G) - SRGB_B) / SRGB_A
    } else {
        x / SRGB_C
    };
    y.clamp(0.0, 1.0) as f32
}

/// The f32 matrices Compose builds for the sRGB ⇄ Oklab connectors, column-major like Compose's `FloatArray`s.
struct Matrices {
    /// sRGB adapted to D50 (`ColorSpaces.Srgb.adapt(Illuminant.D50)`): linear sRGB to XYZ D50, and back.
    srgb_to_xyz_d50: [f32; 9],
    xyz_d50_to_srgb: [f32; 9],
    /// Oklab's M1 (XYZ D50 to LMS, with the D50 to D65 adaptation folded in), M2, and their inverses.
    m1: [f32; 9],
    m2: [f32; 9],
    inverse_m1: [f32; 9],
    inverse_m2: [f32; 9],
}

#[allow(clippy::excessive_precision)]
fn matrices() -> &'static Matrices {
    static MATRICES: std::sync::LazyLock<Matrices> = std::sync::LazyLock::new(|| {
        // Compose's `Illuminant` white points and `Adaptation.Bradford`.
        let d50 = (0.345_67_f32, 0.358_50_f32);
        let d65 = (0.312_71_f32, 0.329_02_f32);
        let bradford = [
            0.8951, -0.7502, 0.0389, 0.2664, 1.7135, -0.0685, -0.1614, 0.0367, 1.0296,
        ];
        // `ColorSpaces.Srgb`: its primaries and D65 give the XYZ matrix, which `adapt` moves to D50.
        let srgb_primaries = [0.640, 0.330, 0.300, 0.600, 0.150, 0.060];
        let srgb_to_xyz = compute_xyz_matrix(srgb_primaries, d65);
        let to_d50 = chromatic_adaptation(&bradford, white_xyz(d65), white_xyz(d50));
        let srgb_to_xyz_d50 = mul3x3(&to_d50, &srgb_to_xyz);
        // `Oklab.M1` and `Oklab.M2`, Compose's literals as written (f32 rounds them).
        let m1_literal = [
            0.8189330101_f32,
            0.0329845436,
            0.0482003018,
            0.3618667424,
            0.9293118715,
            0.2643662691,
            -0.1288597137,
            0.0361456387,
            0.6338517070,
        ];
        let m1 = mul3x3(
            &m1_literal,
            &chromatic_adaptation(&bradford, white_xyz(d50), white_xyz(d65)),
        );
        let m2 = [
            0.2104542553_f32,
            1.9779984951,
            0.0259040371,
            0.7936177850,
            -2.4285922050,
            0.7827717662,
            -0.0040720468,
            0.4505937099,
            -0.8086757660,
        ];
        Matrices {
            srgb_to_xyz_d50,
            xyz_d50_to_srgb: inverse3x3(&srgb_to_xyz_d50),
            m1,
            m2,
            inverse_m1: inverse3x3(&m1),
            inverse_m2: inverse3x3(&m2),
        }
    });
    &MATRICES
}

/// `WhitePoint.toXyz`.
fn white_xyz((x, y): (f32, f32)) -> [f32; 3] {
    [x / y, 1.0, (1.0 - x - y) / y]
}

/// `Rgb.computeXYZMatrix`: the linear RGB to XYZ matrix from xy primaries and a white point.
fn compute_xyz_matrix(p: [f32; 6], (wx, wy): (f32, f32)) -> [f32; 9] {
    let [rx, ry, gx, gy, bx, by] = p;
    let one_rx_ry = (1.0 - rx) / ry;
    let one_gx_gy = (1.0 - gx) / gy;
    let one_bx_by = (1.0 - bx) / by;
    let one_wx_wy = (1.0 - wx) / wy;
    let rx_ry = rx / ry;
    let gx_gy = gx / gy;
    let bx_by = bx / by;
    let wx_wy = wx / wy;
    let by_numerator =
        (one_wx_wy - one_rx_ry) * (gx_gy - rx_ry) - (wx_wy - rx_ry) * (one_gx_gy - one_rx_ry);
    let by_denominator =
        (one_bx_by - one_rx_ry) * (gx_gy - rx_ry) - (bx_by - rx_ry) * (one_gx_gy - one_rx_ry);
    let b_y = by_numerator / by_denominator;
    let g_y = (wx_wy - rx_ry - b_y * (bx_by - rx_ry)) / (gx_gy - rx_ry);
    let r_y = 1.0 - g_y - b_y;
    let r_y_ry = r_y / ry;
    let g_y_gy = g_y / gy;
    let b_y_by = b_y / by;
    [
        r_y_ry * rx,
        r_y,
        r_y_ry * (1.0 - rx - ry),
        g_y_gy * gx,
        g_y,
        g_y_gy * (1.0 - gx - gy),
        b_y_by * bx,
        b_y,
        b_y_by * (1.0 - bx - by),
    ]
}

/// Compose's `chromaticAdaptation`: a von Kries style transform in the cone space of [matrix].
fn chromatic_adaptation(matrix: &[f32; 9], src_white: [f32; 3], dst_white: [f32; 3]) -> [f32; 9] {
    let src = mul3x3_float3(matrix, src_white);
    let dst = mul3x3_float3(matrix, dst_white);
    let lms = [dst[0] / src[0], dst[1] / src[1], dst[2] / src[2]];
    mul3x3(&inverse3x3(matrix), &mul3x3_diag(lms, matrix))
}

/// Compose's `inverse3x3` (cofactors over the determinant, in f32).
fn inverse3x3(m: &[f32; 9]) -> [f32; 9] {
    let (a, b, c) = (m[0], m[3], m[6]);
    let (d, e, f) = (m[1], m[4], m[7]);
    let (g, h, i) = (m[2], m[5], m[8]);
    let x_a = e * i - f * h;
    let x_b = f * g - d * i;
    let x_c = d * h - e * g;
    let det = a * x_a + b * x_b + c * x_c;
    [
        x_a / det,
        x_b / det,
        x_c / det,
        (c * h - b * i) / det,
        (a * i - c * g) / det,
        (b * g - a * h) / det,
        (b * f - c * e) / det,
        (c * d - a * f) / det,
        (a * e - b * d) / det,
    ]
}

/// Compose's `mul3x3` for column-major matrices.
fn mul3x3(l: &[f32; 9], r: &[f32; 9]) -> [f32; 9] {
    let mut out = [0.0; 9];
    for col in 0..3 {
        for row in 0..3 {
            out[col * 3 + row] =
                l[row] * r[col * 3] + l[3 + row] * r[col * 3 + 1] + l[6 + row] * r[col * 3 + 2];
        }
    }
    out
}

/// Compose's `mul3x3Diag`: diag([d]) × [m].
fn mul3x3_diag(d: [f32; 3], m: &[f32; 9]) -> [f32; 9] {
    std::array::from_fn(|i| d[i % 3] * m[i])
}

/// Compose's `mul3x3Float3`: [m] × [v].
fn mul3x3_float3(m: &[f32; 9], v: [f32; 3]) -> [f32; 3] {
    std::array::from_fn(|row| m[row] * v[0] + m[3 + row] * v[1] + m[6 + row] * v[2])
}

/// Compose's `fastCbrt`: a bit-trick estimate refined by two Newton steps, close to but not exactly `cbrt`.
fn fast_cbrt(x: f32) -> f32 {
    // The bits widened like Kotlin's `toLong()` (sign-extended), then masked to 33 bits.
    let v = (x.to_bits() as i32 as i64) & 0x1_ffff_ffff;
    let mut estimate = f32::from_bits(0x2a51_0554_i32.wrapping_add((v / 3) as i32) as u32);
    estimate -= (estimate - x / (estimate * estimate)) * (1.0 / 3.0);
    estimate -= (estimate - x / (estimate * estimate)) * (1.0 / 3.0);
    estimate
}

/// [v] through Compose's `floatToHalf` and `halfToFloat`, the way a non-sRGB `Color` stores a component.
fn through_half(v: f32) -> f32 {
    half_to_float(float_to_half(v))
}

/// Compose's `floatToHalf`. Note it rounds a tie away from zero, not to even.
fn float_to_half(f: f32) -> u16 {
    let bits = f.to_bits();
    let s = bits >> 31;
    let mut e = ((bits >> 23) & 0xff) as i32;
    let mut m = (bits & 0x7f_ffff) as i32;
    let mut out_e = 0;
    let mut out_m = 0;
    if e == 0xff {
        // Infinite or NaN.
        out_e = 0x1f;
        out_m = if m != 0 { 0x200 } else { 0 };
    } else {
        e = e - 127 + 15;
        if e >= 0x1f {
            // Overflow, encoded as Compose does.
            out_e = 0x31;
        } else if e <= 0 {
            // Underflow: flush to zero, or a denormal half.
            if e >= -10 {
                m = (m | 0x80_0000) >> (1 - e);
                if m & 0x1000 != 0 {
                    m += 0x2000;
                }
                out_m = m >> 13;
            }
        } else {
            out_e = e;
            out_m = m >> 13;
            if m & 0x1000 != 0 {
                let out = ((out_e << 10) | out_m) + 1;
                return (out as u32 | (s << 15)) as u16;
            }
        }
    }
    ((s << 15) | ((out_e as u32) << 10) | out_m as u32) as u16
}

/// Compose's `halfToFloat`.
fn half_to_float(h: u16) -> f32 {
    let bits = h as u32;
    let s = bits & 0x8000;
    let e = (bits >> 10) & 0x1f;
    let m = bits & 0x3ff;
    let mut out_e = 0;
    let mut out_m = 0;
    if e == 0 {
        if m != 0 {
            // A denormal half, normalised through a magic float.
            let magic = 126u32 << 23;
            let o = f32::from_bits(magic + m) - f32::from_bits(magic);
            return if s == 0 { o } else { -o };
        }
    } else {
        out_m = m << 13;
        if e == 0x1f {
            out_e = 0xff;
            if out_m != 0 {
                out_m |= 0x40_0000;
            }
        } else {
            out_e = e + 127 - 15;
        }
    }
    f32::from_bits((s << 16) | (out_e << 23) | out_m)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_both_notations() {
        assert_eq!(parse_theme_color("#FFFFFF14"), Some(Rgba::argb(0x14FFFFFF)));
        assert_eq!(parse_theme_color("#0A8"), Some(Rgba::rgb(0x00AA88)));
        assert_eq!(parse_theme_color("#FFFE"), Some(Rgba::argb(0xEEFFFFFF)));
        assert_eq!(parse_theme_color("blue"), None);
        assert_eq!(parse_hex_color("#80FF0000"), Some(Rgba::argb(0x80FF0000)));
        assert_eq!(parse_hex_color("3574F0"), Some(Rgba::rgb(0x3574F0)));
    }

    #[test]
    fn halves_round_like_compose() {
        assert_eq!(through_half(1.0), 1.0);
        // Ties round away from zero in Compose's `floatToHalf`, unlike IEEE's ties to even.
        assert_eq!(through_half(1.0 + 1.0 / 2048.0), 1.0 + 1.0 / 1024.0);
        assert_eq!(through_half(-(1.0 + 1.0 / 2048.0)), -(1.0 + 1.0 / 1024.0));
        assert_eq!(through_half(0.1), 0.099_975_586);
        assert_eq!(through_half(1.0e-5), 1.001_358e-5);
    }

    #[test]
    fn fast_cbrt_is_close_to_cbrt() {
        for x in [1.0e-6_f32, 0.001, 0.18, 0.5, 1.0] {
            assert!((fast_cbrt(x) - x.cbrt()).abs() < 1.0e-5, "{x}");
        }
    }

    #[test]
    fn lerp_matches_compose() {
        // Values the Kotlin app produced, where textbook Oklab is one bit off.
        let dracula = lerp(Rgba::rgb(0x343746), Rgba::rgb(0x282A36), 0.4);
        assert_eq!(dracula, Rgba::rgb(0x2F323F));
        assert_eq!(
            lerp(Rgba::rgb(0x0078D4), Rgba::WHITE, 0.08),
            Rgba::rgb(0x2B83D9)
        );
    }
}
