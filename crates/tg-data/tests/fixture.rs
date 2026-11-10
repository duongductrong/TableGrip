//! The data files `ChatAndDataFileFixtureTest` recorded (`conformance/core/fixtures/datafiles`): each must read as
//! Kotlin read it (`<name>.read.json`), and writing back the same edit must give what Kotlin wrote
//! (`<name>.written.<ext>`): the same bytes for text formats, the same parts for workbooks.

use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use tg_core::connection::DataFileOptions;
use tg_data::datafile::{CellValue, ColumnKind, DataFileFormat, OutputColumn, OutputTable};

fn fixture() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../conformance/core/fixtures/datafiles")
}

/// `ChatAndDataFileFixtureTest.edited`: the table with one more row of a value per kind, its last column typed as
/// integers; an unchanged table as read.
fn edited(
    name: &str,
    columns: &[String],
    rows: Vec<Vec<Option<String>>>,
    changed: bool,
) -> OutputTable<'static> {
    let text = |v: &Option<String>| v.clone().map(CellValue::Text).unwrap_or(CellValue::Null);
    if !changed {
        let rows: Vec<Vec<CellValue>> = rows.iter().map(|r| r.iter().map(text).collect()).collect();
        return OutputTable {
            name: name.into(),
            source: Some(name.into()),
            changed: false,
            columns: columns
                .iter()
                .map(|c| OutputColumn {
                    name: c.clone(),
                    kind: ColumnKind::Text,
                })
                .collect(),
            rows: Box::new(move || Ok(rows.clone())),
        };
    }
    let values = [
        CellValue::Integer(42),
        CellValue::Real(2.5),
        CellValue::Text("new \"row\", ü\nnext".into()),
        CellValue::Null,
        CellValue::Integer(-7),
        CellValue::Real(1.0e-7),
        CellValue::Text(String::new()),
    ];
    let last = columns.len() - 1;
    let mut out: Vec<Vec<CellValue>> = rows
        .iter()
        .map(|row| {
            row.iter()
                .enumerate()
                .map(|(i, v)| match v.as_deref().map(|t| (t, t.parse::<i64>())) {
                    Some((_, Ok(n))) if i == last => CellValue::Integer(n),
                    _ => text(v),
                })
                .collect()
        })
        .collect();
    out.push(
        (0..columns.len())
            .map(|i| values[i % values.len()].clone())
            .collect(),
    );
    OutputTable {
        name: name.into(),
        source: Some(name.into()),
        changed: true,
        columns: columns
            .iter()
            .enumerate()
            .map(|(i, c)| OutputColumn {
                name: c.clone(),
                kind: if i == last {
                    ColumnKind::Integer
                } else {
                    ColumnKind::Text
                },
            })
            .collect(),
        rows: Box::new(move || Ok(out.clone())),
    }
}

fn parts(path: &Path) -> BTreeMap<String, String> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(path).unwrap()).unwrap();
    (0..zip.len())
        .map(|i| {
            let mut entry = zip.by_index(i).unwrap();
            let mut text = String::new();
            entry.read_to_string(&mut text).unwrap();
            (entry.name().unwrap().to_string(), text)
        })
        .collect()
}

#[test]
fn reads_and_writes_what_kotlin_does() {
    let out = tempfile::tempdir().unwrap();
    for name in [
        "people.csv",
        "plain.csv",
        "orders.json",
        "nested.json",
        "book.xlsx",
    ] {
        let path = fixture().join(name);
        let format = DataFileFormat::detect(&path).unwrap();
        let mut document = format.open(&path, &DataFileOptions::default()).unwrap();
        let mut read = Vec::new();
        for (i, table) in document.tables().into_iter().enumerate() {
            let rows: Vec<Vec<Option<String>>> =
                document.rows(i).unwrap().map(Result::unwrap).collect();
            read.push((table.name, table.columns, rows));
        }
        let actual = json!({
            "multiTable": document.multi_table(),
            "readOnlyReason": document.read_only_reason(),
            "notes": document.notes(),
            "tables": read.iter().map(|(n, c, r)| json!({"name": n, "columns": c, "rows": r})).collect::<Vec<_>>(),
        });
        let expected: Value = serde_json::from_str(
            &std::fs::read_to_string(fixture().join(format!("{name}.read.json"))).unwrap(),
        )
        .unwrap();
        assert_eq!(expected, actual, "{name}");

        if document.read_only_reason().is_some() {
            continue;
        }
        let (stem, extension) = name.rsplit_once('.').unwrap();
        let mut tables: Vec<OutputTable> = read
            .into_iter()
            .enumerate()
            .map(|(i, (n, c, r))| edited(&n, &c, r, i == 0))
            .collect();
        if document.multi_table() {
            tables.push(OutputTable {
                name: "Added".into(),
                source: None,
                changed: true,
                columns: vec![OutputColumn {
                    name: "n".into(),
                    kind: ColumnKind::Integer,
                }],
                rows: Box::new(|| Ok(vec![vec![CellValue::Integer(1)], vec![CellValue::Null]])),
            });
        }
        let written = format!("{stem}.written.{extension}");
        let target = out.path().join(&written);
        document.write(&target, &tables, &BTreeSet::new()).unwrap();
        if extension == "xlsx" {
            assert_eq!(
                parts(&fixture().join(&written)),
                parts(&target),
                "{written}"
            );
        } else {
            assert_eq!(
                std::fs::read(fixture().join(&written)).unwrap(),
                std::fs::read(&target).unwrap(),
                "{written}"
            );
        }
    }
}
