//! Helpers shared by the engines' `StructureReader` ports (`metadata/StructureReader.kt`).

use tg_sql::SqlDialect;
use tg_sql::model::{
    ConstraintInfo, ConstraintKind, ForeignKeyInfo, TableDetails, TableRef, TriggerInfo,
};

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

/// `GenericStructure.constraints`: the primary key (unnamed) and the foreign keys, from metadata.
pub fn generic_constraints(dialect: SqlDialect, details: &TableDetails) -> Vec<ConstraintInfo> {
    let mut out = Vec::new();
    let primary_key: Vec<String> = details
        .primary_key()
        .into_iter()
        .map(str::to_owned)
        .collect();
    if !primary_key.is_empty() {
        out.push(ConstraintInfo {
            name: String::new(),
            kind: ConstraintKind::PrimaryKey,
            definition: format!("PRIMARY KEY {}", column_list(dialect, &primary_key)),
            columns: primary_key,
        });
    }
    for fk in &details.foreign_keys {
        out.push(ConstraintInfo {
            name: fk.name.clone(),
            kind: ConstraintKind::ForeignKey,
            columns: fk.columns.clone(),
            definition: foreign_key_definition(dialect, &details.table, fk),
        });
    }
    out
}

/// What `GenericStructure`'s rebuilt DDL says it leaves out.
pub const REBUILT_NOTE: &str = "-- Rebuilt from JDBC metadata; details such as CHECK constraints and triggers aren't included.\n";

/// `GenericStructure.ddl`: CREATE TABLE rebuilt from the columns and constraints, then the indexes that don't back
/// the primary key, then the triggers, after `note`.
pub fn rebuilt_ddl(
    dialect: SqlDialect,
    details: &TableDetails,
    constraints: &[ConstraintInfo],
    triggers: &[TriggerInfo],
    note: &str,
) -> String {
    use tg_sql::model::jdbc_types as t;
    let sized = [
        t::CHAR,
        t::VARCHAR,
        t::NCHAR,
        t::NVARCHAR,
        t::BINARY,
        t::VARBINARY,
    ];
    let mut body: Vec<String> = details
        .columns
        .iter()
        .map(|c| {
            let mut s = format!("{} {}", dialect.quote_identifier(&c.name), c.type_name);
            if let Some(size) = c.size.filter(|_| sized.contains(&c.jdbc_type)) {
                s += &format!("({size})");
            }
            if let Some(d) = c.default_value.as_ref().filter(|_| !c.auto_increment) {
                s += &format!(" DEFAULT {d}");
            }
            if !c.nullable {
                s += " NOT NULL";
            }
            s
        })
        .collect();
    body.extend(constraints.iter().map(|c| {
        if c.name.is_empty() {
            c.definition.clone()
        } else {
            format!(
                "CONSTRAINT {} {}",
                dialect.quote_identifier(&c.name),
                c.definition
            )
        }
    }));
    let name = dialect.qualified_name(&details.table);
    let lines: Vec<String> = body.iter().map(|l| format!("    {l}")).collect();
    let create = format!("CREATE TABLE {name} (\n{}\n)", lines.join(",\n"));
    let primary_key: Vec<String> = details
        .primary_key()
        .into_iter()
        .map(str::to_owned)
        .collect();
    let indexes: Vec<String> = details
        .indexes
        .iter()
        .filter(|i| i.columns != primary_key)
        .map(|i| {
            format!(
                "CREATE {}INDEX {} ON {name} {}",
                if i.unique { "UNIQUE " } else { "" },
                dialect.quote_identifier(&i.name),
                column_list(dialect, &i.columns)
            )
        })
        .collect();
    let definitions: Vec<String> = triggers.iter().map(|t| t.definition.clone()).collect();
    format!("{note}{}", statements(&[&[create], &indexes, &definitions]))
}
