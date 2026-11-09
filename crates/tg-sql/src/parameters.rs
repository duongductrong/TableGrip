use std::collections::HashMap;

use crate::kt::{self, char_at};
use crate::lexer::{SqlLexer, TokenType};
use crate::syntax::SqlSyntax;

/// Named placeholders in a script: `:name` and `${name}`, outside strings, comments and quoted identifiers (`a::int` is
/// a cast, not a parameter). Values replace them as written, so text values need their quotes: `'paid'`, while `42` or
/// `now()` go in as they are.
#[derive(Clone, Copy, Debug)]
pub struct SqlParameters {
    lexer: SqlLexer,
}

struct Occurrence<'a> {
    name: &'a str,
    start: usize,
    end: usize,
}

impl SqlParameters {
    pub fn new(syntax: SqlSyntax) -> Self {
        Self {
            lexer: SqlLexer::new(syntax),
        }
    }

    /// Distinct parameter names in order of first appearance.
    pub fn find(&self, sql: &str) -> Vec<String> {
        let mut names: Vec<String> = Vec::new();
        for o in self.occurrences(sql) {
            if !names.iter().any(|n| n == o.name) {
                names.push(o.name.to_owned());
            }
        }
        names
    }

    /// `sql` with each parameter replaced by its value; parameters without one are left as they are.
    pub fn substitute(&self, sql: &str, values: &HashMap<String, String>) -> String {
        let mut out = String::with_capacity(sql.len());
        let mut last = 0;
        for o in self.occurrences(sql) {
            let Some(value) = values.get(o.name) else {
                continue;
            };
            out.push_str(&sql[last..o.start]);
            out.push_str(value);
            last = o.end;
        }
        out.push_str(&sql[last..]);
        out
    }

    fn occurrences<'a>(&self, sql: &'a str) -> Vec<Occurrence<'a>> {
        let tokens = self.lexer.tokenize(sql);
        let mut found = Vec::new();
        let mut i = 0;
        while i < tokens.len() {
            let token = tokens[i];
            let first = char_at(sql, token.start);
            if token.kind == TokenType::Parameter && first == Some(':') {
                found.push(Occurrence {
                    name: &sql[token.start + 1..token.end],
                    start: token.start,
                    end: token.end,
                });
            } else if token.kind == TokenType::Operator
                && first == Some('$')
                && char_at(sql, token.end) == Some('{')
            {
                // `${name}` lexes as `$`, `{`, the name, `}`.
                let close = sql[token.end..].find('}').map(|c| c + token.end);
                let name = close.map_or("", |close| kt::trim(&sql[token.end + 1..close]));
                if let Some(close) = close
                    && !name.is_empty()
                    && name
                        .chars()
                        .all(|c| kt::is_letter_or_digit(c) || c == '_' || c == '.')
                {
                    found.push(Occurrence {
                        name,
                        start: token.start,
                        end: close + 1,
                    });
                    while i + 1 < tokens.len() && tokens[i + 1].start <= close {
                        i += 1;
                    }
                }
            }
            i += 1;
        }
        found
    }
}
