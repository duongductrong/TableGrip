//! How Java printed values that JDBC drivers handed over as Java objects (`Double.toString`, `Timestamp.toString`…),
//! for engines whose clients give typed values rather than text.

/// Java's `Double.toString`: plain between 10⁻³ and 10⁷, else `d.dddE±n`; always a fraction digit.
pub fn java_double(v: f64) -> String {
    if v.is_nan() {
        return "NaN".into();
    }
    if v.is_infinite() {
        return if v > 0.0 { "Infinity" } else { "-Infinity" }.into();
    }
    if v == 0.0 {
        return if v.is_sign_negative() { "-0.0" } else { "0.0" }.into();
    }
    if (1e-3..1e7).contains(&v.abs()) {
        let s = v.to_string();
        return if s.contains('.') { s } else { s + ".0" };
    }
    let s = format!("{v:e}");
    let (mantissa, exponent) = s.split_once('e').unwrap_or((&s, "0"));
    let mantissa = if mantissa.contains('.') {
        mantissa.to_owned()
    } else {
        format!("{mantissa}.0")
    };
    format!("{mantissa}E{exponent}")
}

/// Java's `Float.toString`, from the float's own shortest digits.
pub fn java_float(v: f32) -> String {
    if v.is_nan() || v.is_infinite() || v == 0.0 {
        return java_double(f64::from(v));
    }
    if (1e-3..1e7).contains(&v.abs()) {
        let s = v.to_string();
        return if s.contains('.') { s } else { s + ".0" };
    }
    let s = format!("{v:e}");
    let (mantissa, exponent) = s.split_once('e').unwrap_or((&s, "0"));
    let mantissa = if mantissa.contains('.') {
        mantissa.to_owned()
    } else {
        format!("{mantissa}.0")
    };
    format!("{mantissa}E{exponent}")
}

#[cfg(feature = "duckdb")]
/// `java.sql.Timestamp.toString` from a date and the nanoseconds into that day: the fraction's trailing zeros dropped,
/// at least one digit kept.
pub fn timestamp(date: chrono::NaiveDate, nanos_of_day: i64) -> String {
    let seconds = nanos_of_day / 1_000_000_000;
    let nanos = nanos_of_day % 1_000_000_000;
    let fraction = format!("{nanos:09}");
    let fraction = fraction.trim_end_matches('0');
    format!(
        "{} {:02}:{:02}:{:02}.{}",
        date.format("%Y-%m-%d"),
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60,
        if fraction.is_empty() { "0" } else { fraction }
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_print_as_java_does() {
        assert_eq!(java_double(2.5), "2.5");
        assert_eq!(java_double(100.0), "100.0");
        assert_eq!(java_double(1e10), "1.0E10");
        assert_eq!(java_double(1.5e-5), "1.5E-5");
        assert_eq!(java_double(-0.0), "-0.0");
        assert_eq!(java_float(0.1), "0.1");
    }

    #[cfg(feature = "duckdb")]
    #[test]
    fn timestamps_print_as_java_does() {
        let day = chrono::NaiveDate::from_ymd_opt(2024, 2, 3).unwrap();
        assert_eq!(timestamp(day, 14_706_500_000_000), "2024-02-03 04:05:06.5");
        assert_eq!(timestamp(day, 0), "2024-02-03 00:00:00.0");
    }
}
