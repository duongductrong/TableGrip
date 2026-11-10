use crate::kt::{self, eq_ignore_case};
use crate::lexer::{SqlLexer, TokenType};
use crate::syntax::SqlSyntax;

/// A statement inside a script. `start`/`end` cover the statement text, without leading/trailing comments and the
/// delimiter; `delimiter_end` includes the delimiter (equals `end` for a trailing statement without one). Byte offsets.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SqlStatement {
    pub text: String,
    pub start: usize,
    pub end: usize,
    pub delimiter_end: usize,
}

/// Splits scripts into statements, respecting quotes, comments, dollar quoting, MySQL's `DELIMITER` command, T-SQL's
/// `GO` and `BEGIN ... END` bodies inside `CREATE` statements (e.g. SQLite triggers).
#[derive(Clone, Copy, Debug)]
pub struct SqlSplitter {
    syntax: SqlSyntax,
    lexer: SqlLexer,
}

fn line_end(sql: &str, from: usize) -> usize {
    sql[from..].find('\n').map_or(sql.len(), |i| i + from)
}

impl SqlSplitter {
    pub fn new(syntax: SqlSyntax) -> Self {
        Self {
            syntax,
            lexer: SqlLexer::new(syntax),
        }
    }

    pub fn split(&self, sql: &str) -> Vec<SqlStatement> {
        let tokens = self.lexer.tokenize(sql);
        let mut statements = Vec::new();
        let mut delimiter = ";".to_owned();
        let mut start: Option<usize> = None;
        let mut end = 0;
        let mut skip_until = 0;
        let mut is_create = false;
        let mut block_depth = 0;

        let mut finish = |start: &mut Option<usize>,
                          end: usize,
                          delimiter_end: usize,
                          is_create: &mut bool,
                          block_depth: &mut i32| {
            if let Some(s) = start.take() {
                statements.push(SqlStatement {
                    text: sql[s..end].to_owned(),
                    start: s,
                    end,
                    delimiter_end,
                });
            }
            *is_create = false;
            *block_depth = 0;
        };

        for token in &tokens {
            if token.start < skip_until {
                continue;
            }
            if matches!(token.kind, TokenType::Whitespace | TokenType::Comment) {
                continue;
            }
            let text = token.text(sql);
            let is_code = matches!(
                token.kind,
                TokenType::Operator | TokenType::Keyword | TokenType::Identifier
            );
            if is_create && token.kind == TokenType::Keyword {
                match text.to_uppercase().as_str() {
                    "BEGIN" | "CASE" => block_depth += 1,
                    "END" if block_depth > 0 => block_depth -= 1,
                    _ => {}
                }
            }
            if is_code && block_depth == 0 && sql[token.start..].starts_with(delimiter.as_str()) {
                let delimiter_end = token.start + delimiter.len();
                finish(
                    &mut start,
                    end,
                    delimiter_end,
                    &mut is_create,
                    &mut block_depth,
                );
                skip_until = delimiter_end;
                continue;
            }
            if self.syntax.batch_separator
                && eq_ignore_case(text, "GO")
                && alone_on_line(sql, token.start, token.end)
            {
                finish(&mut start, end, token.end, &mut is_create, &mut block_depth);
                skip_until = line_end(sql, token.end);
                continue;
            }
            if self.syntax.delimiter_command && start.is_none() && eq_ignore_case(text, "DELIMITER")
            {
                let line_end = line_end(sql, token.end);
                if let Some(d) = kt::trim(&sql[token.end..line_end])
                    .split(kt::is_whitespace)
                    .next()
                    .filter(|d| !d.is_empty())
                {
                    delimiter = d.to_owned();
                }
                skip_until = line_end;
                continue;
            }
            if start.is_none() {
                start = Some(token.start);
                is_create = eq_ignore_case(text, "CREATE");
            }
            end = token.end;
        }
        finish(&mut start, end, end, &mut is_create, &mut block_depth);
        statements
    }

    /// The statement to run for a caret at byte `offset`: the one containing it, else the one just before it on the
    /// same stretch of whitespace (caret right after `;`), else the next one.
    pub fn statement_at(&self, sql: &str, offset: usize) -> Option<SqlStatement> {
        let statements = self.split(sql);
        if let Some(s) = statements
            .iter()
            .find(|s| (s.start..=s.delimiter_end).contains(&offset))
        {
            return Some(s.clone());
        }
        let before = |s: &&SqlStatement, ok: &dyn Fn(&str) -> bool| {
            s.delimiter_end <= offset && ok(&sql[s.delimiter_end..offset])
        };
        statements
            .iter()
            .rfind(|s| before(s, &|gap| !gap.contains('\n')))
            .or_else(|| statements.iter().rfind(|s| before(s, &kt::is_blank)))
            .or_else(|| {
                statements
                    .iter()
                    .find(|s| s.start >= offset && kt::is_blank(&sql[offset..s.start]))
            })
            .cloned()
    }
}

/// Whether only whitespace (or a `GO 5`-style count) shares the line with `start..end`.
fn alone_on_line(sql: &str, start: usize, end: usize) -> bool {
    let line_start = sql[..start].rfind('\n').map_or(0, |i| i + 1);
    let line_end = line_end(sql, end);
    kt::is_blank(&sql[line_start..start]) && kt::trim(&sql[end..line_end]).chars().all(kt::is_digit)
}
