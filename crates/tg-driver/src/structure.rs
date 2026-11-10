//! Helpers shared by the engines' `StructureReader` ports (`metadata/StructureReader.kt`).

use tg_sql::SqlDialect;
use tg_sql::model::{ForeignKeyInfo, TableRef};

/// `(a, b)` with each name quoted.
pub fn column_list(dialect: SqlDialect, columns: &[String]) -> String {
    let quoted: Vec<String> = columns
        .iter()
        .map(|c| dialect.quote_identifier(c))
        .collect();
    format!("({})", quoted.join(", "))
}

/// `FOREIGN KEY (a) REFERENCES t (b) ON UPDATE … ON DELETE …`, naming the target unqualified when it's in the same
/// namespace (or has none).
pub fn foreign_key_definition(
    dialect: SqlDialect,
    table: &TableRef,
    fk: &ForeignKeyInfo,
) -> String {
    let target = &fk.referenced_table;
    let empty = |s: &Option<String>| s.as_deref().is_none_or(str::is_empty);
    let same = target.namespace == table.namespace
        || (empty(&target.namespace.catalog) && empty(&target.namespace.schema));
    let mut s = format!(
        "FOREIGN KEY {} REFERENCES ",
        column_list(dialect, &fk.columns)
    );
    s.push_str(&if same {
        dialect.quote_identifier(&target.name)
    } else {
        dialect.qualified_name(target)
    });
    s.push(' ');
    s.push_str(&column_list(dialect, &fk.referenced_columns));
    if let Some(a) = &fk.on_update {
        s.push_str(" ON UPDATE ");
        s.push_str(a);
    }
    if let Some(a) = &fk.on_delete {
        s.push_str(" ON DELETE ");
        s.push_str(a);
    }
    s
}

/// Statements joined as a script: each trimmed, its own `;` dropped, then `;\n\n` between and `;` after.
pub fn statements(groups: &[&[String]]) -> String {
    let parts: Vec<&str> = groups
        .iter()
        .flat_map(|g| g.iter())
        .map(|s| {
            let t = s.trim();
            t.strip_suffix(';').unwrap_or(t)
        })
        .collect();
    format!("{};", parts.join(";\n\n"))
}
