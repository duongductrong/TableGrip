//! Turns editor-style JSON (comments, trailing commas) into strict JSON, as the Kotlin side's lenient parser accepts.

/// [text] without `//` and `/* */` comments and without commas before `}` or `]`. Strings are left untouched.
pub fn strip(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    let mut in_string = false;
    while let Some(c) = chars.next() {
        if in_string {
            out.push(c);
            match c {
                '\\' => out.extend(chars.next()),
                '"' => in_string = false,
                _ => {}
            }
            continue;
        }
        match c {
            '"' => {
                in_string = true;
                out.push(c);
            }
            '/' if chars.peek() == Some(&'/') => {
                for c in chars.by_ref() {
                    if c == '\n' {
                        out.push('\n');
                        break;
                    }
                }
            }
            '/' if chars.peek() == Some(&'*') => {
                chars.next();
                let mut last = '\0';
                for c in chars.by_ref() {
                    if last == '*' && c == '/' {
                        break;
                    }
                    last = c;
                }
                out.push(' ');
            }
            '}' | ']' => {
                // Drop a trailing comma (and the whitespace after it) before the closing bracket.
                let trimmed = out.trim_end().len();
                if out[..trimmed].ends_with(',') {
                    out.truncate(trimmed - 1);
                }
                out.push(c);
            }
            _ => out.push(c),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn strips_comments_and_trailing_commas() {
        let text = "// head\n{ \"a\": \"//not a comment\", /* x */ \"b\": [1, 2,], }";
        let v: serde_json::Value = serde_json::from_str(&super::strip(text)).unwrap();
        assert_eq!(v["a"], "//not a comment");
        assert_eq!(v["b"], serde_json::json!([1, 2]));
    }
}
