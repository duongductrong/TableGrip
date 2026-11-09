//! Kotlin's character and string rules, so ported code classifies text the way the Kotlin core does.
//!
//! The Kotlin code works on UTF-16 code units; this crate works on `char`s and byte offsets. The two agree everywhere
//! except on characters outside the Basic Multilingual Plane (emoji, for example) that appear outside quotes or
//! comments: Kotlin sees each surrogate half on its own, this crate sees one `char`. Letter and digit tests use Rust's
//! `Alphabetic` and `Numeric` properties, which are slightly wider than Java's `isLetter` (letters) and `isDigit`
//! (decimal digits) on rare characters such as letter numerals.

/// Kotlin's `Char.isWhitespace()`: Java's `isWhitespace` or `isSpaceChar`.
pub fn is_whitespace(c: char) -> bool {
    matches!(c, '\u{1C}'..='\u{1F}') || (c.is_whitespace() && c != '\u{85}')
}

/// Kotlin's `Char.isLetter()`.
pub fn is_letter(c: char) -> bool {
    c.is_alphabetic()
}

/// Kotlin's `Char.isDigit()`.
pub fn is_digit(c: char) -> bool {
    c.is_ascii_digit() || (!c.is_ascii() && c.is_numeric())
}

/// Kotlin's `Char.isLetterOrDigit()`.
pub fn is_letter_or_digit(c: char) -> bool {
    is_letter(c) || is_digit(c)
}

/// Kotlin's `String.trim()`.
pub fn trim(s: &str) -> &str {
    s.trim_matches(is_whitespace)
}

/// Kotlin's `String.trimEnd()`.
pub fn trim_end(s: &str) -> &str {
    s.trim_end_matches(is_whitespace)
}

/// Kotlin's `String.trimStart()`.
pub fn trim_start(s: &str) -> &str {
    s.trim_start_matches(is_whitespace)
}

/// Kotlin's `CharSequence.isBlank()`.
pub fn is_blank(s: &str) -> bool {
    s.chars().all(is_whitespace)
}

/// The character starting at byte `i`, if any.
pub(crate) fn char_at(s: &str, i: usize) -> Option<char> {
    s.get(i..).and_then(|rest| rest.chars().next())
}

/// The character ending at byte `i`, if any.
pub(crate) fn char_before(s: &str, i: usize) -> Option<char> {
    s.get(..i).and_then(|head| head.chars().next_back())
}

/// Kotlin's `String.equals(other, ignoreCase = true)`.
pub fn eq_ignore_case(a: &str, b: &str) -> bool {
    a.chars().count() == b.chars().count()
        && a.chars().zip(b.chars()).all(|(x, y)| {
            x == y || x.to_uppercase().eq(y.to_uppercase()) || x.to_lowercase().eq(y.to_lowercase())
        })
}
