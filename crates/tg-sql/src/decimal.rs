//! Exact decimals as `java.math.BigDecimal` parses and prints them: what filters bind and literals show.

use std::cmp::Ordering;
use std::fmt;

/// A decimal number: `digits` × 10^-`scale`, as `BigDecimal` keeps it, so `1.50` and `1.5` are different values that
/// compare equal with [`Decimal::cmp_value`].
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Decimal {
    negative: bool,
    /// The unscaled value's digits, without leading zeros ("0" for zero).
    digits: String,
    scale: i64,
}

impl Decimal {
    /// Parses `BigDecimal`'s syntax: an optional sign, digits with an optional point, and an optional exponent. No
    /// spaces, no `_`; None for anything else.
    pub fn parse(text: &str) -> Option<Self> {
        let bytes = text.as_bytes();
        let mut i = 0;
        let mut negative = false;
        if let Some(&s) = bytes.first()
            && (s == b'+' || s == b'-')
        {
            negative = s == b'-';
            i = 1;
        }
        let mut digits = String::new();
        let mut scale: i64 = 0;
        let mut any = false;
        let mut point = false;
        while i < bytes.len() {
            match bytes[i] {
                b'0'..=b'9' => {
                    digits.push(bytes[i] as char);
                    any = true;
                    if point {
                        scale += 1;
                    }
                }
                b'.' if !point => point = true,
                _ => break,
            }
            i += 1;
        }
        if !any {
            return None;
        }
        if i < bytes.len() {
            if bytes[i] != b'e' && bytes[i] != b'E' {
                return None;
            }
            let exponent = &text[i + 1..];
            let unsigned = exponent.strip_prefix(['+', '-']).unwrap_or(exponent);
            if unsigned.is_empty() || !unsigned.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            let exponent: i64 = exponent
                .strip_prefix('+')
                .unwrap_or(exponent)
                .parse()
                .ok()?;
            scale = scale.checked_sub(exponent)?;
        }
        let digits = digits.trim_start_matches('0');
        let digits = if digits.is_empty() { "0" } else { digits };
        Some(Self {
            negative: negative && digits != "0",
            digits: digits.to_owned(),
            scale,
        })
    }

    pub fn from_i64(value: i64) -> Self {
        Self {
            negative: value < 0,
            digits: value.unsigned_abs().to_string(),
            scale: 0,
        }
    }

    pub fn is_zero(&self) -> bool {
        self.digits == "0"
    }

    pub fn scale(&self) -> i64 {
        self.scale
    }

    /// `BigDecimal.toPlainString()`.
    pub fn to_plain_string(&self) -> String {
        let mut out = String::new();
        if self.negative {
            out.push('-');
        }
        if self.scale <= 0 {
            out.push_str(&self.digits);
            if !self.is_zero() {
                out.extend(std::iter::repeat_n('0', (-self.scale) as usize));
            }
        } else {
            let scale = self.scale as usize;
            let padded = if self.digits.len() <= scale {
                "0".repeat(scale + 1 - self.digits.len()) + &self.digits
            } else {
                self.digits.clone()
            };
            let (int, frac) = padded.split_at(padded.len() - scale);
            out.push_str(int);
            out.push('.');
            out.push_str(frac);
        }
        out
    }

    /// `BigDecimal.toString()`: plain notation unless the scale is negative or the value is below 1E-6, then
    /// scientific (`1E+3`, `1.5E-7`).
    pub fn to_java_string(&self) -> String {
        if self.scale == 0 {
            return self.to_plain_string();
        }
        let adjusted = -self.scale + (self.digits.len() as i64 - 1);
        if self.scale >= 0 && adjusted >= -6 {
            return self.to_plain_string();
        }
        let mut out = String::new();
        if self.negative {
            out.push('-');
        }
        out.push_str(&self.digits[..1]);
        if self.digits.len() > 1 {
            out.push('.');
            out.push_str(&self.digits[1..]);
        }
        if adjusted != 0 {
            out.push('E');
            if adjusted > 0 {
                out.push('+');
            }
            out.push_str(&adjusted.to_string());
        }
        out
    }

    /// The integer digits and the fraction digits, with the fraction's trailing zeros removed.
    fn parts(&self) -> (String, String) {
        let plain = self.to_plain_string();
        let plain = plain.trim_start_matches('-');
        let (int, frac) = plain.split_once('.').unwrap_or((plain, ""));
        let int = int.trim_start_matches('0');
        (int.to_owned(), frac.trim_end_matches('0').to_owned())
    }

    /// `BigDecimal.longValueExact()`: the value if it is a whole number that fits.
    pub fn to_i64_exact(&self) -> Option<i64> {
        let (int, frac) = self.parts();
        if !frac.is_empty() {
            return None;
        }
        if int.is_empty() {
            return Some(0);
        }
        let magnitude: i128 = int.parse().ok()?;
        i64::try_from(if self.negative { -magnitude } else { magnitude }).ok()
    }

    /// `BigDecimal.compareTo`: by value, ignoring scale.
    pub fn cmp_value(&self, other: &Self) -> Ordering {
        let sign = |d: &Self| {
            if d.is_zero() {
                0
            } else if d.negative {
                -1
            } else {
                1
            }
        };
        let (a, b) = (sign(self), sign(other));
        if a != b {
            return a.cmp(&b);
        }
        let (ai, af) = self.parts();
        let (bi, bf) = other.parts();
        let magnitude = ai
            .len()
            .cmp(&bi.len())
            .then_with(|| ai.cmp(&bi))
            .then_with(|| af.cmp(&bf));
        if a < 0 {
            magnitude.reverse()
        } else {
            magnitude
        }
    }

    /// As an `f64`, for engines without exact decimals.
    pub fn to_f64(&self) -> f64 {
        self.to_plain_string().parse().unwrap_or(f64::NAN)
    }
}

impl fmt::Display for Decimal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_plain_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plain(s: &str) -> String {
        Decimal::parse(s).unwrap().to_plain_string()
    }

    #[test]
    fn prints_like_big_decimal_to_string() {
        let java = |s: &str| Decimal::parse(s).unwrap().to_java_string();
        assert_eq!(java("1e3"), "1E+3");
        assert_eq!(java("1.50"), "1.50");
        assert_eq!(java("0.000001"), "0.000001");
        assert_eq!(java("0.0000001"), "1E-7");
        assert_eq!(java("-12.5e-9"), "-1.25E-8");
        assert_eq!(java("007"), "7");
        assert_eq!(java("0.000"), "0.000");
        assert_eq!(java("0e5"), "0E+5");
        assert_eq!(java("123e-2"), "1.23");
    }

    #[test]
    fn parses_and_prints_like_big_decimal() {
        assert_eq!(plain("1e3"), "1000");
        assert_eq!(plain("1.5E-3"), "0.0015");
        assert_eq!(plain("-0"), "0");
        assert_eq!(plain("-0.0"), "0.0");
        assert_eq!(plain("+5"), "5");
        assert_eq!(plain("00012"), "12");
        assert_eq!(plain(".5"), "0.5");
        assert_eq!(plain("5."), "5");
        assert_eq!(plain("1.50"), "1.50");
        assert_eq!(plain("-12.345e1"), "-123.45");
        for bad in [
            "", "-", ".", "1e", "1e+", "a", " 1", "1 ", "1_0", "NaN", "1.5f", "1..2",
        ] {
            assert!(Decimal::parse(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn compares_by_value_and_converts_exactly() {
        let d = |s| Decimal::parse(s).unwrap();
        assert_eq!(d("1.50").cmp_value(&d("1.5")), Ordering::Equal);
        assert_eq!(d("-2").cmp_value(&d("1")), Ordering::Less);
        assert_eq!(d("-2").cmp_value(&d("-10")), Ordering::Greater);
        assert_eq!(d("0.1").cmp_value(&d("0.09")), Ordering::Greater);
        assert_eq!(d("1.0").to_i64_exact(), Some(1));
        assert_eq!(d("1.5").to_i64_exact(), None);
        assert_eq!(d("-9223372036854775808").to_i64_exact(), Some(i64::MIN));
        assert_eq!(d("9223372036854775808").to_i64_exact(), None);
        assert_eq!(d("1e2").to_i64_exact(), Some(100));
    }
}
