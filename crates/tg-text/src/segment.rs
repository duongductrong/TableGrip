//! Grapheme, word and line boundaries over a string, by byte offset.

use unicode_segmentation::UnicodeSegmentation;

/// The grapheme boundary before `pos`, or 0.
pub fn prev_grapheme(text: &str, pos: usize) -> usize {
    text[..pos]
        .grapheme_indices(true)
        .next_back()
        .map_or(0, |(i, _)| i)
}

/// The grapheme boundary after `pos`, or the end.
pub fn next_grapheme(text: &str, pos: usize) -> usize {
    text[pos..]
        .graphemes(true)
        .next()
        .map_or(text.len(), |g| pos + g.len())
}

/// The nearest grapheme boundary at or before `pos` (which may be anywhere, even inside a character).
pub fn floor_boundary(text: &str, pos: usize) -> usize {
    if pos >= text.len() {
        return text.len();
    }
    text.grapheme_indices(true)
        .map(|(i, _)| i)
        .take_while(|&i| i <= pos)
        .last()
        .unwrap_or(0)
}

pub fn grapheme_count(text: &str) -> usize {
    text.graphemes(true).count()
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Class {
    Space,
    Word,
    Punct,
}

fn class(grapheme: &str) -> Class {
    match grapheme.chars().next() {
        Some(c) if c.is_whitespace() => Class::Space,
        Some(c) if c.is_alphanumeric() || c == '_' => Class::Word,
        _ => Class::Punct,
    }
}

/// Where moving one word right from `pos` lands: past any white space, then to the end of the word, or past one
/// punctuation mark. Matches the Kotlin app: `hello world` stops at 5 and 11, `a.b` at 1, 2 and 3.
pub fn next_word_end(text: &str, pos: usize) -> usize {
    let mut graphemes = text[pos..].grapheme_indices(true).peekable();
    let mut end = pos;
    while let Some((i, g)) = graphemes.next_if(|(_, g)| class(g) == Class::Space) {
        end = pos + i + g.len();
    }
    match graphemes.next() {
        Some((i, g)) if class(g) == Class::Punct => pos + i + g.len(),
        Some((i, g)) => {
            end = pos + i + g.len();
            for (i, g) in graphemes.take_while(|(_, g)| class(g) == Class::Word) {
                end = pos + i + g.len();
            }
            end
        }
        None => end,
    }
}

/// Where moving one word left from `pos` lands; the mirror of [`next_word_end`].
pub fn prev_word_start(text: &str, pos: usize) -> usize {
    let mut graphemes = text[..pos].grapheme_indices(true).rev().peekable();
    let mut start = pos;
    while let Some((i, _)) = graphemes.next_if(|(_, g)| class(g) == Class::Space) {
        start = i;
    }
    match graphemes.next() {
        Some((i, g)) if class(g) == Class::Punct => i,
        Some((i, _)) => {
            start = i;
            for (i, _) in graphemes.take_while(|(_, g)| class(g) == Class::Word) {
                start = i;
            }
            start
        }
        None => start,
    }
}

/// The word at `pos` for a double-click: the run of word characters touching it (preferring the one after), else
/// the single grapheme there.
pub fn word_at(text: &str, pos: usize) -> (usize, usize) {
    let after = text[pos..].graphemes(true).next().map(class);
    let before = text[..pos].graphemes(true).next_back().map(class);
    let anchor = match (before, after) {
        (_, Some(Class::Word)) => pos,
        (Some(Class::Word), _) => prev_grapheme(text, pos),
        (_, Some(_)) => return (pos, next_grapheme(text, pos)),
        (Some(_), None) => return (prev_grapheme(text, pos), pos),
        (None, None) => return (pos, pos),
    };
    let mut start = anchor;
    while start > 0 && class(&text[prev_grapheme(text, start)..start]) == Class::Word {
        start = prev_grapheme(text, start);
    }
    let mut end = anchor;
    while end < text.len() && class(&text[end..next_grapheme(text, end)]) == Class::Word {
        end = next_grapheme(text, end);
    }
    (start, end)
}

pub fn line_start(text: &str, pos: usize) -> usize {
    text[..pos].rfind('\n').map_or(0, |i| i + 1)
}

pub fn line_end(text: &str, pos: usize) -> usize {
    text[pos..].find('\n').map_or(text.len(), |i| pos + i)
}

/// The offset `column` graphemes into the line starting at `start`, or the line's end if it's shorter.
pub fn at_column(text: &str, start: usize, column: usize) -> usize {
    let end = line_end(text, start);
    text[start..end]
        .grapheme_indices(true)
        .nth(column)
        .map_or(end, |(i, _)| start + i)
}

/// Whether Backspace removes this grapheme whole rather than its last code point. Backspace takes one code point at a
/// time, so a decomposed accent (`e` + U+0301, Vietnamese `ệ` as `e` + U+0323 + U+0302) goes before its letter, but
/// emoji, flags and ZWJ sequences go at once. Both are what the Kotlin app does.
pub fn deletes_whole(grapheme: &str) -> bool {
    grapheme.chars().any(|c| {
        matches!(c as u32, 0x1F000..=0x1FAFF | 0x2600..=0x27BF | 0x200D | 0xFE0F | 0x20E3 | 0xE0020..=0xE007F)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn words_stop_like_the_app() {
        let t = "hello world";
        assert_eq!(next_word_end(t, 0), 5);
        assert_eq!(next_word_end(t, 5), 11);
        assert_eq!(prev_word_start(t, 11), 6);
        assert_eq!(prev_word_start(t, 6), 0);
        let t = "a.b foo-bar";
        assert_eq!(next_word_end(t, 0), 1);
        assert_eq!(next_word_end(t, 1), 2);
        assert_eq!(prev_word_start(t, 11), 8);
        assert_eq!(prev_word_start(t, 8), 7);
    }

    #[test]
    fn words_include_accented_letters() {
        let t = "tiếng Việt";
        assert_eq!(next_word_end(t, 0), "tiếng".len());
        assert_eq!(prev_word_start(t, t.len()), "tiếng ".len());
        let t = "Vie\u{323}\u{302}t nam";
        assert_eq!(next_word_end(t, 0), "Vie\u{323}\u{302}t".len());
    }

    #[test]
    fn word_at_prefers_the_word_after() {
        assert_eq!(word_at("hello world", 11), (6, 11));
        assert_eq!(word_at("hello world", 5), (0, 5));
        assert_eq!(word_at("hello world", 6), (6, 11));
        assert_eq!(word_at("a, b", 1), (0, 1));
        assert_eq!(word_at("", 0), (0, 0));
    }

    #[test]
    fn graphemes_and_floor() {
        let t = "a👍🏽b";
        assert_eq!(next_grapheme(t, 1), 1 + "👍🏽".len());
        assert_eq!(prev_grapheme(t, t.len() - 1), 1);
        assert_eq!(floor_boundary(t, 3), 1);
        assert_eq!(floor_boundary(t, t.len()), t.len());
        assert!(deletes_whole("👍🏽"));
        assert!(!deletes_whole("e\u{301}"));
    }
}
