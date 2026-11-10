//! Fixing a statement that failed (`assist/SqlFix.kt`): without an agent when one misspelt name has a clear closest
//! match, else by asking an agent with the error and the schema.

use super::{AssistInvalid, AssistTask, JsonObject, require_text};
use regex::Regex;
use serde_json::Value;
use std::sync::LazyLock;
use tg_driver::QueryError;
use tg_sql::completion::SchemaProvider;
use tg_sql::completion::sql::SqlCompleter;
use tg_sql::{
    SqlClassifier, SqlDialect, SqlLexer, SqlSplitter, SqlSyntax, StatementKind, TokenType,
};

/// A corrected statement, or only `explanation` when there is no fix (e.g. the table really doesn't exist).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SqlFix {
    pub sql: Option<String>,
    pub explanation: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MissingKind {
    Column,
    Table,
}

/// A table or column the engine says doesn't exist, read from its error message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MissingName {
    pub kind: MissingKind,
    pub name: String,
}

static PATTERNS: LazyLock<Vec<(MissingKind, Regex)>> = LazyLock::new(|| {
    use MissingKind::{Column as C, Table as T};
    [
        // PostgreSQL, CockroachDB
        (C, r#"column "?([^"\s]+)"? does not exist"#),
        (T, r#"relation "?([^"\s]+)"? does not exist"#),
        // MySQL, MariaDB
        (C, r"Unknown column '([^']+)'"),
        (T, r"Table '([^']+)' doesn't exist"),
        // SQLite
        (C, r"no such column: ([^\s)]+)"),
        (T, r"no such table: ([^\s)]+)"),
        // SQL Server
        (C, r"Invalid column name '([^']+)'"),
        (T, r"Invalid object name '([^']+)'"),
        // DuckDB
        (C, r#"Referenced column "([^"]+)" not found"#),
        (T, r"Table with name ([^\s!]+) does not exist"),
        // H2
        (C, r#"Column "([^"]+)" not found"#),
        (T, r#"Table "([^"]+)" not found"#),
        // ClickHouse
        (C, r"Missing columns: '([^']+)'"),
        (C, r"Unknown expression identifier [`']([^`']+)[`']"),
        (T, r"Unknown table expression identifier '([^']+)'"),
    ]
    .into_iter()
    .map(|(k, p)| (k, Regex::new(&format!("(?i){p}")).unwrap()))
    .collect()
});

pub struct SqlErrors;

impl SqlErrors {
    /// The unknown name in `error`, unqualified (`u.usr_id` → `usr_id`); None for other errors.
    pub fn missing_name(error: &QueryError) -> Option<MissingName> {
        for (kind, pattern) in PATTERNS.iter() {
            let Some(c) = pattern.captures(&error.message) else {
                continue;
            };
            let raw = &c[1];
            let name = raw
                .rsplit('.')
                .next()
                .unwrap_or(raw)
                .trim_matches(['"', '`', '\'', '[', ']']);
            if !name.is_empty() {
                return Some(MissingName {
                    kind: *kind,
                    name: name.to_owned(),
                });
            }
        }
        None
    }
}

/// Fixes without an agent: a misspelt table or column replaced by the closest real one, only when one name is
/// clearly closest.
pub struct SqlNameFixer {
    dialect: SqlDialect,
    lexer: SqlLexer,
    completer: SqlCompleter,
}

impl SqlNameFixer {
    pub fn new(dialect: SqlDialect) -> Self {
        Self {
            dialect,
            lexer: SqlLexer::new(dialect.syntax()),
            completer: SqlCompleter::new(dialect),
        }
    }

    /// Real names close to `missing`, closest first; empty while the schema isn't loaded.
    pub fn near_misses(
        &self,
        statement: &str,
        missing: &MissingName,
        schema: &dyn SchemaProvider,
        limit: usize,
    ) -> Vec<String> {
        ranked(&missing.name, &self.candidates(statement, missing, schema))
            .into_iter()
            .take(limit)
            .map(|r| r.0)
            .collect()
    }

    /// The statement with the error's unknown name replaced, when exactly one real name is closest.
    pub fn fix(
        &self,
        statement: &str,
        error: &QueryError,
        schema: &dyn SchemaProvider,
    ) -> Option<SqlFix> {
        let missing = SqlErrors::missing_name(error)?;
        let ranked = ranked(&missing.name, &self.candidates(statement, &missing, schema));
        let best = ranked.first()?;
        // Two names equally close (`user_id`, `users_id`): a guess, so leave it to the user or an agent.
        if ranked.get(1).is_some_and(|r| r.1 == best.1) {
            return None;
        }
        let fixed = self.replace_name(statement, &missing.name, &best.0)?;
        let what = if missing.kind == MissingKind::Column {
            "column"
        } else {
            "table"
        };
        Some(SqlFix {
            sql: Some(fixed),
            explanation: format!(
                "There is no {what} {}; the closest is {}.",
                missing.name, best.0
            ),
        })
    }

    /// Columns of the statement's tables, or the tables of the namespace the unknown one was looked for in.
    fn candidates(
        &self,
        statement: &str,
        missing: &MissingName,
        schema: &dyn SchemaProvider,
    ) -> Vec<String> {
        let tables = self.completer.tables_in(statement);
        let names: Vec<String> = match missing.kind {
            MissingKind::Column => tables
                .iter()
                .flat_map(|(ns, t)| {
                    schema
                        .columns(ns.as_deref(), t)
                        .unwrap_or_default()
                        .into_iter()
                        .map(|c| c.name)
                })
                .collect(),
            MissingKind::Table => {
                let namespace = tables
                    .iter()
                    .find(|(_, t)| t.eq_ignore_ascii_case(&missing.name))
                    .and_then(|(ns, _)| ns.clone());
                schema
                    .tables(namespace.as_deref())
                    .unwrap_or_default()
                    .into_iter()
                    .map(|t| t.name)
                    .collect()
            }
        };
        let mut seen = std::collections::HashSet::new();
        names
            .into_iter()
            .filter(|n| seen.insert(n.clone()))
            .collect()
    }

    /// Replaces every identifier spelled `from` (ignoring case and quotes) by `to`; None when there is none.
    pub fn replace_name(&self, statement: &str, from: &str, to: &str) -> Option<String> {
        let tokens: Vec<_> = self
            .lexer
            .tokenize(statement)
            .into_iter()
            .filter(|t| {
                matches!(
                    t.kind,
                    TokenType::Identifier | TokenType::QuotedIdentifier | TokenType::Keyword
                ) && unquote(t.text(statement)).eq_ignore_ascii_case(from)
            })
            .collect();
        if tokens.is_empty() {
            return None;
        }
        let mut result = statement.to_owned();
        for token in tokens.iter().rev() {
            let replacement = if token.kind == TokenType::QuotedIdentifier || self.needs_quotes(to)
            {
                self.dialect.quote_identifier(to)
            } else {
                to.to_owned()
            };
            result.replace_range(token.start..token.end, &replacement);
        }
        Some(result)
    }

    fn needs_quotes(&self, name: &str) -> bool {
        static PLAIN: LazyLock<Regex> =
            LazyLock::new(|| Regex::new("^[A-Za-z_][A-Za-z0-9_]*$").unwrap());
        !PLAIN.is_match(name)
            || (self.dialect.syntax() == SqlSyntax::POSTGRESQL && name != name.to_lowercase())
    }
}

fn unquote(text: &str) -> &str {
    if text.len() >= 2 && text.starts_with(['"', '`', '[']) {
        &text[1..text.len() - 1]
    } else {
        text
    }
}

/// Candidates within a few edits of `name`, closest first, as (candidate, distance).
pub fn ranked(name: &str, candidates: &[String]) -> Vec<(String, usize)> {
    let target = name.to_lowercase();
    let mut out: Vec<(String, usize)> = candidates
        .iter()
        .filter(|c| !c.eq_ignore_ascii_case(name))
        .map(|c| {
            let lower = c.to_lowercase();
            (
                c.clone(),
                distance(&target, &lower)
                    .min(distance(&target.replace('_', ""), &lower.replace('_', ""))),
            )
        })
        .filter(|(c, d)| *d <= 1.max(target.chars().count().min(c.chars().count()) / 3))
        .collect();
    out.sort_by(|a, b| {
        a.1.cmp(&b.1)
            .then(a.0.chars().count().cmp(&b.0.chars().count()))
    });
    out
}

/// Damerau-Levenshtein (optimal string alignment): a swapped pair of letters is one typo, not two.
pub fn distance(a: &str, b: &str) -> usize {
    let a: Vec<char> = a.chars().collect();
    let b: Vec<char> = b.chars().collect();
    let mut d = vec![vec![0usize; b.len() + 1]; a.len() + 1];
    for (i, row) in d.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, cell) in d[0].iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=a.len() {
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            d[i][j] = (d[i - 1][j] + 1)
                .min(d[i][j - 1] + 1)
                .min(d[i - 1][j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                d[i][j] = d[i][j].min(d[i - 2][j - 2] + 1);
            }
        }
    }
    d[a.len()][b.len()]
}

/// What a reviewer should notice about a proposed fix before applying it, worst first; empty when it only corrects.
pub fn review_fix(original: &str, fixed: &str, syntax: SqlSyntax) -> Vec<String> {
    let classifier = SqlClassifier::new(syntax);
    let before = classifier.classify(original);
    let after = classifier.classify(fixed);
    let mut out = Vec::new();
    if let Some(d) = &after.danger {
        out.push(d.clone());
    }
    if !before.changes_data() && after.changes_data() {
        out.push(format!(
            "The fix changes data or schema ({}); the original only read.",
            after.verb
        ));
    } else if after.kind != StatementKind::Read && before.verb != after.verb {
        out.push(format!(
            "The fix runs {} instead of {}.",
            after.verb, before.verb
        ));
    }
    out
}

/// Asks an agent to correct a statement that failed. It gets the engine, the statement, the error and the schema
/// (never data), and must answer with one statement in the same dialect, or explain why there is no fix.
pub struct SqlFixTask {
    pub dialect: SqlDialect,
    pub engine: String,
    pub statement: String,
    pub error: QueryError,
    pub schema: Option<String>,
    pub near_misses: Vec<String>,
}

fn normalize(sql: &str) -> String {
    static SPACE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\s+").unwrap());
    let t = sql.trim();
    SPACE
        .replace_all(t.strip_suffix(';').unwrap_or(t), " ")
        .trim()
        .to_owned()
}

impl AssistTask for SqlFixTask {
    type Output = SqlFix;

    fn name(&self) -> &str {
        "fix-sql"
    }

    fn system(&self) -> String {
        format!(
            "You fix SQL statements that failed in TableGrip, a desktop database client.\n\n# Rules\n\
             - Write {} SQL. Return one statement, without a trailing semicolon.\n\
             - Change as little as needed to fix the error, keeping what the statement was meant to do. Don't add filters, limits, joins or columns it didn't ask for.\n\
             - Never turn a statement that only reads into one that changes data or schema.\n\
             - Use only tables and columns listed under Schema; never invent a name. If the statement can't work against this schema, return \"sql\": null and say why.\n\
             - Don't run anything: you can't, and TableGrip only applies the fix after the user reviews it.",
            self.engine
        )
    }

    fn prompt(&self) -> String {
        let mut out = format!(
            "This statement failed:\n```sql\n{}\n```\n\n",
            self.statement.trim()
        );
        out.push_str(&format!("Error: {}\n", self.error.message.trim()));
        if let Some(s) = &self.error.sql_state {
            out.push_str(&format!("SQLSTATE: {s}\n"));
        }
        if let Some(v) = self.error.vendor_code {
            out.push_str(&format!("Vendor error code: {v}\n"));
        }
        let units: Vec<u16> = self.statement.encode_utf16().collect();
        if let Some(at) = self.error.position.filter(|p| *p <= units.len()) {
            let before = String::from_utf16_lossy(&units[..at]);
            let after = String::from_utf16_lossy(&units[at..]);
            let line = before.matches('\n').count() + 1;
            let near: String = after.chars().take(40).collect();
            out.push_str(&format!(
                "The engine points at line {line}, near: {}\n",
                near.lines().next().unwrap_or("")
            ));
        }
        if !self.near_misses.is_empty() {
            out.push_str(&format!(
                "Existing names close to the unknown one: {}\n",
                self.near_misses.join(", ")
            ));
        }
        out.push_str("\n# Schema\n");
        out.push_str(
            self.schema.as_deref().unwrap_or(
                "Not shared. Fix only what the error and the statement themselves show.",
            ),
        );
        out.push_str("\n\nReply with only this JSON object:\n");
        out.push_str("```json\n{\"sql\": \"the corrected statement, or null if it can't be fixed\", \"explanation\": \"one or two short sentences: what was wrong and what changed\"}\n```");
        out
    }

    fn parse(&self, json: &JsonObject) -> Result<SqlFix, AssistInvalid> {
        let explanation = require_text(json, "explanation")?;
        if matches!(json.get("sql"), None | Some(Value::Null)) {
            return Ok(SqlFix {
                sql: None,
                explanation,
            });
        }
        let sql = require_text(json, "sql")?;
        let sql = sql.trim();
        let sql = sql.strip_suffix(';').unwrap_or(sql).trim().to_owned();
        let statements = SqlSplitter::new(self.dialect.syntax()).split(&sql);
        if statements.len() != 1 {
            return Err(AssistInvalid::one(format!(
                "\"sql\" must be exactly one statement; it has {}.",
                statements.len()
            )));
        }
        if normalize(&sql) == normalize(&self.statement) {
            return Err(AssistInvalid::one(
                "\"sql\" is the same statement that failed; change what the error points at, or return null.",
            ));
        }
        Ok(SqlFix {
            sql: Some(sql),
            explanation,
        })
    }
}
