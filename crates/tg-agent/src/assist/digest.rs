//! A schema as an agent reads it (`assist/SchemaDigest.kt`): tables and columns in a compact `table(column type, …)`
//! form, within a character budget. Only names and types, never data.

use std::collections::{HashMap, HashSet};
use tg_driver::RelationalConnection;
use tg_sql::model::{ColumnInfo, TableDetails, TableInfo};

pub struct SchemaDigest;

fn len(s: &str) -> usize {
    s.encode_utf16().count()
}

fn take(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

impl SchemaDigest {
    /// The default namespace of `database`. `focus` tables (e.g. those a statement uses) are listed first, so they
    /// keep their columns when the budget runs out.
    pub async fn read(
        database: &dyn RelationalConnection,
        configured_database: &str,
        budget: usize,
        focus: &[String],
    ) -> tg_driver::Result<String> {
        let namespaces = database.namespaces().await?;
        let names: Vec<String> = namespaces
            .iter()
            .map(|n| n.display_name().to_owned())
            .collect();
        let default = database
            .dialect()
            .default_namespace(&names, configured_database);
        let Some(namespace) = namespaces
            .iter()
            .find(|n| Some(n.display_name().to_owned()) == default)
        else {
            return Ok("The database has no schemas.".into());
        };
        let others: Vec<String> = names
            .iter()
            .filter(|n| n.as_str() != namespace.display_name())
            .cloned()
            .collect();
        let tables = database.tables(namespace).await?;
        let columns: HashMap<String, Vec<ColumnInfo>> =
            database.columns_of(namespace).await?.into_iter().collect();
        Ok(Self::describe(
            namespace.display_name(),
            &others,
            &tables,
            &columns,
            budget,
            focus,
        ))
    }

    /// One table in full: every column with its type and nullability, then its keys and indexes.
    pub fn table(details: &TableDetails) -> String {
        let mut out = String::from("Columns:\n");
        for c in &details.columns {
            out.push_str(&format!("  {} {}", c.name, c.type_name.to_lowercase()));
            if c.primary_key {
                out.push_str(" pk");
            }
            if !c.nullable {
                out.push_str(" not null");
            }
            if c.auto_increment {
                out.push_str(" auto increment");
            }
            if let Some(d) = c.default_value.as_deref().filter(|d| !d.trim().is_empty()) {
                out.push_str(" default ");
                out.push_str(&take(d, 60));
            }
            out.push('\n');
        }
        let primary_key = details.primary_key();
        if !primary_key.is_empty() {
            out.push_str(&format!("Primary key: {}\n", primary_key.join(", ")));
        }
        for key in &details.foreign_keys {
            out.push_str(&format!(
                "Foreign key: ({}) -> {}({})\n",
                key.columns.join(", "),
                key.referenced_table.name,
                key.referenced_columns.join(", ")
            ));
        }
        for index in &details.indexes {
            out.push_str(&format!(
                "{} {}: ({})\n",
                if index.unique {
                    "Unique index"
                } else {
                    "Index"
                },
                index.name,
                index.columns.join(", ")
            ));
        }
        out.trim_end().to_owned()
    }

    pub fn describe(
        namespace: &str,
        others: &[String],
        tables: &[TableInfo],
        columns: &HashMap<String, Vec<ColumnInfo>>,
        budget: usize,
        focus: &[String],
    ) -> String {
        let mut out = format!("Default schema: {namespace}");
        if !others.is_empty() {
            let shown: Vec<&str> = others.iter().take(40).map(String::as_str).collect();
            out.push_str(&format!(" (others, not described: {})", shown.join(", ")));
        }
        out.push_str(&format!(
            ". These are all of its {} tables and views, with every column:\n",
            tables.len()
        ));
        let wanted: HashSet<String> = focus.iter().map(|f| f.to_lowercase()).collect();
        let mut ordered: Vec<&TableInfo> = tables.iter().collect();
        ordered.sort_by_key(|t| {
            if wanted.contains(&t.table.name.to_lowercase()) {
                0
            } else {
                1
            }
        });
        let mut skipped = Vec::new();
        for table in ordered {
            let name = &table.table.name;
            let mut line = name.clone();
            if table.is_view {
                line.push_str(" [view]");
            }
            let list: Vec<String> = columns
                .get(name)
                .map(|cs| {
                    cs.iter()
                        .map(|c| {
                            format!(
                                "{} {}{}",
                                c.name,
                                c.type_name.to_lowercase(),
                                if c.primary_key { " pk" } else { "" }
                            )
                        })
                        .collect()
                })
                .unwrap_or_default();
            line.push_str(&format!("({})", list.join(", ")));
            if let Some(r) = table.remarks.as_deref().filter(|r| !r.trim().is_empty()) {
                line.push_str(" -- ");
                line.push_str(&take(r, 120));
            }
            if len(&out) + len(&line) > budget {
                skipped.push(name.clone());
            } else {
                out.push_str(&line);
                out.push('\n');
            }
        }
        if !skipped.is_empty() {
            out.push_str(&format!(
                "Also these tables, columns not listed (request a lookup query when you need them): {}\n",
                skipped.join(", ")
            ));
        }
        out.trim_end().to_owned()
    }
}
