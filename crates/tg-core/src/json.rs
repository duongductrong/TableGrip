//! The JSON the Kotlin stores read and write, so files round-trip.
//!
//! kotlinx.serialization differs from serde's defaults in three ways that matter here: `encodeDefaults = false` leaves
//! fields equal to their default out of the file ([`omit_defaults`]), `allowComments`/`allowTrailingComma` accept the
//! hand-edited files of VS Code and Zed ([`strip_lenient`]), and `prettyPrint` indents with four spaces unless a store
//! picks another indent ([`to_pretty`]).

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::ser::{PrettyFormatter, Serializer};
use std::borrow::Cow;

/// The text with `//` and `/* */` comments and trailing commas removed; strings are left alone. Text without either
/// is returned as it is.
pub fn strip_lenient(text: &str) -> Cow<'_, str> {
    if !text.contains('/') && !text.contains(',') {
        return Cow::Borrowed(text);
    }
    let mut out = String::with_capacity(text.len());
    // Where the last comma written outside a string is in `out`, while only whitespace has followed it.
    let mut pending_comma: Option<usize> = None;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' => {
                pending_comma = None;
                out.push(c);
                while let Some(s) = chars.next() {
                    out.push(s);
                    match s {
                        '\\' => {
                            if let Some(escaped) = chars.next() {
                                out.push(escaped);
                            }
                        }
                        '"' => break,
                        _ => {}
                    }
                }
            }
            '/' if chars.peek() == Some(&'/') => {
                for s in chars.by_ref() {
                    if s == '\n' {
                        out.push('\n');
                        break;
                    }
                }
            }
            '/' if chars.peek() == Some(&'*') => {
                chars.next();
                let mut last = ' ';
                for s in chars.by_ref() {
                    if last == '*' && s == '/' {
                        break;
                    }
                    last = s;
                }
                out.push(' ');
            }
            ',' => {
                pending_comma = Some(out.len());
                out.push(c);
            }
            '}' | ']' => {
                if let Some(at) = pending_comma.take() {
                    out.remove(at);
                }
                out.push(c);
            }
            c if c.is_whitespace() => out.push(c),
            c => {
                pending_comma = None;
                out.push(c);
            }
        }
    }
    Cow::Owned(out)
}

/// Parses like a kotlinx `Json { allowComments; allowTrailingComma; ignoreUnknownKeys }`. serde ignores unknown keys
/// unless a type opts out.
pub fn from_lenient_str<T: DeserializeOwned>(text: &str) -> Result<T, serde_json::Error> {
    serde_json::from_str(&strip_lenient(text))
}

/// `prettyPrint = true` with the given indent (kotlinx defaults to four spaces; settings and themes use two).
pub fn to_pretty<T: Serialize + ?Sized>(
    value: &T,
    indent: &str,
) -> Result<String, serde_json::Error> {
    let mut out = Vec::new();
    let formatter = PrettyFormatter::with_indent(indent.as_bytes());
    value.serialize(&mut Serializer::with_formatter(&mut out, formatter))?;
    Ok(String::from_utf8(out).expect("serde_json writes UTF-8"))
}

/// The first line of an error, which is what the Kotlin stores keep as their load error.
pub fn first_line(message: &str) -> String {
    message.lines().next().unwrap_or(message).to_string()
}

/// What a value is compared against to leave default fields out: [`Default::default`], or for types with required
/// fields the same value with every optional field reset.
pub trait Baseline {
    fn baseline(&self) -> Self;
}

/// `impl Serialize` that writes the listed fields in order, leaving out those equal to the baseline, as kotlinx does
/// with `encodeDefaults = false`. A field marked `= req` has no default and is always written.
///
/// ```ignore
/// omit_defaults!(Config { id: "id" = req, page_size: "pageSize" });
/// ```
macro_rules! omit_defaults {
    ($ty:ty { $($field:ident : $name:literal $(= $req:ident)?),+ $(,)? }) => {
        impl ::serde::Serialize for $ty {
            fn serialize<S: ::serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
                use ::serde::ser::SerializeMap;
                let baseline = $crate::json::Baseline::baseline(self);
                let mut map = serializer.serialize_map(None)?;
                $(
                    let required = false $(|| { let _ = stringify!($req); true })?;
                    if required || self.$field != baseline.$field {
                        map.serialize_entry($name, &self.$field)?;
                    }
                )+
                map.end()
            }
        }
    };
}
pub(crate) use omit_defaults;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn comments_and_trailing_commas_go_but_strings_stay() {
        let text = "{\n // note\n \"a\": \"x // not a comment, }\", /* c */\n \"b\": [1, 2,],\n}";
        let v: serde_json::Value = from_lenient_str(text).unwrap();
        assert_eq!(
            v,
            serde_json::json!({"a": "x // not a comment, }", "b": [1, 2]})
        );
    }

    #[test]
    fn plain_json_is_not_copied() {
        assert!(matches!(strip_lenient("{\"a\":1}"), Cow::Borrowed(_)));
    }

    #[test]
    fn a_broken_file_still_fails() {
        assert!(from_lenient_str::<serde_json::Value>("{ \"a\": }").is_err());
    }
}
