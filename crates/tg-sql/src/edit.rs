//! Row edits as parameterized statements (`edit/ChangeSet.kt`). The driver binds the parameters and checks
//! `expected_rows`; this module only builds the SQL.

use crate::SqlDialect;
use crate::model::TableRef;

/// A column value to bind; `jdbc_type` is a [`crate::model::jdbc_types`] constant used for NULLs and dialect binding.
/// With `is_default` an update sets the column back to its default instead, and `value` is ignored.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BoundValue {
    pub value: Option<String>,
    pub jdbc_type: i32,
    pub numeric: bool,
    pub is_default: bool,
}

impl BoundValue {
    pub fn new(value: Option<&str>, jdbc_type: i32, numeric: bool) -> Self {
        Self {
            value: value.map(str::to_owned),
            jdbc_type,
            numeric,
            is_default: false,
        }
    }

    /// `SET column = DEFAULT`.
    pub fn default_of(jdbc_type: i32) -> Self {
        Self {
            value: None,
            jdbc_type,
            numeric: false,
            is_default: true,
        }
    }
}

/// Columns in the order they were given (Kotlin keeps a `LinkedHashMap`).
pub type Columns = Vec<(String, BoundValue)>;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RowChange {
    /// Identifies the row by its primary key values as they were read.
    Update {
        key: Columns,
        values: Columns,
    },
    /// Columns not listed take their database default.
    Insert {
        values: Columns,
    },
    Delete {
        key: Columns,
    },
}

/// A parameterized statement; `expected_rows` guards updates and deletes against hitting the wrong number of rows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChangeStatement {
    pub sql: String,
    pub parameters: Vec<BoundValue>,
    pub expected_rows: Option<u64>,
}

impl ChangeStatement {
    /// The statement with literals inlined, for previews only.
    pub fn preview(&self, dialect: SqlDialect) -> String {
        let mut values = self.parameters.iter();
        let mut out = String::with_capacity(self.sql.len());
        for c in self.sql.chars() {
            match if c == '?' { values.next() } else { None } {
                Some(p) => out.push_str(&dialect.literal(p.value.as_deref(), p.numeric)),
                None => out.push(c),
            }
        }
        out
    }
}

/// Deletes first, then updates, then inserts, so a key freed by a delete can be reused.
pub fn statements(
    dialect: SqlDialect,
    table: &TableRef,
    changes: &[RowChange],
) -> Vec<ChangeStatement> {
    let target = dialect.qualified_name(table);
    let q = |name: &str| dialect.quote_identifier(name);
    let condition = |key: &Columns| {
        let mut parameters = Vec::new();
        let parts: Vec<String> = key
            .iter()
            .map(|(column, bound)| {
                if bound.value.is_none() {
                    format!("{} IS NULL", q(column))
                } else {
                    parameters.push(bound.clone());
                    format!("{} = ?", q(column))
                }
            })
            .collect();
        (parts.join(" AND "), parameters)
    };
    let rank = |c: &RowChange| match c {
        RowChange::Delete { .. } => 0,
        RowChange::Update { .. } => 1,
        RowChange::Insert { .. } => 2,
    };
    let mut ordered: Vec<&RowChange> = changes.iter().collect();
    ordered.sort_by_key(|c| rank(c)); // stable, like Kotlin's filterIsInstance concatenation
    ordered
        .into_iter()
        .map(|change| match change {
            RowChange::Delete { key } => {
                let (cond, parameters) = condition(key);
                ChangeStatement {
                    sql: format!("DELETE FROM {target} WHERE {cond}"),
                    parameters,
                    expected_rows: Some(1),
                }
            }
            RowChange::Update { key, values } => {
                let (cond, key_parameters) = condition(key);
                let assignments: Vec<String> = values
                    .iter()
                    .map(|(column, bound)| {
                        format!(
                            "{} = {}",
                            q(column),
                            if bound.is_default { "DEFAULT" } else { "?" }
                        )
                    })
                    .collect();
                let mut parameters: Vec<BoundValue> = values
                    .iter()
                    .filter(|(_, b)| !b.is_default)
                    .map(|(_, b)| b.clone())
                    .collect();
                parameters.extend(key_parameters);
                ChangeStatement {
                    sql: format!(
                        "UPDATE {target} SET {} WHERE {cond}",
                        assignments.join(", ")
                    ),
                    parameters,
                    expected_rows: Some(1),
                }
            }
            RowChange::Insert { values } if values.is_empty() => ChangeStatement {
                sql: format!("INSERT INTO {target} DEFAULT VALUES"),
                parameters: Vec::new(),
                expected_rows: None,
            },
            RowChange::Insert { values } => {
                let names: Vec<String> = values.iter().map(|(c, _)| q(c)).collect();
                let marks = vec!["?"; values.len()].join(", ");
                ChangeStatement {
                    sql: format!(
                        "INSERT INTO {target} ({}) VALUES ({marks})",
                        names.join(", ")
                    ),
                    parameters: values.iter().map(|(_, b)| b.clone()).collect(),
                    expected_rows: None,
                }
            }
        })
        .collect()
}
