use crate::keywords;
use crate::kt::{char_at, char_before, is_digit, is_letter, is_letter_or_digit, is_whitespace};
use crate::syntax::SqlSyntax;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TokenType {
    Whitespace,
    Comment,
    Keyword,
    Identifier,
    QuotedIdentifier,
    String,
    Number,
    Parameter,
    Operator,
}

impl TokenType {
    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Whitespace => "Whitespace",
            Self::Comment => "Comment",
            Self::Keyword => "Keyword",
            Self::Identifier => "Identifier",
            Self::QuotedIdentifier => "QuotedIdentifier",
            Self::String => "String",
            Self::Number => "Number",
            Self::Parameter => "Parameter",
            Self::Operator => "Operator",
        }
    }
}

/// A token spanning the byte range `start..end` of its source.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Token {
    pub kind: TokenType,
    pub start: usize,
    pub end: usize,
}

impl Token {
    pub fn text<'a>(&self, source: &'a str) -> &'a str {
        &source[self.start..self.end]
    }
}

/// A forgiving SQL tokenizer: never fails, unterminated strings and comments run to the end of input.
#[derive(Clone, Copy, Debug)]
pub struct SqlLexer {
    syntax: SqlSyntax,
}

fn find_from(sql: &str, from: usize, pat: &str) -> Option<usize> {
    sql.get(from..)?.find(pat).map(|i| i + from)
}

impl SqlLexer {
    pub fn new(syntax: SqlSyntax) -> Self {
        Self { syntax }
    }

    pub fn tokenize(&self, sql: &str) -> Vec<Token> {
        let syntax = &self.syntax;
        let len = sql.len();
        let mut tokens = Vec::new();
        let mut i = 0;
        // Advances `i` while `f` holds for the character at `i`.
        let take_while = |mut i: usize, f: &dyn Fn(char) -> bool| {
            while let Some(c) = char_at(sql, i) {
                if !f(c) {
                    break;
                }
                i += c.len_utf8();
            }
            i
        };
        while i < len {
            let start = i;
            let c = char_at(sql, i).unwrap();
            let next = char_at(sql, i + c.len_utf8());
            let kind;
            if is_whitespace(c) {
                i = take_while(i, &is_whitespace);
                kind = TokenType::Whitespace;
            } else if c == '-' && next == Some('-') || c == '#' && syntax.hash_comments {
                i = find_from(sql, i, "\n").unwrap_or(len);
                kind = TokenType::Comment;
            } else if c == '/' && next == Some('*') {
                i = find_from(sql, i + 2, "*/").map_or(len, |e| e + 2);
                kind = TokenType::Comment;
            } else if c == '\'' {
                i = skip_quoted(sql, i, '\'', syntax.backslash_escapes);
                kind = TokenType::String;
            } else if c == '"' {
                i = skip_quoted(
                    sql,
                    i,
                    '"',
                    syntax.backslash_escapes && syntax.double_quoted_strings,
                );
                kind = if syntax.double_quoted_strings {
                    TokenType::String
                } else {
                    TokenType::QuotedIdentifier
                };
            } else if c == '[' && syntax.bracket_identifiers {
                // `]]` inside the brackets is an escaped bracket.
                i += 1;
                while let Some(ch) = char_at(sql, i) {
                    if ch == ']' {
                        if char_at(sql, i + 1) == Some(']') {
                            i += 2;
                        } else {
                            i += 1;
                            break;
                        }
                    } else {
                        i += ch.len_utf8();
                    }
                }
                kind = TokenType::QuotedIdentifier;
            } else if c == '`' && syntax.backtick_identifiers {
                i = skip_quoted(sql, i, '`', false);
                kind = TokenType::QuotedIdentifier;
            } else if let Some(tag) = (c == '$' && syntax.dollar_quoted_strings)
                .then(|| dollar_tag(sql, i))
                .flatten()
            {
                i = find_from(sql, i + tag.len(), tag).map_or(len, |e| e + tag.len());
                kind = TokenType::String;
            } else if c == '$' && next.is_some_and(is_digit) {
                i = take_while(i + 1, &is_digit);
                kind = TokenType::Parameter;
            } else if is_digit(c) || c == '.' && next.is_some_and(is_digit) {
                i = skip_number(sql, i);
                kind = TokenType::Number;
            } else if is_letter(c) || c == '_' {
                i = take_while(i, &|ch| is_letter_or_digit(ch) || ch == '_' || ch == '$');
                kind = if keywords::is_keyword(&sql[start..i]) {
                    TokenType::Keyword
                } else {
                    TokenType::Identifier
                };
            } else if c == ':'
                && next.is_some_and(|n| is_letter(n) || n == '_')
                && char_before(sql, i) != Some(':')
            {
                i = take_while(i + 1, &|ch| is_letter_or_digit(ch) || ch == '_');
                kind = TokenType::Parameter;
            } else if c == '?' {
                i += 1;
                kind = TokenType::Parameter;
            } else {
                i += c.len_utf8();
                kind = TokenType::Operator;
            }
            tokens.push(Token {
                kind,
                start,
                end: i,
            });
        }
        tokens
    }
}

fn skip_quoted(sql: &str, start: usize, quote: char, backslash_escapes: bool) -> usize {
    let mut i = start + 1;
    while let Some(c) = char_at(sql, i) {
        if backslash_escapes && c == '\\' {
            // Skips the backslash and the character it escapes, which may be several bytes long.
            i += 1;
            i += char_at(sql, i).map_or(1, char::len_utf8);
        } else if c == quote && char_at(sql, i + 1) == Some(quote) {
            i += 2;
        } else if c == quote {
            return i + 1;
        } else {
            i += c.len_utf8();
        }
    }
    sql.len()
}

fn skip_number(sql: &str, start: usize) -> usize {
    let mut i = start;
    while let Some(c) = char_at(sql, i).filter(|&c| is_digit(c) || c == '.') {
        i += c.len_utf8();
    }
    if matches!(char_at(sql, i), Some('e' | 'E')) {
        let sign = usize::from(matches!(char_at(sql, i + 1), Some('+' | '-')));
        if char_at(sql, i + 1 + sign).is_some_and(is_digit) {
            i += 1 + sign;
            while let Some(c) = char_at(sql, i).filter(|&c| is_digit(c)) {
                i += c.len_utf8();
            }
        }
    }
    i
}

/// The `$tag$` opening a dollar-quoted string at `start`, if any.
fn dollar_tag(sql: &str, start: usize) -> Option<&str> {
    let mut i = start + 1;
    while let Some(c) = char_at(sql, i).filter(|&c| is_letter_or_digit(c) || c == '_') {
        if i == start + 1 && is_digit(c) {
            return None;
        }
        i += c.len_utf8();
    }
    (char_at(sql, i) == Some('$')).then(|| &sql[start..=i])
}
