//! sRGB to CIE Lab (D65) and the CIEDE2000 colour difference.

/// A colour in CIE L*a*b*.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Lab {
    pub l: f64,
    pub a: f64,
    pub b: f64,
}

pub fn lab([r, g, b]: [u8; 3]) -> Lab {
    let lin = |c: u8| {
        let c = c as f64 / 255.0;
        if c <= 0.04045 {
            c / 12.92
        } else {
            ((c + 0.055) / 1.055).powf(2.4)
        }
    };
    let (r, g, b) = (lin(r), lin(g), lin(b));
    let x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
    let y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
    let z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
    let f = |t: f64| {
        if t > 216.0 / 24389.0 {
            t.cbrt()
        } else {
            (24389.0 / 27.0 * t + 16.0) / 116.0
        }
    };
    let (fx, fy, fz) = (f(x), f(y), f(z));
    Lab {
        l: 116.0 * fy - 16.0,
        a: 500.0 * (fx - fy),
        b: 200.0 * (fy - fz),
    }
}

/// CIEDE2000 ΔE between two colours; about 1 is the smallest difference people notice side by side.
pub fn delta_e(c1: Lab, c2: Lab) -> f64 {
    use std::f64::consts::PI;
    let deg = |r: f64| r * 180.0 / PI;
    let rad = |d: f64| d * PI / 180.0;
    let c1ab = (c1.a * c1.a + c1.b * c1.b).sqrt();
    let c2ab = (c2.a * c2.a + c2.b * c2.b).sqrt();
    let cbar = (c1ab + c2ab) / 2.0;
    let g = 0.5 * (1.0 - (cbar.powi(7) / (cbar.powi(7) + 25f64.powi(7))).sqrt());
    let a1 = (1.0 + g) * c1.a;
    let a2 = (1.0 + g) * c2.a;
    let cp1 = (a1 * a1 + c1.b * c1.b).sqrt();
    let cp2 = (a2 * a2 + c2.b * c2.b).sqrt();
    let hue = |b: f64, a: f64| {
        if a == 0.0 && b == 0.0 {
            0.0
        } else {
            let h = deg(b.atan2(a));
            if h < 0.0 { h + 360.0 } else { h }
        }
    };
    let h1 = hue(c1.b, a1);
    let h2 = hue(c2.b, a2);
    let dl = c2.l - c1.l;
    let dc = cp2 - cp1;
    let dh = if cp1 * cp2 == 0.0 {
        0.0
    } else if (h2 - h1).abs() <= 180.0 {
        h2 - h1
    } else if h2 - h1 > 180.0 {
        h2 - h1 - 360.0
    } else {
        h2 - h1 + 360.0
    };
    let dhh = 2.0 * (cp1 * cp2).sqrt() * rad(dh / 2.0).sin();
    let lbar = (c1.l + c2.l) / 2.0;
    let cpbar = (cp1 + cp2) / 2.0;
    let hbar = if cp1 * cp2 == 0.0 {
        h1 + h2
    } else if (h1 - h2).abs() <= 180.0 {
        (h1 + h2) / 2.0
    } else if h1 + h2 < 360.0 {
        (h1 + h2 + 360.0) / 2.0
    } else {
        (h1 + h2 - 360.0) / 2.0
    };
    let t = 1.0 - 0.17 * rad(hbar - 30.0).cos()
        + 0.24 * rad(2.0 * hbar).cos()
        + 0.32 * rad(3.0 * hbar + 6.0).cos()
        - 0.20 * rad(4.0 * hbar - 63.0).cos();
    let dtheta = 30.0 * (-((hbar - 275.0) / 25.0).powi(2)).exp();
    let rc = 2.0 * (cpbar.powi(7) / (cpbar.powi(7) + 25f64.powi(7))).sqrt();
    let sl = 1.0 + 0.015 * (lbar - 50.0).powi(2) / (20.0 + (lbar - 50.0).powi(2)).sqrt();
    let sc = 1.0 + 0.045 * cpbar;
    let sh = 1.0 + 0.015 * cpbar * t;
    let rt = -rad(2.0 * dtheta).sin() * rc;
    ((dl / sl).powi(2) + (dc / sc).powi(2) + (dhh / sh).powi(2) + rt * (dc / sc) * (dhh / sh))
        .sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_pairs() {
        // Sharma, Wu & Dalal's test data, pair 1.
        let a = Lab {
            l: 50.0,
            a: 2.6772,
            b: -79.7751,
        };
        let b = Lab {
            l: 50.0,
            a: 0.0,
            b: -82.7485,
        };
        assert!((delta_e(a, b) - 2.0425).abs() < 1e-4);
        assert_eq!(delta_e(lab([30, 31, 34]), lab([30, 31, 34])), 0.0);
        // One level off on a saturated blue is well under the threshold.
        assert!(delta_e(lab([0x35, 0x74, 0xF0]), lab([0x34, 0x74, 0xF0])) < 1.0);
    }
}
