use crate::lexer::{SqlLexer, TokenType};
use crate::syntax::SqlSyntax;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum StatementKind {
    /// Only reads data: SELECT, SHOW, EXPLAIN without ANALYZE...
    Read,
    /// Changes rows: INSERT, UPDATE, DELETE, MERGE, CALL...
    Write,
    /// Changes schema or permissions: CREATE, ALTER, DROP, TRUNCATE, GRANT...
    Ddl,
    /// BEGIN, COMMIT, ROLLBACK, SAVEPOINT...
    Transaction,
    /// Session settings and anything unrecognized: SET, USE, PRAGMA...
    Other,
}

impl StatementKind {
    /// The Kotlin enum name.
    pub fn name(self) -> &'static str {
        match self {
            Self::Read => "Read",
            Self::Write => "Write",
            Self::Ddl => "Ddl",
            Self::Transaction => "Transaction",
            Self::Other => "Other",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatementInfo {
    pub kind: StatementKind,
    pub verb: String,
    pub danger: Option<String>,
}

impl StatementInfo {
    pub fn changes_data(&self) -> bool {
        matches!(self.kind, StatementKind::Write | StatementKind::Ddl)
    }
}

const READ_VERBS: &[&str] = &[
    "SELECT", "SHOW", "EXPLAIN", "DESCRIBE", "DESC", "VALUES", "TABLE",
];
const WRITE_VERBS: &[&str] = &[
    "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT", "CALL", "COPY", "LOAD", "EXEC",
    "EXECUTE", "DO",
];
const DDL_VERBS: &[&str] = &[
    "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT", "REVOKE", "COMMENT", "REINDEX",
    "VACUUM", "CLUSTER",
];
const TRANSACTION_VERBS: &[&str] = &[
    "BEGIN",
    "START",
    "COMMIT",
    "END",
    "ROLLBACK",
    "SAVEPOINT",
    "RELEASE",
];
const MAIN_VERBS: &[&str] = &["SELECT", "INSERT", "UPDATE", "DELETE", "MERGE"];
const DATA_VERBS: &[&str] = &["INSERT", "UPDATE", "DELETE", "MERGE"];

/// Classifies statements so the UI can enforce read-only connections and ask before risky ones.
#[derive(Clone, Copy, Debug)]
pub struct SqlClassifier {
    lexer: SqlLexer,
}

impl SqlClassifier {
    pub fn new(syntax: SqlSyntax) -> Self {
        Self {
            lexer: SqlLexer::new(syntax),
        }
    }

    pub fn classify(&self, sql: &str) -> StatementInfo {
        let words: Vec<String> = self
            .lexer
            .tokenize(sql)
            .into_iter()
            .filter(|t| !matches!(t.kind, TokenType::Whitespace | TokenType::Comment))
            .map(|t| match t.kind {
                TokenType::Keyword | TokenType::Identifier => t.text(sql).to_uppercase(),
                _ => t.text(sql).to_owned(),
            })
            .collect();
        if words.is_empty() {
            return StatementInfo {
                kind: StatementKind::Other,
                verb: String::new(),
                danger: None,
            };
        }
        let has = |w: &str| words.iter().any(|x| x == w);

        // For WITH ... AS (...) <verb>, the verb is the first keyword outside the CTE parentheses.
        let verb_index = if words[0] == "WITH" {
            top_level_index_of(&words, MAIN_VERBS, 1).unwrap_or(0)
        } else {
            0
        };
        let verb = words[verb_index].as_str();
        let kind = if READ_VERBS.contains(&verb) {
            // EXPLAIN ANALYZE runs the statement; a PostgreSQL CTE can modify data even under a SELECT.
            if verb == "EXPLAIN" && has("ANALYZE")
                || words[0] == "WITH" && words.iter().any(|w| DATA_VERBS.contains(&w.as_str()))
            {
                StatementKind::Write
            } else {
                StatementKind::Read
            }
        } else if WRITE_VERBS.contains(&verb) {
            StatementKind::Write
        } else if DDL_VERBS.contains(&verb) {
            StatementKind::Ddl
        } else if TRANSACTION_VERBS.contains(&verb) {
            StatementKind::Transaction
        } else {
            StatementKind::Other
        };
        let has_where = top_level_index_of(&words, &["WHERE"], verb_index).is_some();
        let danger = if (verb == "DELETE" || verb == "UPDATE") && !has_where {
            Some(format!("{verb} without WHERE affects every row"))
        } else if verb == "DROP" {
            let what = words
                .get(verb_index + 1)
                .map_or_else(|| "an object".to_owned(), |w| w.to_lowercase());
            Some(format!("DROP permanently removes {what}"))
        } else if verb == "TRUNCATE" {
            Some("TRUNCATE removes every row".to_owned())
        } else if verb == "ALTER" && words[verb_index..].iter().any(|w| w == "DROP") {
            Some("ALTER … DROP removes part of a schema object".to_owned())
        } else {
            None
        };
        StatementInfo {
            kind,
            verb: verb.to_owned(),
            danger,
        }
    }
}

fn top_level_index_of(words: &[String], targets: &[&str], from: usize) -> Option<usize> {
    let mut depth = 0;
    for (i, w) in words.iter().enumerate().skip(from) {
        match w.as_str() {
            "(" => depth += 1,
            ")" => depth -= 1,
            w if targets.contains(&w) && depth == 0 => return Some(i),
            _ => {}
        }
    }
    None
}
