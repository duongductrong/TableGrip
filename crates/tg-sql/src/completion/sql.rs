//! Context-aware SQL completion (`SqlCompleter.kt`).

use regex::Regex;
use std::sync::LazyLock;

use super::{
    CompletionItem, CompletionKind, CompletionResult, EmptySchema, SchemaProvider, TableEntry,
    ValueContext,
};
use crate::dialect::SqlDialect;
use crate::keywords;
use crate::kt::{self, eq_ignore_case};
use crate::lexer::{SqlLexer, Token, TokenType};
use crate::splitter::SqlSplitter;

const STATEMENT_STARTS: &[&str] = &[
    "SELECT",
    "WITH",
    "INSERT INTO",
    "UPDATE",
    "DELETE FROM",
    "CREATE TABLE",
    "ALTER TABLE",
    "DROP TABLE",
    "EXPLAIN",
    "BEGIN",
    "COMMIT",
    "ROLLBACK",
];
const CLAUSE_WORDS: &[&str] = &[
    "SELECT",
    "FROM",
    "WHERE",
    "GROUP",
    "ORDER",
    "HAVING",
    "SET",
    "ON",
    "RETURNING",
    "VALUES",
    "UPDATE",
    "INTO",
    "JOIN",
    "LIMIT",
    "OFFSET",
    "TABLE",
];
/// Keywords that begin a statement, for telling statements apart that aren't separated by `;`.
const STATEMENT_KEYWORDS: &[&str] = &[
    "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP", "TRUNCATE",
    "EXPLAIN", "SHOW", "DESCRIBE", "PRAGMA", "VACUUM", "ANALYZE", "GRANT", "REVOKE", "CALL", "USE",
    "BEGIN", "COMMIT", "ROLLBACK",
];
/// A statement keyword after one of these continues the statement (`UNION\n\nSELECT`, `AS\n\nSELECT`).
const CONTINUING_WORDS: &[&str] = &[
    "UNION",
    "ALL",
    "DISTINCT",
    "INTERSECT",
    "EXCEPT",
    "AS",
    "AND",
    "OR",
    "NOT",
    "IN",
    "EXISTS",
    "THEN",
    "ELSE",
    "BEGIN",
    "EXPLAIN",
    "ANALYZE",
    "INTO",
    "TABLE",
    "VIEW",
];
const PREDICATE_KEYWORDS: &[&str] = &["AND", "OR"];
const AFTER_TABLE_KEYWORDS: &[&str] = &[
    "WHERE",
    "JOIN",
    "LEFT JOIN",
    "INNER JOIN",
    "RIGHT JOIN",
    "CROSS JOIN",
    "ON",
    "AS",
    "GROUP BY",
    "ORDER BY",
    "HAVING",
    "LIMIT",
    "SET",
    "VALUES",
];
const OPERATORS: &[&str] = &["=", "<>", ">", "<", ">=", "<="];
const EXPRESSION_KEYWORDS: &[&str] =
    &["NULL", "NOT", "EXISTS", "CASE", "TRUE", "FALSE", "DISTINCT"];
const OPERATOR_KEYWORDS: &[&str] = &[
    "AND",
    "OR",
    "IN",
    "IS NULL",
    "IS NOT NULL",
    "LIKE",
    "BETWEEN",
    "AS",
    "ASC",
    "DESC",
];
const CLAUSE_KEYWORDS: &[&str] = &[
    "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "UNION", "JOIN",
];
const COMMON_FUNCTIONS: &[&str] = &[
    "count",
    "sum",
    "avg",
    "min",
    "max",
    "coalesce",
    "lower",
    "upper",
    "length",
    "substring",
    "trim",
    "round",
    "abs",
    "cast",
    "nullif",
];
const RESERVED_WORDS: &[&str] = &[
    "SELECT", "FROM", "WHERE", "ORDER", "GROUP", "BY", "TABLE", "USER", "TO", "AND", "OR", "NOT",
    "NULL", "AS", "ON", "IN", "IS", "JOIN", "LIMIT", "CHECK", "DEFAULT", "PRIMARY", "KEY", "INDEX",
    "DESC", "ASC", "CASE", "WHEN", "END",
];
const SET_OPERATORS: &[&str] = &["UNION", "INTERSECT", "EXCEPT"];
const TABLE_INTRODUCERS: &[&str] = &["FROM", "JOIN", "UPDATE", "INTO", "TABLE"];
const NOT_ALIASES: &[&str] = &[
    "WHERE",
    "ON",
    "JOIN",
    "LEFT",
    "RIGHT",
    "INNER",
    "OUTER",
    "FULL",
    "CROSS",
    "GROUP",
    "ORDER",
    "HAVING",
    "LIMIT",
    "SET",
    "VALUES",
    "USING",
    "UNION",
    "AS",
    "NATURAL",
    "OFFSET",
    "RETURNING",
    "WINDOW",
    "SELECT",
];

static BLANK_LINE: LazyLock<Regex> = LazyLock::new(|| Regex::new("\n[ \t\r]*\n").unwrap());

fn upper(t: &Token, sql: &str) -> String {
    t.text(sql).to_uppercase()
}

fn is_word(t: &Token) -> bool {
    matches!(
        t.kind,
        TokenType::Identifier | TokenType::Keyword | TokenType::QuotedIdentifier
    )
}

fn is_name(t: Option<&Token>) -> bool {
    t.is_some_and(|t| matches!(t.kind, TokenType::Identifier | TokenType::QuotedIdentifier))
}

fn get(tokens: &[Token], i: isize) -> Option<&Token> {
    usize::try_from(i).ok().and_then(|i| tokens.get(i))
}

fn unquote(name: &str) -> String {
    let first = name.chars().next();
    if name.chars().count() >= 2
        && matches!(first, Some('"' | '`'))
        && name.chars().next_back() == first
    {
        name[1..name.len() - 1].to_owned()
    } else {
        name.to_owned()
    }
}

/// What the caret is completing.
#[derive(Clone, Debug, PartialEq)]
enum Context {
    StatementStart,
    Table,
    Join,
    AfterTable,
    InsertColumns,
    Expression,
    AfterExpression,
    /// After a complete condition or sort key (`IS NULL`, `DESC`): only connectives and clauses.
    AfterPredicate,
    /// A name is being made up (`AS |`, `LIMIT |`); nothing sensible to suggest.
    Nothing,
    Other,
    Keywords(Vec<&'static str>),
}

/// The clause keyword the end of a token list belongs to, or the unclosed `(` it sits in.
#[derive(Clone, Debug, PartialEq)]
enum Clause {
    Word(String),
    Open(usize),
}

/// A table referenced by the statement, possibly with an alias; CTEs carry their own column names.
#[derive(Clone, Debug, PartialEq)]
struct ScopeTable {
    namespace: Option<String>,
    name: String,
    alias: Option<String>,
    cte_columns: Option<Vec<String>>,
    group: i64,
    branch: i64,
}

impl ScopeTable {
    fn plain(namespace: Option<String>, name: String) -> Self {
        Self {
            namespace,
            name,
            alias: None,
            cte_columns: None,
            group: -1,
            branch: -1,
        }
    }
}

/// Tables, aliases and CTEs of one statement.
struct Scope<'a> {
    tables: Vec<ScopeTable>,
    ctes: Vec<String>,
    schema: &'a dyn SchemaProvider,
}

fn distinct_by<T: Clone, K: PartialEq>(items: Vec<T>, key: impl Fn(&T) -> K) -> Vec<T> {
    let mut keys: Vec<K> = Vec::new();
    let mut out = Vec::new();
    for item in items {
        let k = key(&item);
        if !keys.contains(&k) {
            keys.push(k);
            out.push(item);
        }
    }
    out
}

impl<'a> Scope<'a> {
    /// Only the tables a query at `offset` can see: its own and those of the queries around it, not those inside other
    /// subqueries or CTE bodies.
    fn visible_at(self, sql: &str, all: &[Token], offset: usize) -> Scope<'a> {
        // (group, branch) of every query around the offset: parenthesized groups, and UNION branches within each.
        let mut path: Vec<(i64, i64)> = vec![(-1, -1)];
        for t in all {
            if t.end > offset {
                break;
            }
            let word = upper(t, sql);
            if word == "(" {
                path.push((t.start as i64, -1));
            } else if word == ")" {
                if path.len() > 1 {
                    path.pop();
                }
            } else if SET_OPERATORS.contains(&word.as_str()) {
                let (group, _) = path.pop().unwrap();
                path.push((group, t.start as i64));
            }
        }
        let tables = self
            .tables
            .into_iter()
            .filter(|t| path.contains(&(t.group, t.branch)))
            .collect();
        Scope {
            tables: distinct_by(tables, |t: &ScopeTable| {
                t.alias.as_ref().unwrap_or(&t.name).to_lowercase()
            }),
            ctes: self.ctes,
            schema: self.schema,
        }
    }

    fn resolve(&self, name: &str) -> Option<&ScopeTable> {
        self.tables
            .iter()
            .find(|t| t.alias.as_deref().is_some_and(|a| eq_ignore_case(a, name)))
            .or_else(|| self.tables.iter().find(|t| eq_ignore_case(&t.name, name)))
    }

    fn has_column(&self, table: &ScopeTable, column: &str) -> bool {
        if let Some(cols) = &table.cte_columns {
            return cols.iter().any(|c| eq_ignore_case(c, column));
        }
        self.schema
            .columns(table.namespace.as_deref(), &table.name)
            .is_some_and(|cols| cols.iter().any(|c| eq_ignore_case(&c.name, column)))
    }

    fn parse(sql: &str, all: &[Token], schema: &'a dyn SchemaProvider) -> Scope<'a> {
        let tokens: Vec<Token> = all
            .iter()
            .filter(|t| !matches!(t.kind, TokenType::Whitespace | TokenType::Comment))
            .copied()
            .collect();
        let mut tables: Vec<ScopeTable> = Vec::new();
        let mut ctes: Vec<String> = Vec::new();
        let mut cte_columns: Vec<(String, Vec<String>)> = Vec::new();
        let name = |t: &Token| unquote_scope(t.text(sql));

        // Token indices of the open parentheses around each token, and the UNION branch within each; tables remember
        // the innermost ones (as offsets).
        let mut open: Vec<usize> = Vec::new();
        let mut branches: Vec<i64> = vec![-1];
        let group = |open: &Vec<usize>| open.last().map_or(-1, |&i| tokens[i].start as i64);
        let mut i = 0;
        while i < tokens.len() {
            let t = tokens[i];
            let word = upper(&t, sql);
            if word == "(" {
                open.push(i);
                branches.push(-1);
            }
            if SET_OPERATORS.contains(&word.as_str()) {
                branches.pop();
                branches.push(t.start as i64);
            }
            if word == ")" {
                let start = open.pop();
                if branches.len() > 1 {
                    branches.pop();
                }
                // A derived table: FROM (SELECT a, b ...) t
                if let Some(start) = start
                    && tokens
                        .get(start + 1)
                        .is_some_and(|n| upper(n, sql) == "SELECT")
                    && (get(&tokens, start as isize - 1).is_some_and(|p| {
                        matches!(upper(p, sql).as_str(), "FROM" | "JOIN" | "LATERAL")
                    }) || get(&tokens, start as isize - 1).is_some_and(|p| p.text(sql) == ",")
                        && preceded_by_from(sql, &tokens, start - 1))
                {
                    let next = tokens.get(i + 1);
                    let alias = if next.is_some_and(|n| upper(n, sql) == "AS")
                        && is_name(tokens.get(i + 2))
                    {
                        Some(name(&tokens[i + 2]))
                    } else if is_name(next)
                        && !NOT_ALIASES.contains(&upper(next.unwrap(), sql).as_str())
                    {
                        Some(name(next.unwrap()))
                    } else {
                        None
                    };
                    if let Some(alias) = alias {
                        tables.push(ScopeTable {
                            namespace: None,
                            name: alias.clone(),
                            alias: Some(alias),
                            cte_columns: Some(select_list_names(sql, &tokens, start + 1)),
                            group: group(&open),
                            branch: *branches.last().unwrap(),
                        });
                    }
                }
            }
            // WITH name [(cols)] AS (SELECT a, b ...)
            if (word == "WITH" || word == "," && !ctes.is_empty()) && is_name(tokens.get(i + 1)) {
                let cte = name(&tokens[i + 1]);
                let j = i + 2;
                if tokens.get(j).is_some_and(|t| upper(t, sql) == "AS")
                    && tokens.get(j + 1).is_some_and(|t| t.text(sql) == "(")
                {
                    ctes.push(cte.clone());
                    let columns = select_list_names(sql, &tokens, j + 2);
                    match cte_columns.iter_mut().find(|(n, _)| *n == cte) {
                        Some(entry) => entry.1 = columns,
                        None => cte_columns.push((cte, columns)),
                    }
                }
            }
            if t.kind == TokenType::Keyword && TABLE_INTRODUCERS.contains(&word.as_str())
                || word == "," && preceded_by_from(sql, &tokens, i)
            {
                let mut j = i + 1;
                let first = tokens.get(j);
                if !is_name(first) && first.is_none_or(|f| f.kind != TokenType::Keyword) {
                    i += 1;
                    continue;
                }
                let first = first.unwrap();
                if first.kind == TokenType::Keyword && upper(first, sql) != "USER" {
                    i += 1;
                    continue;
                }
                let mut namespace = None;
                let mut table_name = name(first);
                if tokens.get(j + 1).is_some_and(|t| t.text(sql) == ".") {
                    // `schema.` still being typed: not a table yet.
                    if !is_name(tokens.get(j + 2)) {
                        i += 1;
                        continue;
                    }
                    namespace = Some(table_name);
                    table_name = name(&tokens[j + 2]);
                    j += 2;
                }
                let next = tokens.get(j + 1);
                let alias =
                    if next.is_some_and(|n| upper(n, sql) == "AS") && is_name(tokens.get(j + 2)) {
                        Some(name(&tokens[j + 2]))
                    } else if is_name(next)
                        && !NOT_ALIASES.contains(&upper(next.unwrap(), sql).as_str())
                    {
                        Some(name(next.unwrap()))
                    } else {
                        None
                    };
                let columns = cte_columns
                    .iter()
                    .find(|(n, _)| *n == table_name)
                    .map(|(_, c)| c.clone());
                tables.push(ScopeTable {
                    namespace,
                    name: table_name,
                    alias,
                    cte_columns: columns,
                    group: group(&open),
                    branch: *branches.last().unwrap(),
                });
            }
            i += 1;
        }
        Scope {
            tables: distinct_by(tables, |t: &ScopeTable| {
                (
                    t.alias.as_ref().unwrap_or(&t.name).to_lowercase(),
                    t.group,
                    t.branch,
                )
            }),
            ctes,
            schema,
        }
    }
}

/// Scope's own unquoting: `"x"` and `` `x` `` by their first character, whatever the last one is.
fn unquote_scope(text: &str) -> String {
    if text.chars().count() >= 2 && matches!(text.chars().next(), Some('"' | '`')) {
        let inner = &text[1..];
        let last = inner.chars().next_back().map_or(0, char::len_utf8);
        inner[..inner.len() - last].to_owned()
    } else {
        text.to_owned()
    }
}

fn preceded_by_from(sql: &str, tokens: &[Token], index: usize) -> bool {
    let mut depth = 0;
    for k in (0..index).rev() {
        let text = tokens[k].text(sql);
        if text == ")" {
            depth += 1;
        } else if text == "(" {
            if depth == 0 {
                return false;
            }
            depth -= 1;
        } else if depth == 0 && tokens[k].kind == TokenType::Keyword {
            return upper(&tokens[k], sql) == "FROM";
        }
    }
    false
}

/// Output names of `SELECT a, b AS c, t.d` starting at `from`: a, c, d.
fn select_list_names(sql: &str, tokens: &[Token], from: usize) -> Vec<String> {
    if tokens.get(from).is_none_or(|t| upper(t, sql) != "SELECT") {
        return vec![];
    }
    let trim = |t: &Token| t.text(sql).trim_matches(['"', '`']).to_owned();
    let mut names = Vec::new();
    let mut depth = 0;
    let mut last: Option<Token> = None;
    let mut i = from + 1;
    while i < tokens.len() {
        let t = tokens[i];
        let text = t.text(sql);
        if text == "(" {
            depth += 1;
        } else if text == ")" {
            if depth == 0 {
                break;
            }
            depth -= 1;
        } else if depth == 0 && (text == "," || upper(&t, sql) == "FROM") {
            if let Some(l) = &last {
                names.push(trim(l));
            }
            if upper(&t, sql) == "FROM" {
                return names;
            }
        } else if depth == 0
            && matches!(t.kind, TokenType::Identifier | TokenType::QuotedIdentifier)
        {
            last = Some(t);
        }
        i += 1;
    }
    if let Some(l) = &last {
        names.push(trim(l));
    }
    names
}

/// Context-aware SQL completion: tables after FROM/JOIN (with join conditions from foreign keys), columns of the
/// tables and aliases in scope, `alias.` and `schema.` members, functions and keywords, ranked by how well they match.
pub struct SqlCompleter {
    dialect: SqlDialect,
    lexer: SqlLexer,
    splitter: SqlSplitter,
}

type Pending = Vec<(Option<String>, String)>;

fn add_pending(pending: &mut Pending, table: &ScopeTable) {
    let entry = (table.namespace.clone(), table.name.clone());
    if !pending.contains(&entry) {
        pending.push(entry);
    }
}

impl SqlCompleter {
    pub fn new(dialect: SqlDialect) -> Self {
        Self {
            dialect,
            lexer: SqlLexer::new(dialect.syntax()),
            splitter: SqlSplitter::new(dialect.syntax()),
        }
    }

    /// The tables and views one statement reads or writes, as (namespace, name); CTEs are left out.
    pub fn tables_in(&self, statement: &str) -> Vec<(Option<String>, String)> {
        let scope = Scope::parse(statement, &self.lexer.tokenize(statement), &EmptySchema);
        let tables: Vec<(Option<String>, String)> = scope
            .tables
            .iter()
            .filter(|t| {
                t.cte_columns.is_none() && !scope.ctes.iter().any(|c| eq_ignore_case(c, &t.name))
            })
            .map(|t| (t.namespace.clone(), t.name.clone()))
            .collect();
        distinct_by(tables, |(ns, name)| {
            format!(
                "{}.{}",
                ns.as_deref()
                    .map_or_else(|| "null".to_owned(), str::to_lowercase),
                name.to_lowercase()
            )
        })
    }

    pub fn complete(
        &self,
        text: &str,
        caret: usize,
        schema: &dyn SchemaProvider,
        max_items: usize,
    ) -> CompletionResult {
        let (script_start, script_end) = self.statement_bounds(text, caret);
        let script = &text[script_start..script_end];
        let script_tokens = self.lexer.tokenize(script);
        // Statements without a delimiter, separated by a blank line, are still separate statements.
        let (sub_start, sub_end) =
            self.blank_line_bounds(script, &script_tokens, caret - script_start);
        let start = script_start + sub_start;
        let statement = &text[start..script_start + sub_end];
        let local = caret - start;
        let tokens = if sub_start == 0 && sub_end == script.len() {
            script_tokens
        } else {
            self.lexer.tokenize(statement)
        };

        // No completion inside comments or parameters (string literals are value contexts, handled below).
        let at_token = tokens.iter().find(|t| {
            local > t.start && local < t.end
                || local == t.end && t.end == statement.len() && t.kind == TokenType::Comment
        });
        if at_token.is_some_and(|t| t.kind == TokenType::Comment) {
            return CompletionResult::empty(caret);
        }
        if tokens
            .iter()
            .any(|t| t.kind == TokenType::Parameter && local > t.start && local <= t.end)
        {
            return CompletionResult::empty(caret);
        }

        // The word at the caret — also when the caret sits inside it (`u.na|me`): the typed part filters, the whole
        // word is replaced.
        let word = tokens
            .iter()
            .rfind(|t| local > t.start && local <= t.end && is_word(t));
        let in_string = tokens.iter().find(|t| {
            t.kind == TokenType::String
                && local > t.start
                && (local < t.end || !is_closed_string(statement, t))
        });
        let prefix_start = in_string.or(word).map_or(local, |t| t.start);
        let replace_end = match (in_string, word) {
            (None, Some(w))
                if w.kind != TokenType::QuotedIdentifier || is_closed_string(statement, w) =>
            {
                w.end
            }
            _ => local,
        };
        let raw_prefix = &statement[prefix_start..local];
        let prefix = raw_prefix.trim_start_matches(['"', '`', '\'', '[']);
        let before: Vec<Token> = tokens
            .iter()
            .filter(|t| {
                t.end <= prefix_start
                    && !matches!(t.kind, TokenType::Whitespace | TokenType::Comment)
            })
            .copied()
            .collect();
        // Only the tables of this (sub)query and the queries around it are in scope at the caret.
        let scope =
            Scope::parse(statement, &tokens, schema).visible_at(statement, &tokens, prefix_start);
        let mut pending: Pending = Vec::new();

        let result = |items: Vec<CompletionItem>, value: Option<ValueContext>, pending: Pending| {
            let mut items = self.rank(items, prefix);
            items.truncate(max_items);
            CompletionResult {
                replace_start: start + prefix_start,
                replace_end: start + replace_end,
                prefix: raw_prefix.to_owned(),
                items,
                value,
                pending_tables: pending,
            }
        };

        // Values: `col = 'x|`, `col IN ('a', |`, `col LIKE |`. Inside a string only values make sense; outside, the
        // right-hand side may just as well be another column (`o.user_id = u.id`), so both are offered.
        let value = self.value_context(statement, &before, &scope, in_string.is_some(), prefix);
        if in_string.is_some() {
            return match value {
                Some(value) => result(vec![], Some(value), pending),
                None => CompletionResult::empty(caret),
            };
        }

        // Qualified: `alias.|`, `schema.|`, `schema.table.|`.
        let qualifier = qualifier_before(statement, &before, prefix_start);
        if !qualifier.is_empty() {
            let items = self.member_items(&qualifier, &scope, schema, &mut pending);
            return result(items, None, pending);
        }

        let context = self.context_of(statement, &before);
        let items: Vec<CompletionItem> = match &context {
            Context::StatementStart => keyword_items(STATEMENT_STARTS),
            Context::Table => self.table_items(schema, &scope),
            Context::Join => {
                let mut items = self.join_items(schema, &scope, &mut pending);
                items.extend(self.table_items(schema, &scope));
                items
            }
            Context::AfterTable => keyword_items(AFTER_TABLE_KEYWORDS),
            Context::InsertColumns => scope
                .tables
                .first()
                .map(|t| self.column_items(t, schema, &mut pending, false))
                .unwrap_or_default(),
            Context::Expression => {
                let mut items = Vec::new();
                for t in &scope.tables {
                    let qualify = scope.tables.len() > 1 && t.alias.is_some();
                    items.extend(self.column_items(t, schema, &mut pending, qualify));
                }
                for t in &scope.tables {
                    let (label, insert) = match &t.alias {
                        Some(a) => (a.clone(), a.clone()),
                        None => (t.name.clone(), self.quote(&t.name)),
                    };
                    let detail = t.alias.as_ref().map(|_| t.name.clone());
                    items.push(CompletionItem::new(
                        label,
                        insert,
                        CompletionKind::Alias,
                        detail,
                    ));
                }
                items.extend(self.functions());
                items.extend(keyword_items(EXPRESSION_KEYWORDS));
                items
            }
            Context::AfterExpression => {
                let mut items: Vec<CompletionItem> = OPERATORS
                    .iter()
                    .map(|o| {
                        CompletionItem::new(*o, format!("{o} "), CompletionKind::Operator, None)
                    })
                    .collect();
                items.extend(keyword_items(OPERATOR_KEYWORDS));
                items.extend(keyword_items(CLAUSE_KEYWORDS));
                items
            }
            Context::AfterPredicate => {
                let mut items = keyword_items(PREDICATE_KEYWORDS);
                items.extend(keyword_items(CLAUSE_KEYWORDS));
                items
            }
            Context::Nothing => vec![],
            Context::Other => {
                // Kotlin iterates its keyword set in insertion order: the order of the keyword list.
                keyword_items(&keywords::in_order())
            }
            Context::Keywords(words) => keyword_items(words),
        };
        // A word typed at the start of a line after a finished clause may just as well begin the next statement.
        let line_start = statement[..prefix_start].rfind('\n').map_or(0, |i| i + 1);
        let starts_line = !before.is_empty() && kt::is_blank(&statement[line_start..prefix_start]);
        let mut all = items;
        if starts_line
            && !matches!(
                context,
                Context::Keywords(_) | Context::Nothing | Context::StatementStart
            )
        {
            all.extend(keyword_items(STATEMENT_STARTS));
        }
        let all = distinct_by(all, |i: &CompletionItem| (i.kind, i.insert_text.clone()));
        result(all, value, pending)
    }

    fn context_of(&self, sql: &str, before: &[Token]) -> Context {
        let Some(last) = before.last() else {
            return Context::StatementStart;
        };
        let last_word = upper(last, sql);
        let previous = before.len().checked_sub(2).map(|i| upper(&before[i], sql));
        let first_upper = || upper(&before[0], sql);

        if last.kind == TokenType::Keyword {
            return match last_word.as_str() {
                "FROM" | "INTO" | "UPDATE" | "DESCRIBE" | "TRUNCATE" => Context::Table,
                "TABLE" => {
                    if first_upper() == "CREATE" {
                        Context::Keywords(vec!["IF NOT EXISTS"])
                    } else {
                        Context::Table
                    }
                }
                "JOIN" => Context::Join,
                "SELECT" | "WHERE" | "AND" | "OR" | "ON" | "BY" | "HAVING" | "SET" | "WHEN"
                | "THEN" | "ELSE" | "DISTINCT" | "CASE" | "RETURNING" | "LIKE" | "ILIKE"
                | "BETWEEN" => Context::Expression,
                "NOT" => {
                    if previous.as_deref() == Some("IS") {
                        Context::Keywords(vec!["NULL", "TRUE", "FALSE", "DISTINCT FROM"])
                    } else {
                        Context::Expression
                    }
                }
                "IS" => Context::Keywords(vec![
                    "NULL",
                    "NOT NULL",
                    "TRUE",
                    "FALSE",
                    "NOT",
                    "DISTINCT FROM",
                ]),
                "ORDER" | "GROUP" | "PARTITION" => Context::Keywords(vec!["BY"]),
                "LEFT" | "RIGHT" | "FULL" => Context::Keywords(vec!["JOIN", "OUTER JOIN"]),
                "INNER" | "CROSS" | "OUTER" | "NATURAL" => Context::Keywords(vec!["JOIN"]),
                "UNION" | "INTERSECT" | "EXCEPT" => Context::Keywords(vec!["SELECT", "ALL"]),
                "ALL" => {
                    if previous
                        .as_deref()
                        .is_some_and(|p| SET_OPERATORS.contains(&p))
                    {
                        Context::Keywords(vec!["SELECT"])
                    } else {
                        Context::Expression
                    }
                }
                "INSERT" => Context::Keywords(vec!["INTO"]),
                "DELETE" => Context::Keywords(vec!["FROM"]),
                "CREATE" => Context::Keywords(vec![
                    "TABLE",
                    "VIEW",
                    "INDEX",
                    "UNIQUE INDEX",
                    "SCHEMA",
                    "OR REPLACE VIEW",
                    "MATERIALIZED VIEW",
                    "TRIGGER",
                    "FUNCTION",
                ]),
                "ALTER" | "DROP" => Context::Keywords(vec![
                    "TABLE",
                    "VIEW",
                    "INDEX",
                    "SCHEMA",
                    "MATERIALIZED VIEW",
                    "TRIGGER",
                    "FUNCTION",
                ]),
                "WITH" => Context::Keywords(vec!["RECURSIVE"]),
                "NULL" | "TRUE" | "FALSE" | "ASC" | "DESC" => Context::AfterPredicate,
                "END" => Context::AfterExpression,
                "AS" | "LIMIT" | "OFFSET" | "IN" | "EXISTS" | "VALUES" | "RECURSIVE" => {
                    Context::Nothing
                }
                _ => Context::Other,
            };
        }
        if last.kind == TokenType::Operator {
            match last.text(sql) {
                "(" => {
                    // INSERT INTO t (|
                    if is_insert_column_list(sql, before, before.len() - 1) {
                        return Context::InsertColumns;
                    }
                    return Context::Expression;
                }
                "," => {
                    // Continue the clause the comma belongs to, or the parenthesized list it sits in.
                    return match enclosing_clause(sql, &before[..before.len() - 1]) {
                        Some(Clause::Open(open)) => {
                            if is_insert_column_list(sql, before, open) {
                                Context::InsertColumns
                            } else {
                                Context::Expression
                            }
                        }
                        Some(Clause::Word(w)) if w == "FROM" => Context::Table,
                        _ => Context::Expression,
                    };
                }
                ")" => {
                    // INSERT INTO t (a, b) |
                    if let Some(open) = matching_open(sql, before, before.len() - 1)
                        && is_insert_column_list(sql, before, open)
                    {
                        return Context::Keywords(vec!["VALUES", "SELECT"]);
                    }
                    return Context::AfterExpression;
                }
                // `SELECT * |`, `t.* |`: a finished select item, not a multiplication.
                "*" if matches!(previous.as_deref(), Some("SELECT" | "," | ".")) => {
                    return Context::AfterExpression;
                }
                _ => {}
            }
            return Context::Expression;
        }
        // After a name, number or literal: "FROM users |" or "WHERE id = 1 |".
        match enclosing_clause(sql, before) {
            Some(Clause::Word(w)) => match w.as_str() {
                "TABLE" => {
                    if first_upper() == "ALTER" {
                        Context::Keywords(vec![
                            "ADD COLUMN",
                            "DROP COLUMN",
                            "ALTER COLUMN",
                            "RENAME COLUMN",
                            "RENAME TO",
                            "ADD CONSTRAINT",
                        ])
                    } else {
                        Context::AfterTable
                    }
                }
                "FROM" | "JOIN" | "UPDATE" => Context::AfterTable,
                "INTO" => Context::Keywords(vec!["VALUES", "SELECT", "DEFAULT VALUES"]),
                "LIMIT" | "OFFSET" => Context::Keywords(vec!["OFFSET", "UNION", "UNION ALL"]),
                _ => Context::AfterExpression,
            },
            None | Some(Clause::Open(_)) => {
                if before.len() == 1 {
                    Context::Other
                } else {
                    Context::AfterExpression
                }
            }
        }
    }

    fn value_context(
        &self,
        sql: &str,
        before: &[Token],
        scope: &Scope,
        in_string: bool,
        prefix: &str,
    ) -> Option<ValueContext> {
        // Walk back over an `IN ('a', ` list to the IN keyword; other lists (`SET a = 'x', |`) aren't values.
        let last_index = before.len() as isize - 1;
        let mut i = last_index;
        let mut in_list = false;
        while i >= 0 {
            let t = &before[i as usize];
            let text = t.text(sql);
            if text == "(" && get(before, i - 1).is_some_and(|p| upper(p, sql) == "IN") {
                i -= 1;
                in_list = true;
                break;
            }
            if matches!(t.kind, TokenType::String | TokenType::Number) || text == "," {
                i -= 1;
                continue;
            }
            break;
        }
        if !in_list && i != last_index {
            return None;
        }
        let op = get(before, i)?;
        let op_text = upper(op, sql);
        if !["=", "<>", "!=", "IN", "LIKE", "ILIKE", ">", "<", ">=", "<="]
            .contains(&op_text.as_str())
        {
            return None;
        }
        let column_token = get(before, i - 1).filter(|t| is_name(Some(t)))?;
        let column = unquote(column_token.text(sql));
        let qualifier = get(before, i - 2)
            .filter(|t| t.text(sql) == ".")
            .and_then(|_| get(before, i - 3))
            .map(|t| unquote(t.text(sql)));
        let table = match qualifier {
            Some(q) => scope.resolve(&q),
            None => {
                if scope.tables.len() == 1 {
                    scope.tables.first()
                } else {
                    scope.tables.iter().find(|t| scope.has_column(t, &column))
                }
            }
        }?;
        if table.cte_columns.is_some() {
            return None;
        }
        Some(ValueContext {
            namespace: table.namespace.clone(),
            table: table.name.clone(),
            column,
            prefix: prefix.to_owned(),
            quoted: in_string,
        })
    }

    /// The statement being edited. One without a delimiter runs on to the next statement (or the end), so trailing
    /// whitespace after `FROM ` still belongs to it; after a `;` a new statement begins.
    fn statement_bounds(&self, text: &str, caret: usize) -> (usize, usize) {
        let statements = self.splitter.split(text);
        for (index, statement) in statements.iter().enumerate() {
            let terminated = statement.delimiter_end > statement.end;
            let end = if terminated {
                statement.end
            } else {
                statements.get(index + 1).map_or(text.len(), |s| s.start)
            };
            if (statement.start..=end).contains(&caret) {
                return (statement.start, end);
            }
        }
        let start = statements
            .iter()
            .rfind(|s| s.delimiter_end <= caret)
            .map_or(0, |s| s.delimiter_end);
        let end = statements
            .iter()
            .find(|s| s.start > caret)
            .map_or(text.len(), |s| s.start);
        (start, end.max(caret))
    }

    /// Narrows `script` to the statement around `caret` when several are written one after another without `;`, the
    /// way consoles are often used: a statement keyword (SELECT, UPDATE, …) starting a line after a blank line begins a
    /// new one, unless the text before it clearly continues (`UNION`, `AS (...)`, an operator, a CREATE ... BEGIN
    /// body).
    fn blank_line_bounds(&self, script: &str, tokens: &[Token], caret: usize) -> (usize, usize) {
        let mut start = 0;
        let mut depth = 0;
        let mut blocks = 0;
        let mut previous: Option<&Token> = None;
        let mut is_create = false;
        for t in tokens {
            if matches!(t.kind, TokenType::Whitespace | TokenType::Comment) {
                continue;
            }
            let word = upper(t, script);
            if let Some(prev) = previous
                && depth == 0
                && blocks == 0
                && t.kind == TokenType::Keyword
                && STATEMENT_KEYWORDS.contains(&word.as_str())
            {
                let blank = BLANK_LINE.find(&script[prev.end..t.start]);
                let prev_word = upper(prev, script);
                let continues = prev.kind == TokenType::Operator && prev_word != ")"
                    || CONTINUING_WORDS.contains(&prev_word.as_str())
                    || prev_word == ")" && (word == "SELECT" || word == "WITH");
                if let Some(blank) = blank
                    && !continues
                {
                    let boundary = prev.end + blank.end();
                    if caret < boundary {
                        return (start, boundary);
                    }
                    start = boundary;
                    is_create = word == "CREATE";
                }
            }
            if previous.is_none() {
                is_create = word == "CREATE";
            }
            if word == "(" {
                depth += 1;
            } else if word == ")" {
                if depth > 0 {
                    depth -= 1;
                }
            } else if is_create && (word == "BEGIN" || word == "CASE") {
                blocks += 1;
            } else if is_create && word == "END" && blocks > 0 {
                blocks -= 1;
            }
            previous = Some(t);
        }
        // The caret after a blank line that ends the text: a statement of its own is starting there.
        if let Some(prev) = previous {
            let until = caret.clamp(prev.end, script.len());
            let tail = BLANK_LINE.find_iter(&script[prev.end..until]).last();
            if let Some(tail) = tail
                && prev.end <= caret
                && blocks == 0
                && depth == 0
                && !tokens.iter().any(|t| {
                    t.start >= caret
                        && !matches!(t.kind, TokenType::Whitespace | TokenType::Comment)
                })
            {
                return (prev.end + tail.end(), script.len());
            }
        }
        (start, script.len())
    }

    fn table_items(&self, schema: &dyn SchemaProvider, scope: &Scope) -> Vec<CompletionItem> {
        let default = schema.default_namespace();
        let mut items: Vec<CompletionItem> = scope
            .ctes
            .iter()
            .map(|c| {
                CompletionItem::new(
                    c.clone(),
                    c.clone(),
                    CompletionKind::Table,
                    Some("WITH".to_owned()),
                )
            })
            .collect();
        items.extend(
            schema
                .tables(default.as_deref())
                .unwrap_or_default()
                .iter()
                .map(|t| self.table(t, None)),
        );
        items.extend(
            schema
                .namespaces()
                .into_iter()
                .filter(|n| Some(n) != default.as_ref())
                .map(|n| {
                    let insert = format!("{}.", self.quote(&n));
                    CompletionItem::new(
                        n,
                        insert,
                        CompletionKind::Schema,
                        Some("schema".to_owned()),
                    )
                }),
        );
        items
    }

    fn table(&self, entry: &TableEntry, qualified: Option<&str>) -> CompletionItem {
        let insert = format!(
            "{}{}",
            qualified.map_or_else(String::new, |q| format!("{}.", self.quote(q))),
            self.quote(&entry.name)
        );
        let kind = if entry.is_view {
            CompletionKind::View
        } else {
            CompletionKind::Table
        };
        let detail = qualified.unwrap_or(if entry.is_view { "view" } else { "table" });
        CompletionItem::new(entry.name.clone(), insert, kind, Some(detail.to_owned()))
    }

    fn member_items(
        &self,
        qualifier: &[String],
        scope: &Scope,
        schema: &dyn SchemaProvider,
        pending: &mut Pending,
    ) -> Vec<CompletionItem> {
        if qualifier.len() == 1 {
            let name = &qualifier[0];
            // An explicit alias wins, then a schema, then a table name.
            let aliased = scope
                .tables
                .iter()
                .find(|t| t.alias.as_deref().is_some_and(|a| eq_ignore_case(a, name)));
            let namespace = schema
                .namespaces()
                .into_iter()
                .find(|n| eq_ignore_case(n, name));
            if aliased.is_none()
                && let Some(namespace) = namespace
            {
                return schema
                    .tables(Some(&namespace))
                    .unwrap_or_default()
                    .iter()
                    .map(|t| self.table(t, None))
                    .collect();
            }
            if let Some(table) = aliased.or_else(|| scope.resolve(name)) {
                let mut items = self.column_items(table, schema, pending, false);
                items.push(CompletionItem::new(
                    "*",
                    "*",
                    CompletionKind::Column,
                    Some("all columns".to_owned()),
                ));
                return items;
            }
            // A table not in FROM yet: still offer its columns.
            let default = schema.default_namespace();
            if let Some(entry) = schema
                .tables(default.as_deref())
                .and_then(|ts| ts.into_iter().find(|t| eq_ignore_case(&t.name, name)))
            {
                return self.column_items(
                    &ScopeTable::plain(default, entry.name),
                    schema,
                    pending,
                    false,
                );
            }
            return vec![];
        }
        let ns = &qualifier[qualifier.len() - 2];
        let table_name = &qualifier[qualifier.len() - 1];
        self.column_items(
            &ScopeTable::plain(Some(ns.clone()), table_name.clone()),
            schema,
            pending,
            false,
        )
    }

    fn column_items(
        &self,
        table: &ScopeTable,
        schema: &dyn SchemaProvider,
        pending: &mut Pending,
        qualify: bool,
    ) -> Vec<CompletionItem> {
        if let Some(cols) = &table.cte_columns {
            return cols
                .iter()
                .map(|c| {
                    CompletionItem::new(
                        c.clone(),
                        self.quote(c),
                        CompletionKind::Column,
                        Some(table.name.clone()),
                    )
                })
                .collect();
        }
        let Some(columns) = schema.columns(table.namespace.as_deref(), &table.name) else {
            add_pending(pending, table);
            return vec![];
        };
        let owner = table.alias.as_ref().unwrap_or(&table.name);
        columns
            .iter()
            .map(|column| {
                let insert = format!(
                    "{}{}",
                    if qualify {
                        format!("{}.", self.quote(owner))
                    } else {
                        String::new()
                    },
                    self.quote(&column.name)
                );
                let detail = format!(
                    "{}{} · {owner}",
                    column.type_.to_lowercase(),
                    if column.primary_key { " · PK" } else { "" }
                );
                CompletionItem::new(
                    column.name.clone(),
                    insert,
                    CompletionKind::Column,
                    Some(detail),
                )
            })
            .collect()
    }

    /// `JOIN orders o ON o.user_id = u.id` for every foreign key between an in-scope table and another one.
    fn join_items(
        &self,
        schema: &dyn SchemaProvider,
        scope: &Scope,
        pending: &mut Pending,
    ) -> Vec<CompletionItem> {
        let mut used: Vec<String> = scope
            .tables
            .iter()
            .filter_map(|t| t.alias.clone())
            .collect();
        let mut items = Vec::new();
        for in_scope in &scope.tables {
            let Some(relations) = schema.relations(in_scope.namespace.as_deref(), &in_scope.name)
            else {
                add_pending(pending, in_scope);
                continue;
            };
            for relation in relations {
                let (other, other_columns, own_columns) =
                    if eq_ignore_case(&relation.from_table, &in_scope.name) {
                        (
                            relation.to_table,
                            relation.to_columns,
                            relation.from_columns,
                        )
                    } else if eq_ignore_case(&relation.to_table, &in_scope.name) {
                        (
                            relation.from_table,
                            relation.from_columns,
                            relation.to_columns,
                        )
                    } else {
                        continue;
                    };
                if scope.tables.iter().any(|t| eq_ignore_case(&t.name, &other)) {
                    continue;
                }
                let alias = in_scope
                    .alias
                    .as_ref()
                    .map(|_| alias_for(&other, &mut used));
                let other_ref = alias.clone().unwrap_or_else(|| other.clone());
                let own_ref = in_scope.alias.as_ref().unwrap_or(&in_scope.name);
                let condition = other_columns
                    .iter()
                    .zip(&own_columns)
                    .map(|(o, s)| {
                        format!(
                            "{}.{} = {}.{}",
                            self.quote(&other_ref),
                            self.quote(o),
                            self.quote(own_ref),
                            self.quote(s)
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(" AND ");
                let text = format!(
                    "{}{} ON {condition}",
                    self.quote(&other),
                    alias.map_or_else(String::new, |a| format!(" {a}"))
                );
                items.push(CompletionItem::new(
                    text.clone(),
                    text,
                    CompletionKind::Join,
                    Some("foreign key".to_owned()),
                ));
            }
        }
        items
    }

    fn functions(&self) -> Vec<CompletionItem> {
        COMMON_FUNCTIONS
            .iter()
            .chain(self.dialect.functions())
            .map(|f| {
                CompletionItem::new(
                    format!("{f}()"),
                    format!("{f}()"),
                    CompletionKind::Function,
                    Some("function".to_owned()),
                )
                .with_caret(f.len() + 1)
            })
            .collect()
    }

    /// Prefix matches first, then word-initial matches (`uid` → user_id), then substrings; context order breaks ties.
    fn rank(&self, items: Vec<CompletionItem>, prefix: &str) -> Vec<CompletionItem> {
        if prefix.is_empty() {
            return items;
        }
        let lower = prefix.to_lowercase();
        // Typing lower-case keywords keeps them lower-case.
        let lower_keywords = prefix.chars().any(kt::is_letter) && prefix == prefix.to_lowercase();
        let mut scored: Vec<(u8, usize, CompletionItem)> = items
            .into_iter()
            .enumerate()
            .filter_map(|(index, mut item)| {
                let label = item.label.to_lowercase();
                let score = if label == lower {
                    0
                } else if label.starts_with(&lower) {
                    1
                } else if matches_word_starts(&label, &lower) {
                    2
                } else if label.split(['_', ' ', '.']).any(|w| w.starts_with(&lower)) {
                    3
                } else if lower.encode_utf16().count() >= 2 && label.contains(&lower) {
                    4
                } else {
                    return None;
                };
                if item.kind == CompletionKind::Keyword && lower_keywords {
                    item.insert_text = item.insert_text.to_lowercase();
                }
                Some((score, index, item))
            })
            .collect();
        scored.sort_by_key(|(score, index, _)| (*score, *index));
        scored.into_iter().map(|(_, _, item)| item).collect()
    }

    /// Quotes names that wouldn't survive unquoted: reserved words, odd characters, and (PostgreSQL) upper case.
    pub fn quote(&self, name: &str) -> String {
        if self.dialect.is_plain_identifier(name)
            && !RESERVED_WORDS.contains(&name.to_uppercase().as_str())
        {
            name.to_owned()
        } else {
            self.dialect.quote_identifier(name)
        }
    }
}

/// Keywords insert a trailing space, so the next word can follow straight away.
fn keyword_items<S: AsRef<str>>(words: &[S]) -> Vec<CompletionItem> {
    words
        .iter()
        .map(|w| {
            CompletionItem::new(
                w.as_ref(),
                format!("{} ", w.as_ref()),
                CompletionKind::Keyword,
                None,
            )
        })
        .collect()
}

/// The clause keyword the end of `before` belongs to, skipping parenthesized groups, or the index of the unclosed `(`
/// it sits in.
fn enclosing_clause(sql: &str, before: &[Token]) -> Option<Clause> {
    let mut depth = 0;
    for k in (0..before.len()).rev() {
        let t = &before[k];
        match t.text(sql) {
            ")" => depth += 1,
            "(" => {
                if depth == 0 {
                    return Some(Clause::Open(k));
                }
                depth -= 1;
            }
            _ => {
                let word = upper(t, sql);
                if depth == 0
                    && t.kind == TokenType::Keyword
                    && CLAUSE_WORDS.contains(&word.as_str())
                {
                    return Some(Clause::Word(word));
                }
            }
        }
    }
    None
}

/// Index of the `(` matching the `)` at `close`.
fn matching_open(sql: &str, before: &[Token], close: usize) -> Option<usize> {
    let mut depth = 0;
    for k in (0..=close).rev() {
        match before[k].text(sql) {
            ")" => depth += 1,
            "(" => {
                depth -= 1;
                if depth == 0 {
                    return Some(k);
                }
            }
            _ => {}
        }
    }
    None
}

/// Whether the `(` at `open` starts the column list of `INSERT INTO t (`.
fn is_insert_column_list(sql: &str, before: &[Token], open: usize) -> bool {
    let mut k = open as isize - 1;
    if !is_name(get(before, k)) {
        return false;
    }
    // schema.table
    while get(before, k - 1).is_some_and(|t| t.text(sql) == ".") && get(before, k - 2).is_some() {
        k -= 2;
    }
    get(before, k - 1).is_some_and(|t| upper(t, sql) == "INTO")
}

/// `a.b.|` → ["a", "b"]: the dotted names directly before the prefix; empty when it isn't qualified.
fn qualifier_before(sql: &str, before: &[Token], prefix_start: usize) -> Vec<String> {
    let mut parts = Vec::new();
    let mut end = prefix_start;
    let mut i = before.len() as isize - 1;
    while i >= 1 {
        let dot = &before[i as usize];
        let name = &before[i as usize - 1];
        let adjacent = dot.end == end && name.end == dot.start;
        let is_name = matches!(
            name.kind,
            TokenType::Identifier | TokenType::QuotedIdentifier | TokenType::Keyword
        );
        if dot.text(sql) != "." || !adjacent || !is_name {
            break;
        }
        parts.insert(0, unquote(name.text(sql)));
        end = name.start;
        i -= 2;
    }
    parts
}

fn is_closed_string(sql: &str, token: &Token) -> bool {
    let text = token.text(sql);
    let mut chars = text.chars();
    let (Some(first), Some(last)) = (chars.next(), text.chars().next_back()) else {
        return false;
    };
    let closing = if first == '[' { ']' } else { first };
    text.encode_utf16().count() >= 2 && last == closing
}

/// First letters of the words in a table name, e.g. order_items → oi; numbered if taken.
fn alias_for(table: &str, used: &mut Vec<String>) -> String {
    let base: String = table
        .split(['_', '-'])
        .filter(|w| !w.is_empty())
        .map(|w| w.chars().next().unwrap().to_lowercase().collect::<String>())
        .collect();
    let base = if base.is_empty() {
        "t".to_owned()
    } else {
        base
    };
    let mut alias = base.clone();
    let mut n = 1;
    while used.contains(&alias) || keywords::is_keyword(&alias) {
        alias = format!("{base}{n}");
        n += 1;
    }
    used.push(alias.clone());
    alias
}

/// IntelliJ-style fuzzy match: each typed character continues the current word or starts a later one, so `uid`
/// matches `user_id` and `oi` matches `order_items`. The first character must start the name.
fn matches_word_starts(label: &str, query: &str) -> bool {
    let words: Vec<Vec<char>> = label
        .split(['_', ' ', '.'])
        .filter(|w| !w.is_empty())
        .map(|w| w.chars().collect())
        .collect();
    let query: Vec<char> = query.chars().collect();
    fn matches(words: &[Vec<char>], query: &[char], word: usize, offset: usize, q: usize) -> bool {
        if q == query.len() {
            return true;
        }
        if word >= words.len() {
            return false;
        }
        let current = &words[word];
        if offset < current.len()
            && current[offset] == query[q]
            && matches(words, query, word, offset + 1, q + 1)
        {
            return true;
        }
        (word + 1..words.len())
            .any(|next| words[next][0] == query[q] && matches(words, query, next, 1, q + 1))
    }
    !words.is_empty()
        && !query.is_empty()
        && words[0][0] == query[0]
        && matches(&words, &query, 0, 1, 1)
}
