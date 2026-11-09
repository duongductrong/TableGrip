use crate::kt;
use crate::lexer::{SqlLexer, Token, TokenType};
use crate::syntax::SqlSyntax;

/// How [`SqlFormatter`] writes keywords.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum KeywordCase {
    #[default]
    Upper,
    Lower,
    Preserve,
}

impl KeywordCase {
    pub fn apply(self, keyword: &str) -> String {
        match self {
            Self::Upper => keyword.to_uppercase(),
            Self::Lower => keyword.to_lowercase(),
            Self::Preserve => keyword.to_owned(),
        }
    }

    /// The case named `name` (the Kotlin enum name), upper case for anything else.
    pub fn of(name: &str) -> Self {
        match name {
            "Lower" => Self::Lower,
            "Preserve" => Self::Preserve,
            _ => Self::Upper,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Self::Upper => "Upper",
            Self::Lower => "Lower",
            Self::Preserve => "Preserve",
        }
    }
}

/// Reformats SQL in a conventional layout: keywords in one case (upper by default), one clause per line, multi-item
/// lists one item per line, AND/OR conditions on their own lines, and indented subqueries. Works on tokens, so strings,
/// comments, quoted identifiers and dollar-quoted bodies are never altered.
#[derive(Clone, Debug)]
pub struct SqlFormatter {
    lexer: SqlLexer,
    indent: String,
    keyword_case: KeywordCase,
}

/// A token with its final text (keywords upper-cased, adjacent operator characters joined).
#[derive(Clone, Debug, PartialEq, Eq)]
struct Tok {
    kind: TokenType,
    text: String,
    newline_before: bool,
    upper: String,
}

impl Tok {
    fn new(kind: TokenType, text: String, newline_before: bool) -> Self {
        let upper = text.to_uppercase();
        Self {
            kind,
            text,
            newline_before,
            upper,
        }
    }

    fn is_op(&self, ops: &[&str]) -> bool {
        self.kind == TokenType::Operator && ops.contains(&self.text.as_str())
    }

    fn is_word(&self) -> bool {
        matches!(self.kind, TokenType::Keyword | TokenType::Identifier)
    }
}

const SINGLE: &[&str] = &["(", ")", ",", ";", "."];

const CALL_LIKE_KEYWORDS: &[&str] = &[
    "COUNT",
    "CAST",
    "COALESCE",
    "EXISTS",
    "REPLACE",
    "LEFT",
    "RIGHT",
    "DATE",
    "TIME",
    "TIMESTAMP",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ClauseKind {
    /// Followed by a comma-separated list: SELECT, GROUP BY, ORDER BY, SET...
    List,
    /// Followed by AND/OR conditions: WHERE, HAVING, ON (of joins).
    Condition,
    /// Followed by a single expression or table reference.
    Simple,
    /// Stands on its own line: UNION, INTERSECT, EXCEPT.
    Standalone,
    /// Stays on the current line but starts conditions: ON.
    InlineCondition,
}

/// Longest first, so "LEFT OUTER JOIN" wins over "LEFT JOIN" (a stable sort of the Kotlin list by word count).
const CLAUSES: &[(&[&str], ClauseKind)] = &[
    (&["LEFT", "OUTER", "JOIN"], ClauseKind::Simple),
    (&["RIGHT", "OUTER", "JOIN"], ClauseKind::Simple),
    (&["FULL", "OUTER", "JOIN"], ClauseKind::Simple),
    (&["SELECT", "DISTINCT"], ClauseKind::List),
    (&["GROUP", "BY"], ClauseKind::List),
    (&["ORDER", "BY"], ClauseKind::List),
    (&["UNION", "ALL"], ClauseKind::Standalone),
    (&["LEFT", "JOIN"], ClauseKind::Simple),
    (&["RIGHT", "JOIN"], ClauseKind::Simple),
    (&["FULL", "JOIN"], ClauseKind::Simple),
    (&["INNER", "JOIN"], ClauseKind::Simple),
    (&["CROSS", "JOIN"], ClauseKind::Simple),
    (&["INSERT", "INTO"], ClauseKind::Simple),
    (&["DELETE", "FROM"], ClauseKind::Simple),
    (&["WITH", "RECURSIVE"], ClauseKind::Simple),
    (&["SELECT"], ClauseKind::List),
    (&["FROM"], ClauseKind::List),
    (&["WHERE"], ClauseKind::Condition),
    (&["HAVING"], ClauseKind::Condition),
    (&["LIMIT"], ClauseKind::Simple),
    (&["OFFSET"], ClauseKind::Simple),
    (&["UNION"], ClauseKind::Standalone),
    (&["INTERSECT"], ClauseKind::Standalone),
    (&["EXCEPT"], ClauseKind::Standalone),
    (&["JOIN"], ClauseKind::Simple),
    (&["ON"], ClauseKind::InlineCondition),
    (&["VALUES"], ClauseKind::List),
    (&["UPDATE"], ClauseKind::Simple),
    (&["SET"], ClauseKind::List),
    (&["RETURNING"], ClauseKind::List),
    (&["WITH"], ClauseKind::Simple),
];

impl SqlFormatter {
    pub fn new(syntax: SqlSyntax) -> Self {
        Self::with_options(syntax, "    ", KeywordCase::Upper)
    }

    pub fn with_options(syntax: SqlSyntax, indent: &str, keyword_case: KeywordCase) -> Self {
        Self {
            lexer: SqlLexer::new(syntax),
            indent: indent.to_owned(),
            keyword_case,
        }
    }

    pub fn format(&self, sql: &str) -> String {
        let tokens: Vec<Token> = self
            .lexer
            .tokenize(sql)
            .into_iter()
            .filter(|t| t.kind != TokenType::Whitespace)
            .collect();
        let tokens = self.merge(sql, &tokens);
        if tokens.is_empty() {
            return kt::trim(sql).to_owned();
        }
        let mut writer = Writer {
            fmt: self,
            tokens: &tokens,
            out: String::new(),
            level: 0,
            subqueries: Vec::new(),
            paren_depth: 0,
            subquery_depths: vec![0],
            list_clause: false,
            condition_clause: false,
        };
        let mut i = 0;
        while i < tokens.len() {
            i = writer.emit(i);
        }
        kt::trim_end(&writer.out).to_owned()
    }

    fn merge(&self, sql: &str, tokens: &[Token]) -> Vec<Tok> {
        let mut result = Vec::new();
        let mut i = 0;
        while i < tokens.len() {
            let token = tokens[i];
            let newline = i > 0 && sql[tokens[i - 1].end..token.start].contains('\n');
            if token.kind == TokenType::Operator && !SINGLE.contains(&token.text(sql)) {
                // Join "<", "=" into "<=", ":" ":" into "::"... when written without a gap.
                let mut end = token.end;
                let mut j = i + 1;
                while j < tokens.len()
                    && tokens[j].kind == TokenType::Operator
                    && tokens[j].start == end
                    && !SINGLE.contains(&tokens[j].text(sql))
                {
                    end = tokens[j].end;
                    j += 1;
                }
                result.push(Tok::new(
                    TokenType::Operator,
                    sql[token.start..end].to_owned(),
                    newline,
                ));
                i = j;
                continue;
            }
            let text = token.text(sql);
            let text = if token.kind == TokenType::Keyword {
                self.keyword_case.apply(text)
            } else {
                text.to_owned()
            };
            result.push(Tok::new(token.kind, text, newline));
            i += 1;
        }
        result
    }
}

struct Writer<'a> {
    fmt: &'a SqlFormatter,
    tokens: &'a [Tok],
    out: String,
    level: usize,
    /// Indent levels at which each open subquery parenthesis started.
    subqueries: Vec<usize>,
    paren_depth: i32,
    /// Paren depth of each open subquery, to recognize clause keywords at its top level.
    subquery_depths: Vec<i32>,
    list_clause: bool,
    condition_clause: bool,
}

impl Writer<'_> {
    fn at_top_level(&self) -> bool {
        Some(&self.paren_depth) == self.subquery_depths.last()
    }

    fn last(&self) -> Option<char> {
        self.out.chars().next_back()
    }

    fn emit(&mut self, index: usize) -> usize {
        let tokens = self.tokens;
        let case = self.fmt.keyword_case;
        let tok = &tokens[index];
        let prev = index.checked_sub(1).map(|p| &tokens[p]);

        if tok.kind == TokenType::Comment {
            if tok.newline_before && !self.out.is_empty() {
                self.newline(self.level);
            } else {
                self.space(prev, tok, index);
            }
            self.out.push_str(&tok.text);
            if tok.text.starts_with("--") || tok.text.starts_with('#') {
                self.newline(self.level);
            }
            return index + 1;
        }

        if tok.is_op(&[";"]) {
            self.trim_trailing();
            self.out.push_str(";\n\n");
            self.level = 0;
            self.paren_depth = 0;
            self.subqueries.clear();
            self.subquery_depths.clear();
            self.subquery_depths.push(0);
            self.list_clause = false;
            self.condition_clause = false;
            return index + 1;
        }

        // Multi-word clauses: GROUP BY, ORDER BY, LEFT OUTER JOIN, UNION ALL, INSERT INTO, DELETE FROM...
        if tok.is_word() && self.at_top_level() {
            let clause = self.clause_at(index);
            if let Some((_, ClauseKind::InlineCondition)) = clause {
                // ON stays on its JOIN's line but starts a condition list.
                self.space(prev, tok, index);
                self.out.push_str(&case.apply(&tok.text));
                self.out.push(' ');
                self.list_clause = false;
                self.condition_clause = true;
                return index + 1;
            }
            if let Some((words, kind)) = clause {
                if !self.out.is_empty() {
                    self.newline(self.level);
                }
                let joined = tokens[index..index + words]
                    .iter()
                    .map(|t| case.apply(&t.text))
                    .collect::<Vec<_>>()
                    .join(" ");
                self.out.push_str(&joined);
                let next = index + words;
                self.list_clause = kind == ClauseKind::List;
                self.condition_clause = kind == ClauseKind::Condition;
                // A list with several items goes one item per line; a single item stays on the clause's line.
                if kind == ClauseKind::List && self.has_top_level_comma(next) {
                    self.newline(self.level + 1);
                } else if kind == ClauseKind::Standalone {
                    // e.g. UNION: the next SELECT starts its own line anyway.
                } else if next < tokens.len() && !tokens[next].is_op(&[";", ")"]) {
                    self.out.push(' ');
                }
                return next;
            }
            if self.condition_clause
                && (tok.upper == "AND" || tok.upper == "OR")
                && !self.is_between_and(index)
            {
                self.newline(self.level + 1);
                self.out.push_str(&case.apply(&tok.text));
                self.out.push(' ');
                return index + 1;
            }
        }

        if tok.is_op(&["("]) {
            self.space(prev, tok, index);
            self.out.push('(');
            self.paren_depth += 1;
            if let Some(next) = tokens.get(index + 1)
                && (next.upper == "SELECT" || next.upper == "WITH")
            {
                self.subqueries.push(self.level);
                self.subquery_depths.push(self.paren_depth);
                self.level += 1;
                self.newline(self.level);
            }
            return index + 1;
        }
        if tok.is_op(&[")"]) {
            if self.subquery_depths.len() > 1
                && self.subquery_depths.last() == Some(&self.paren_depth)
            {
                self.subquery_depths.pop();
                self.level = self.subqueries.pop().unwrap_or(0);
                self.newline(self.level);
            } else {
                self.trim_trailing_spaces();
            }
            self.out.push(')');
            self.paren_depth -= 1;
            return index + 1;
        }
        if tok.is_op(&[","]) {
            self.trim_trailing_spaces();
            self.out.push(',');
            if self.list_clause && self.at_top_level() {
                self.newline(self.level + 1);
            } else {
                self.out.push(' ');
            }
            return index + 1;
        }

        self.space(prev, tok, index);
        self.out.push_str(&tok.text);
        index + 1
    }

    /// Whether the BETWEEN ... AND ... at `index` is the range's AND rather than a condition separator.
    fn is_between_and(&self, index: usize) -> bool {
        for i in (index.saturating_sub(4)..index).rev() {
            let upper = self.tokens[i].upper.as_str();
            if upper == "BETWEEN" {
                return true;
            }
            if upper == "AND" || upper == "OR" {
                return false;
            }
        }
        false
    }

    fn has_top_level_comma(&self, from: usize) -> bool {
        let mut depth = 0;
        for (i, t) in self.tokens.iter().enumerate().skip(from) {
            if t.is_op(&["("]) {
                depth += 1;
            } else if t.is_op(&[")"]) {
                if depth == 0 {
                    return false;
                }
                depth -= 1;
            } else if t.is_op(&[";"]) {
                return false;
            } else if depth == 0 && t.is_op(&[","]) {
                return true;
            } else if depth == 0 && t.is_word() && self.clause_at(i).is_some() {
                return false;
            }
        }
        false
    }

    fn clause_at(&self, index: usize) -> Option<(usize, ClauseKind)> {
        CLAUSES.iter().find_map(|&(words, kind)| {
            let toks = self.tokens.get(index..index + words.len())?;
            toks.iter()
                .zip(words)
                .all(|(t, w)| t.upper == *w && t.is_word())
                .then_some((words.len(), kind))
        })
    }

    fn space(&mut self, prev: Option<&Tok>, tok: &Tok, index: usize) {
        let Some(prev) = prev else { return };
        if self.out.is_empty() || matches!(self.last(), Some('\n' | ' ' | '\t' | '(')) {
            return;
        }
        let no_space = tok.is_op(&[".", "::"])
            || prev.is_op(&[".", "::"])
            // Function calls: name( — but keep "IN (", "VALUES (", "AS (".
            || (tok.is_op(&["("]) && prev.kind == TokenType::Identifier && !self.is_table_name(index - 1))
            || (tok.is_op(&["("])
                && prev.kind == TokenType::Keyword
                && CALL_LIKE_KEYWORDS.contains(&prev.upper.as_str()))
            // Unary minus/plus after an operator, comma, paren or keyword.
            || (prev.is_op(&["-", "+"]) && self.is_unary(prev));
        if !no_space {
            self.out.push(' ');
        }
    }

    /// `INSERT INTO t (a, b)` and `CREATE TABLE t (...)`: a table name, not a function call.
    fn is_table_name(&self, index: usize) -> bool {
        let mut i = index as isize - 1;
        while i >= 1 && self.tokens[i as usize].is_op(&["."]) {
            i -= 2;
        }
        usize::try_from(i)
            .ok()
            .and_then(|i| self.tokens.get(i))
            .is_some_and(|t| {
                ["INTO", "TABLE", "UPDATE", "JOIN", "FROM"].contains(&t.upper.as_str())
            })
    }

    fn is_unary(&self, op: &Tok) -> bool {
        // Kotlin's `tokens.indexOf(op)`: the first token equal to it, which need not be the one at hand.
        let index = self.tokens.iter().position(|t| t == op).unwrap_or(0);
        let Some(before) = index.checked_sub(1).map(|b| &self.tokens[b]) else {
            return true;
        };
        before.kind == TokenType::Operator && !before.is_op(&[")"])
            || before.kind == TokenType::Keyword
    }

    fn newline(&mut self, indent_level: usize) {
        self.trim_trailing_spaces();
        if !self.out.is_empty() && self.last() != Some('\n') {
            self.out.push('\n');
        }
        for _ in 0..indent_level {
            self.out.push_str(&self.fmt.indent);
        }
    }

    fn trim_trailing_spaces(&mut self) {
        let len = self.out.trim_end_matches([' ', '\t']).len();
        self.out.truncate(len);
    }

    fn trim_trailing(&mut self) {
        let len = kt::trim_end(&self.out).len();
        self.out.truncate(len);
    }
}
