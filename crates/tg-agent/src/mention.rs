//! Mentions (`agent/Mention.kt`): something the user pointed the agent at from elsewhere in the app, copied when it
//! was mentioned, and text that carries them as single private-use characters.

use std::collections::{BTreeMap, HashMap};
use std::ops::RangeInclusive;
use tg_data::{CopyFormat, ResultFormats};
use tg_sql::model::ResultColumn;

/// Rows of a [`Mention::Cells`] that are kept and sent, and how much of each value.
pub const CELL_ROWS: usize = 50;
pub const CELL_BUDGET: usize = 200;
/// Most of a mention the agent reads, so one huge selection can't crowd out the conversation.
pub const BUDGET: usize = 8_000;
pub const MESSAGE_BUDGET: usize = 2_000;

/// The first `n` UTF-16 units of `text`, as Kotlin's `take`.
fn take_utf16(text: &str, n: usize) -> &str {
    let mut units = 0;
    for (i, c) in text.char_indices() {
        units += c.len_utf16();
        if units > n {
            return &text[..i];
        }
    }
    text
}

fn len_utf16(text: &str) -> usize {
    text.encode_utf16().count()
}

pub fn capped(text: &str, budget: usize) -> String {
    let length = len_utf16(text);
    if length <= budget {
        text.to_owned()
    } else {
        format!(
            "{}\n… ({} more characters not shown)",
            take_utf16(text, budget),
            length - budget
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Mention {
    /// A statement or selection from a console, as `lines` (1-based) of `source`.
    Sql {
        source: String,
        lines: RangeInclusive<u32>,
        engine: String,
        sql: String,
    },
    /// A statement that failed in `source`, with what the database said.
    Error {
        source: String,
        engine: String,
        sql: String,
        message: String,
    },
    /// A table or view; its structure (`details`) is read when the message is sent.
    Table {
        connection_id: String,
        connection: String,
        engine: String,
        namespace: Option<String>,
        name: String,
        details: Option<String>,
    },
    Column {
        connection: String,
        engine: String,
        table: String,
        name: String,
        type_name: String,
        primary_key: bool,
    },
    /// A saved query, by its name in the library.
    Query { name: String, sql: String },
    /// Cells selected in a grid: `columns` and the 1-based `rows` of `source`, counted over what `origin` returns.
    /// `values` is None when the connection doesn't share data. `keys` are primary key columns that weren't selected,
    /// with `key_values` per row. `notes` flag rows whose values are edits not saved yet.
    Cells {
        source: String,
        engine: String,
        columns: Vec<ResultColumn>,
        rows: RangeInclusive<u32>,
        values: Option<Vec<Vec<Option<String>>>>,
        origin: Option<String>,
        keys: Vec<ResultColumn>,
        key_values: Option<Vec<Vec<Option<String>>>>,
        notes: BTreeMap<u32, String>,
    },
    /// A mention in a chat reopened from history: what it read as and what the agent read for it.
    Saved { label: String, context: String },
}

/// A cell of a Cells mention, cut to the budget; a text `NULL` is quoted so it can't pass for a missing value.
fn cell(value: &Option<String>) -> Option<String> {
    match value.as_deref() {
        None => None,
        Some("NULL") => Some("\"NULL\"".into()),
        Some(v) if len_utf16(v) > CELL_BUDGET => Some(format!("{}…", take_utf16(v, CELL_BUDGET))),
        Some(v) => Some(v.to_owned()),
    }
}

impl Mention {
    /// How the mention reads in the chat, without the `@`.
    pub fn label(&self) -> String {
        match self {
            Self::Sql { source, lines, .. } => {
                if lines.start() == lines.end() {
                    format!("{source}:{}", lines.start())
                } else {
                    format!("{source}:{}-{}", lines.start(), lines.end())
                }
            }
            Self::Error { source, .. } => format!("{source} error"),
            Self::Table { name, .. } => name.clone(),
            Self::Column { table, name, .. } => format!("{table}.{name}"),
            Self::Query { name, .. } => name.clone(),
            Self::Cells {
                source,
                columns,
                rows,
                ..
            } => {
                let place = if rows.start() == rows.end() {
                    format!("row {}", rows.start())
                } else {
                    format!("rows {}-{}", rows.start(), rows.end())
                };
                match columns.as_slice() {
                    [only] => format!("{source}.{} {place}", only.name),
                    _ => format!("{source} {place} · {} cols", columns.len()),
                }
            }
            Self::Saved { label, .. } => label.clone(),
        }
    }

    /// What the agent reads for it.
    pub fn context(&self) -> String {
        match self {
            Self::Sql {
                source,
                lines,
                engine,
                sql,
            } => {
                let which = if lines.start() == lines.end() {
                    format!(" {}", lines.start())
                } else {
                    format!("s {}-{}", lines.start(), lines.end())
                };
                format!(
                    "{engine} SQL from {source}, line{which}:\n```sql\n{}\n```",
                    capped(sql.trim(), BUDGET)
                )
            }
            Self::Error {
                source,
                engine,
                sql,
                message,
            } => format!(
                "This {engine} statement from {source} failed:\n```sql\n{}\n```\nError: {}",
                capped(sql.trim(), BUDGET),
                capped(message.trim(), MESSAGE_BUDGET)
            ),
            Self::Table {
                connection,
                engine,
                namespace,
                name,
                details,
                ..
            } => {
                let qualified = namespace
                    .iter()
                    .chain(std::iter::once(name))
                    .cloned()
                    .collect::<Vec<_>>()
                    .join(".");
                format!(
                    "{engine} table {qualified} on connection {connection}:\n{}",
                    details
                        .as_deref()
                        .map(|d| capped(d, BUDGET))
                        .unwrap_or_else(|| "(its structure couldn't be read)".into())
                )
            }
            Self::Column {
                connection,
                engine,
                table,
                name,
                type_name,
                primary_key,
            } => format!(
                "Column {table}.{name} of {engine} connection {connection}: {}{}",
                type_name.to_lowercase(),
                if *primary_key { ", primary key" } else { "" }
            ),
            Self::Query { name, sql } => format!(
                "Saved query \"{name}\":\n```sql\n{}\n```",
                capped(sql.trim(), BUDGET)
            ),
            Self::Cells {
                source,
                engine,
                columns,
                rows,
                values,
                origin,
                keys,
                key_values,
                notes,
            } => {
                let row_count = (rows.end() - rows.start() + 1) as usize;
                let mut out = format!(
                    "Cells from {source} ({engine}), {} column(s) × {row_count} row(s), rows {}-{}",
                    columns.len(),
                    rows.start(),
                    rows.end()
                );
                match origin {
                    None => out.push_str(":\n"),
                    Some(o) => out.push_str(&format!(
                        " of what this returns:\n```sql\n{}\n```\n",
                        capped(o.trim(), MESSAGE_BUDGET)
                    )),
                }
                let Some(values) = values else {
                    let shape: Vec<String> = columns
                        .iter()
                        .map(|c| format!("{} {}", c.name, c.type_name.to_lowercase()))
                        .collect();
                    out.push_str(&shape.join(" | "));
                    out.push_str(
                        "\n(Values not shared: the connection only shares its schema with agents.)",
                    );
                    return capped(&out, BUDGET);
                };
                let keyed = key_values.as_ref().filter(|_| !keys.is_empty());
                if keyed.is_some() {
                    let names: Vec<&str> = keys.iter().map(|k| k.name.as_str()).collect();
                    out.push_str(&format!(
                        "The primary key ({}) comes first to identify the rows; it wasn't selected.\n",
                        names.join(", ")
                    ));
                }
                let table: Vec<Vec<Option<String>>> = values
                    .iter()
                    .enumerate()
                    .map(|(i, row)| {
                        let key_row = keyed.and_then(|k| k.get(i)).cloned().unwrap_or_default();
                        key_row.iter().chain(row.iter()).map(cell).collect()
                    })
                    .collect();
                let all_columns: Vec<ResultColumn> = if keyed.is_some() {
                    keys.iter().chain(columns.iter()).cloned().collect()
                } else {
                    columns.clone()
                };
                out.push_str(
                    &ResultFormats::format(
                        CopyFormat::Markdown,
                        &all_columns,
                        &table,
                        true,
                        None,
                        None,
                    )
                    .unwrap_or_default(),
                );
                if row_count > values.len() {
                    out.push_str(&format!(
                        "\n({} more rows not shown)",
                        row_count - values.len()
                    ));
                }
                if !notes.is_empty() {
                    let list: Vec<String> = notes
                        .iter()
                        .map(|(row, note)| format!("row {row} ({note})"))
                        .collect();
                    out.push_str(&format!(
                        "\nNot saved to the database yet: {}",
                        list.join(", ")
                    ));
                }
                capped(&out, BUDGET)
            }
            Self::Saved { context, .. } => context.clone(),
        }
    }
}

/// The first and last characters of the private use area: each stands in for one mention.
pub const FIRST: char = '\u{e000}';
pub const LAST: char = '\u{f8ff}';

pub fn is_mention(c: char) -> bool {
    (FIRST..=LAST).contains(&c)
}

/// Text with mentions in it, each a single private-use character standing in for it, so editing treats a mention as
/// a unit. A stand-in without an entry in `mentions` is dropped.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MentionText {
    pub text: String,
    pub mentions: HashMap<char, Mention>,
}

impl MentionText {
    pub fn new(text: impl Into<String>, mentions: HashMap<char, Mention>) -> Self {
        Self {
            text: text.into(),
            mentions,
        }
    }

    /// The mentions the text still contains, in the order they first appear.
    pub fn used(&self) -> Vec<(char, &Mention)> {
        let mut seen = Vec::new();
        for c in self.text.chars().filter(|c| is_mention(*c)) {
            if !seen.contains(&c) {
                seen.push(c);
            }
        }
        seen.into_iter()
            .filter_map(|c| self.mentions.get(&c).map(|m| (c, m)))
            .collect()
    }

    /// As the user reads it: each mention as `@label`.
    pub fn plain(&self) -> String {
        self.expand(|_, m| format!("@{}", m.label()))
    }

    /// As the agent reads it: each mention as `@label [n]`, then what each one is under its number.
    pub fn prompt(&self) -> String {
        let used = self.used();
        if used.is_empty() {
            return self.plain();
        }
        let numbers: HashMap<char, usize> = used
            .iter()
            .enumerate()
            .map(|(i, (c, _))| (*c, i + 1))
            .collect();
        let mut out = self.expand(|c, m| format!("@{} [{}]", m.label(), numbers[&c]));
        out.push_str("\n\n# Mentioned\n");
        for (i, (_, m)) in used.iter().enumerate() {
            out.push_str(&format!("\n[{}] {}\n", i + 1, m.context()));
        }
        out.trim_end().to_owned()
    }

    fn expand(&self, render: impl Fn(char, &Mention) -> String) -> String {
        let mut out = String::new();
        for c in self.text.chars() {
            if !is_mention(c) {
                out.push(c);
            } else if let Some(m) = self.mentions.get(&c) {
                out.push_str(&render(c, m));
            }
        }
        out
    }
}
