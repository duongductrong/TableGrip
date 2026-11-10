//! `CsvImportTest`, `JsonImportTest` and `XlsxImportTest`, ported.

use std::io::Write;
use std::path::{Path, PathBuf};
use tg_data::InferredKind;
use tg_data::importer::charset::{self, Charset};
use tg_data::importer::xlsx::{DateKind, format_kind};
use tg_data::importer::{self, ImportError, ImportFormat, ImportOptions, ImportRow};

fn file(dir: &Path, name: &str, bytes: &[u8]) -> PathBuf {
    let path = dir.join(name);
    std::fs::write(&path, bytes).unwrap();
    path
}

fn read(path: &Path, options: &ImportOptions) -> (Vec<String>, ImportOptions, Vec<ImportRow>) {
    let mut source = importer::open(path, options).unwrap();
    let rows = source
        .rows()
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    (source.columns().to_vec(), source.options().clone(), rows)
}

fn csv(text: &str) -> (Vec<String>, ImportOptions, Vec<ImportRow>) {
    let dir = tempfile::tempdir().unwrap();
    read(
        &file(dir.path(), "data.csv", text.as_bytes()),
        &ImportOptions::new(ImportFormat::Csv),
    )
}

fn values(row: &ImportRow) -> Vec<Option<&str>> {
    row.values.iter().map(Option::as_deref).collect()
}

fn read_error(path: &Path, options: &ImportOptions) -> ImportError {
    match importer::open(path, options) {
        Err(e) => e,
        Ok(mut source) => source
            .rows()
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .expect_err("expected an error"),
    }
}

#[test]
fn quotes_doubled_quotes_and_embedded_delimiters_and_newlines() {
    let (columns, _, rows) = csv(
        "id,text\r\n1,\"a,b\"\r\n2,\"say \"\"hi\"\"\"\r\n3,\"two\r\nlines\nand\rmore\"\r\n4,\"  padded  \"\r\n5,last",
    );
    assert_eq!(columns, ["id", "text"]);
    let texts: Vec<_> = rows.iter().map(|r| r.values[1].clone().unwrap()).collect();
    assert_eq!(
        texts,
        [
            "a,b",
            "say \"hi\"",
            "two\r\nlines\nand\rmore",
            "  padded  ",
            "last"
        ]
    );
    // Lines are physical: record 3 spans lines 4-7 (CRLF, LF and CR each end a line).
    assert_eq!(
        rows.iter().map(|r| r.line).collect::<Vec<_>>(),
        [2, 3, 4, 8, 9]
    );
}

#[test]
fn stray_quotes_are_tolerated_and_empty_fields_are_null_unless_quoted() {
    let (_, _, rows) = csv("a,b,c\n5\" screen,\"x\"y, \"z\" \n,\"\",\n");
    assert_eq!(
        values(&rows[0]),
        [Some("5\" screen"), Some("xy"), Some("z")]
    );
    assert_eq!(values(&rows[1]), [None, Some(""), None]);
    let dir = tempfile::tempdir().unwrap();
    let options = ImportOptions {
        empty_as_null: false,
        ..ImportOptions::new(ImportFormat::Csv)
    };
    let (_, _, keep) = read(&file(dir.path(), "k.csv", b"a,b\n,x\n"), &options);
    assert_eq!(values(&keep[0]), [Some(""), Some("x")]);
}

#[test]
fn bom_is_stripped_and_utf16_is_detected() {
    let dir = tempfile::tempdir().unwrap();
    let mut bytes = vec![0xEF, 0xBB, 0xBF];
    bytes.extend("name,city\nZoë,Hà Nội\n".as_bytes());
    let (columns, options, rows) = read(
        &file(dir.path(), "a.csv", &bytes),
        &ImportOptions::new(ImportFormat::Csv),
    );
    assert_eq!(columns, ["name", "city"]);
    assert_eq!(options.charset, Some(charset::UTF_8));
    assert_eq!(values(&rows[0]), [Some("Zoë"), Some("Hà Nội")]);

    let mut utf16 = vec![0xFF, 0xFE];
    utf16.extend(charset::encode("a;b\n1;ü\n", charset::UTF_16LE).unwrap());
    let (columns, options, rows) = read(
        &file(dir.path(), "b.csv", &utf16),
        &ImportOptions::new(ImportFormat::Csv),
    );
    assert_eq!(options.charset, Some(charset::UTF_16LE));
    assert_eq!(columns, ["a", "b"]);
    assert_eq!(values(&rows[0]), [Some("1"), Some("ü")]);
}

#[test]
fn non_utf8_falls_back_to_windows_1252() {
    let dir = tempfile::tempdir().unwrap();
    let bytes = charset::encode("name\ncafé – €5\n", charset::WINDOWS_1252).unwrap();
    let (_, options, rows) = read(
        &file(dir.path(), "a.csv", &bytes),
        &ImportOptions::new(ImportFormat::Csv),
    );
    assert_eq!(
        options.charset.map(|c: Charset| c.name()),
        Some("windows-1252")
    );
    assert_eq!(values(&rows[0]), [Some("café – €5")]);
}

#[test]
fn invalid_utf8_when_utf8_is_forced_names_the_line() {
    let dir = tempfile::tempdir().unwrap();
    let mut bytes = b"a\nok\n".to_vec();
    bytes.extend([0xC3, 0x28]);
    bytes.extend(b"\n");
    let options = ImportOptions {
        charset: Some(charset::UTF_8),
        ..ImportOptions::new(ImportFormat::Csv)
    };
    assert_eq!(
        read_error(&file(dir.path(), "bad.csv", &bytes), &options).line,
        Some(3)
    );
}

#[test]
fn delimiter_detection_respects_quotes_and_prefers_consistency() {
    // Decimal commas: the comma split is ragged (header has one), the semicolon split is consistent.
    let (_, options, rows) = csv("name;price\n\"a;b\";1,5\nc;2,25\nd;3\n");
    assert_eq!(options.delimiter, Some(';'));
    assert_eq!(values(&rows[0]), [Some("a;b"), Some("1,5")]);
    assert_eq!(csv("a\tb\tc\n1, 2\t3\t4\n").1.delimiter, Some('\t'));
    assert_eq!(csv("a|b\n1|2\n").1.delimiter, Some('|'));
    assert_eq!(csv("a,b\n\"x;y;z\",2\n").1.delimiter, Some(','));
    let (columns, _, rows) = csv("only\nvalue one\nvalue two\n");
    assert_eq!(columns, ["only"]);
    assert_eq!(rows.len(), 2);
}

#[test]
fn ragged_rows_are_padded_trailing_delimiters_tolerated_longer_rows_rejected() {
    let (_, _, rows) = csv("a,b,c\n1\n1,2,3,\n");
    assert_eq!(values(&rows[0]), [Some("1"), None, None]);
    assert_eq!(values(&rows[1]), [Some("1"), Some("2"), Some("3")]);
    let dir = tempfile::tempdir().unwrap();
    let options = ImportOptions {
        delimiter: Some(','),
        ..ImportOptions::new(ImportFormat::Csv)
    };
    let error = read_error(&file(dir.path(), "a.csv", b"a,b\n1,2\n\n1,2,3\n"), &options);
    assert_eq!(error.line, Some(4));
    assert_eq!(
        error.message,
        "Line 4 has 3 fields but there are only 2 columns"
    );
}

#[test]
fn header_names_are_trimmed_deduplicated_and_blanks_named() {
    let (columns, _, _) = csv(" id ,Name,name,,id,column_4\n");
    assert_eq!(
        columns,
        ["id", "Name", "name_2", "column_4", "id_2", "column_4_2"]
    );
}

#[test]
fn no_header_names_columns_by_the_widest_early_record_and_skip_rows_drops_a_title() {
    let dir = tempfile::tempdir().unwrap();
    let options = ImportOptions {
        has_header: false,
        skip_rows: 2,
        ..ImportOptions::new(ImportFormat::Csv)
    };
    let (columns, _, rows) = read(
        &file(
            dir.path(),
            "a.csv",
            b"Sales report\nexported today\n1,2\n3,4,5\n",
        ),
        &options,
    );
    assert_eq!(columns, ["column_1", "column_2", "column_3"]);
    assert_eq!(values(&rows[0]), [Some("1"), Some("2"), None]);
    assert_eq!(values(&rows[1]), [Some("3"), Some("4"), Some("5")]);
    assert_eq!(rows.iter().map(|r| r.line).collect::<Vec<_>>(), [3, 4]);
}

#[test]
fn unclosed_quote_reports_where_the_record_started() {
    let dir = tempfile::tempdir().unwrap();
    let path = file(dir.path(), "a.csv", b"a,b\n1,2\n3,\"oops\n4,5\n");
    assert_eq!(
        read_error(&path, &ImportOptions::new(ImportFormat::Csv)).line,
        Some(3)
    );
}

#[test]
fn empty_and_header_only_files() {
    let (columns, _, rows) = csv("");
    assert!(columns.is_empty() && rows.is_empty());
    let (columns, _, rows) = csv("a,b\r\n");
    assert_eq!(columns, ["a", "b"]);
    assert!(rows.is_empty());
}

#[test]
fn preview_infers_types_and_rows_can_only_be_read_once() {
    let dir = tempfile::tempdir().unwrap();
    let path = file(
        dir.path(),
        "a.csv",
        b"id,zip,amount,ok,day\n1,007,1.5,true,2024-01-31\n2,010,2,false,\n",
    );
    let preview = importer::preview(&path, &ImportOptions::new(ImportFormat::Csv), 200).unwrap();
    assert_eq!(
        preview.types.iter().map(|t| t.kind).collect::<Vec<_>>(),
        [
            InferredKind::Integer,
            InferredKind::Text,
            InferredKind::Decimal,
            InferredKind::Boolean,
            InferredKind::Date
        ]
    );
    assert!(preview.types[4].nullable);
    assert!(preview.rows[1].values[4].is_none());

    let mut source = importer::open(
        &file(dir.path(), "b.csv", b"a\n1\n"),
        &ImportOptions::new(ImportFormat::Csv),
    )
    .unwrap();
    assert_eq!(source.rows().unwrap().count(), 1);
    assert!(source.rows().is_err());
}

#[test]
fn large_files_stream() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("big.csv");
    let mut out = std::io::BufWriter::new(std::fs::File::create(&path).unwrap());
    writeln!(out, "id,text").unwrap();
    for i in 0..200_000 {
        writeln!(out, "{i},\"row {i}, with comma\"").unwrap();
    }
    drop(out);
    let mut source = importer::open(&path, &ImportOptions::new(ImportFormat::Csv)).unwrap();
    let mut count = 0;
    let mut last = None;
    for row in source.rows().unwrap() {
        count += 1;
        last = Some(row.unwrap());
    }
    assert_eq!(count, 200_000);
    assert_eq!(
        values(&last.unwrap()),
        [Some("199999"), Some("row 199999, with comma")]
    );
}

#[test]
fn format_detection_by_extension() {
    assert_eq!(
        ImportFormat::detect(Path::new("a.TSV")),
        Some(ImportFormat::Csv)
    );
    assert_eq!(
        ImportFormat::detect(Path::new("a.jsonl")),
        Some(ImportFormat::Json)
    );
    assert_eq!(
        ImportFormat::detect(Path::new("a.xlsx")),
        Some(ImportFormat::Xlsx)
    );
    assert_eq!(ImportFormat::detect(Path::new("a.xls")), None);
}

// JSON

fn json(text: &str, name: &str) -> (Vec<String>, Vec<ImportRow>) {
    let dir = tempfile::tempdir().unwrap();
    let (columns, _, rows) = read(
        &file(dir.path(), name, text.as_bytes()),
        &ImportOptions::new(ImportFormat::Json),
    );
    (columns, rows)
}

fn json_error(text: &str, name: &str) -> ImportError {
    let dir = tempfile::tempdir().unwrap();
    read_error(
        &file(dir.path(), name, text.as_bytes()),
        &ImportOptions::new(ImportFormat::Json),
    )
}

#[test]
fn array_of_objects_with_the_union_of_keys() {
    let (columns, rows) = json(
        r#"[{"id": 1, "name": "Ann"}, {"id": 2, "extra": true, "name": null}, {"id": 3.50, "name": "Bo \"B\""}]"#,
        "d.json",
    );
    assert_eq!(columns, ["id", "name", "extra"]);
    assert_eq!(values(&rows[0]), [Some("1"), Some("Ann"), None]);
    assert_eq!(values(&rows[1]), [Some("2"), None, Some("true")]);
    assert_eq!(values(&rows[2]), [Some("3.50"), Some("Bo \"B\""), None]);
    assert_eq!(rows.iter().map(|r| r.line).collect::<Vec<_>>(), [1, 2, 3]);
}

#[test]
fn nested_values_become_compact_json() {
    let (_, rows) = json(r#"[ {"a": {"b": [1, 2], "c": "x"}, "d": [] } ]"#, "d.json");
    assert_eq!(
        values(&rows[0]),
        [Some(r#"{"b":[1,2],"c":"x"}"#), Some("[]")]
    );
}

#[test]
fn ndjson_with_blank_lines_and_crlf() {
    let (columns, rows) = json(
        "{\"a\":1}\r\n\r\n{\"b\":\"x\"}\r\n{\"a\":3,\"b\":\"y\"}\r\n",
        "d.ndjson",
    );
    assert_eq!(columns, ["a", "b"]);
    assert_eq!(
        rows.iter().map(values).collect::<Vec<_>>(),
        [[Some("1"), None], [None, Some("x")], [Some("3"), Some("y")]]
    );
}

#[test]
fn object_wrapping_the_rows() {
    let (columns, rows) = json(
        r#"{"meta": {"count": 2}, "data": [{"x": 1}, {"x": 2}]}"#,
        "d.json",
    );
    assert_eq!(columns, ["x"]);
    assert_eq!(
        rows.iter()
            .map(|r| r.values[0].clone().unwrap())
            .collect::<Vec<_>>(),
        ["1", "2"]
    );
    let (_, pretty) = json("{\n  \"rows\": [\n    {\"x\": 1}\n  ]\n}\n", "d.json");
    assert_eq!(values(&pretty[0]), [Some("1")]);
}

#[test]
fn a_single_object_without_an_array_is_one_record() {
    let (columns, rows) = json(r#"{"id": 1, "tags": ["a"]}"#, "d.json");
    assert_eq!(columns, ["id", "tags"]);
    assert_eq!(values(&rows[0]), [Some("1"), Some(r#"["a"]"#)]);
}

#[test]
fn scalars_in_an_array_land_in_a_value_column() {
    let (columns, rows) = json(r#"[1, "two", null]"#, "d.json");
    assert_eq!(columns, ["value"]);
    assert_eq!(
        rows.iter().map(|r| r.values[0].clone()).collect::<Vec<_>>(),
        [Some("1".into()), Some("two".into()), None]
    );
}

#[test]
fn utf16_with_bom() {
    let dir = tempfile::tempdir().unwrap();
    let mut bytes = vec![0xFE, 0xFF];
    bytes.extend(charset::encode(r#"[{"name":"Hà Nội 😀"}]"#, charset::UTF_16BE).unwrap());
    let (_, _, rows) = read(
        &file(dir.path(), "u16.json", &bytes),
        &ImportOptions::new(ImportFormat::Json),
    );
    assert_eq!(values(&rows[0]), [Some("Hà Nội 😀")]);
}

#[test]
fn json_preview_takes_columns_from_the_preview_rows_only() {
    let dir = tempfile::tempdir().unwrap();
    let path = file(
        dir.path(),
        "d.json",
        br#"[{"a": 1}, {"a": 2}, {"a": 3, "late": "x"}]"#,
    );
    let preview = importer::preview(&path, &ImportOptions::new(ImportFormat::Json), 2).unwrap();
    assert_eq!(preview.columns, ["a"]);
    assert_eq!(preview.rows.len(), 2);
    assert_eq!(preview.types[0].kind, InferredKind::Integer);
}

#[test]
fn json_errors_name_the_record_or_line() {
    assert_eq!(
        json_error(r#"[{"a": 1}, {"a": 2,}]"#, "d.json").line,
        Some(2)
    );
    assert_eq!(
        json_error("{\"a\":1}\n{\"a\":2}\n{oops}\n", "x.ndjson").line,
        Some(3)
    );
    json_error("hello", "d.json");
}

#[test]
fn empty_array_and_empty_file() {
    assert_eq!(json("[]", "d.json"), (vec![], vec![]));
    assert_eq!(json("", "d.json"), (vec![], vec![]));
}

// XLSX

const MAIN: &str = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const REL: &str = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

/// The minimal parts Excel itself writes, with sheets given as `<sheetData>` bodies in order.
fn workbook(dir: &Path, sheets: &[(&str, &str)], shared: &[&str], date1904: bool) -> PathBuf {
    let mut parts: Vec<(String, String)> = vec![
        ("[Content_Types].xml".into(), r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>"#.into()),
        ("_rels/.rels".into(), format!(r#"<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>"#)),
    ];
    let sheet_tags: String = sheets
        .iter()
        .enumerate()
        .map(|(i, (name, _))| {
            format!(
                r#"<sheet name="{name}" sheetId="{}" r:id="rIdS{}"/>"#,
                i + 1,
                i + 1
            )
        })
        .collect();
    parts.push(("xl/workbook.xml".into(), format!(
        r#"<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="{MAIN}" xmlns:r="{REL}"><workbookPr{}/><sheets>{sheet_tags}</sheets></workbook>"#,
        if date1904 { r#" date1904="1""# } else { "" }
    )));
    // Sheet targets mix relative and absolute forms, as different writers do.
    let rels: String = (0..sheets.len())
        .map(|i| {
            let target = if i == 0 {
                "worksheets/sheet1.xml".to_owned()
            } else {
                format!("/xl/worksheets/sheet{}.xml", i + 1)
            };
            format!(
                r#"<Relationship Id="rIdS{}" Type="{REL}/worksheet" Target="{target}"/>"#,
                i + 1
            )
        })
        .collect();
    parts.push(("xl/_rels/workbook.xml.rels".into(), format!(
        r#"<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{rels}<Relationship Id="rIdSt" Type="{REL}/styles" Target="styles.xml"/><Relationship Id="rIdSs" Type="{REL}/sharedStrings" Target="sharedStrings.xml"/></Relationships>"#
    )));
    parts.push(("xl/styles.xml".into(), format!(
        r#"<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="{MAIN}"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd\ hh:mm"/><numFmt numFmtId="165" formatCode="0.0&quot; days&quot;;[Red]\-0.0"/></numFmts><cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" xfId="0"/><xf numFmtId="14" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" xfId="0"/><xf numFmtId="165" xfId="0"/><xf numFmtId="20" xfId="0"/></cellXfs><dxfs count="1"><dxf><numFmt numFmtId="165" formatCode="yyyy"/></dxf></dxfs></styleSheet>"#
    )));
    let si: String = shared.iter().map(|s| format!("<si>{s}</si>")).collect();
    parts.push(("xl/sharedStrings.xml".into(), format!(
        r#"<?xml version="1.0" encoding="UTF-8"?><sst xmlns="{MAIN}" count="{0}" uniqueCount="{0}">{si}</sst>"#,
        shared.len()
    )));
    for (i, (_, data)) in sheets.iter().enumerate() {
        parts.push((format!("xl/worksheets/sheet{}.xml", i + 1), format!(
            r#"<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{MAIN}" xmlns:r="{REL}"><dimension ref="A1:Z99"/><sheetData>{data}</sheetData></worksheet>"#
        )));
    }
    let path = dir.join("book.xlsx");
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
    for (name, text) in parts {
        zip.start_file(name, zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(text.as_bytes()).unwrap();
    }
    zip.finish().unwrap();
    path
}

const STRINGS: &[&str] = &[
    "<t>name</t>",
    "<t>born</t>",
    "<t>total</t>",
    // Rich text runs, with a phonetic hint that isn't part of the text.
    r#"<r><rPr><b/></rPr><t>A</t></r><r><t xml:space="preserve">da </t></r><rPh sb="0" eb="1"><t>ignored</t></rPh>"#,
    "<t>Bob_x000D_\nSmith</t>",
    "<t></t>",
];

const PEOPLE: &str = concat!(
    r#"<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>active</t></is></c>"#,
    r#"<c r="D1" t="s"><v>2</v></c><c r="E1" t="inlineStr"><is><r><t>no</t></r><r><t>te</t></r></is></c></row>"#,
    r#"<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>36526</v></c><c r="C2" t="b"><v>1</v></c>"#,
    r#"<c r="D2"><f>1+2</f><v>3</v></c><c r="E2" s="3"><v>2.5</v></c></row>"#,
    r#"<row r="4"><c r="A4" t="s"><v>4</v></c><c r="B4" s="2"><v>36526.5</v></c><c r="C4" t="e"><v>#N/A</v></c>"#,
    r#"<c r="D4"><v>1.50</v></c><c r="H4" s="1"/></row>"#,
    r#"<row><c t="str"><f>"x"&amp;"y"</f><v>xy</v></c><c t="b"><v>0</v></c><c><v>1E-3</v></c><c><v>12345678901234567890</v></c>"#,
    r#"<c t="s"><v>5</v></c></row>"#,
);

fn xlsx(options: impl FnOnce(&mut ImportOptions)) -> ImportOptions {
    let mut o = ImportOptions::new(ImportFormat::Xlsx);
    options(&mut o);
    o
}

#[test]
fn reads_strings_numbers_dates_booleans_formulas_and_sparse_rows() {
    let dir = tempfile::tempdir().unwrap();
    let other = r#"<row r="1"><c r="A1" t="inlineStr"><is><t>k</t></is></c></row><row r="2"><c r="A2"><v>1</v></c></row>"#;
    let path = workbook(
        dir.path(),
        &[("People", PEOPLE), ("Other", other)],
        STRINGS,
        false,
    );
    let mut source = importer::open(&path, &xlsx(|_| {})).unwrap();
    assert_eq!(source.sheets(), ["People", "Other"]);
    assert_eq!(source.options().sheet.as_deref(), Some("People"));
    assert_eq!(
        source.columns(),
        ["name", "born", "active", "total", "note"]
    );
    let rows: Vec<ImportRow> = source.rows().unwrap().collect::<Result<_, _>>().unwrap();
    assert_eq!(rows.iter().map(|r| r.line).collect::<Vec<_>>(), [2, 4, 5]);
    assert_eq!(
        values(&rows[0]),
        [
            Some("Ada "),
            Some("2000-01-01"),
            Some("true"),
            Some("3"),
            Some("2.5")
        ]
    );
    assert_eq!(
        values(&rows[1]),
        [
            Some("Bob\r\nSmith"),
            Some("2000-01-01 12:00:00"),
            None,
            Some("1.5"),
            None
        ]
    );
    assert_eq!(
        values(&rows[2]),
        [
            Some("xy"),
            Some("false"),
            Some("0.001"),
            Some("12345678901234567890"),
            None
        ]
    );
}

#[test]
fn second_sheet_no_header_and_skip_rows() {
    let dir = tempfile::tempdir().unwrap();
    let other = r#"<row r="1"><c r="A1" t="inlineStr"><is><t>title</t></is></c></row><row r="2"><c r="A2"><v>1</v></c><c r="C2"><v>3</v></c></row>"#;
    let path = workbook(dir.path(), &[("A", PEOPLE), ("Other", other)], &[], false);
    let (columns, _, rows) = read(
        &path,
        &xlsx(|o| {
            o.sheet = Some("Other".into());
            o.has_header = false;
            o.skip_rows = 1;
        }),
    );
    assert_eq!(columns, ["column_1", "column_2", "column_3"]);
    assert_eq!(
        rows.iter().map(values).collect::<Vec<_>>(),
        [[Some("1"), None, Some("3")]]
    );
    let missing = read_error(&path, &xlsx(|o| o.sheet = Some("Nope".into())));
    assert_eq!(
        missing.message,
        "There's no sheet named \"Nope\"; the workbook has \"A\", \"Other\""
    );
}

#[test]
fn data_wider_than_the_header_gets_generated_names_and_preview_sizes_from_its_rows() {
    let dir = tempfile::tempdir().unwrap();
    let data = concat!(
        r#"<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c></row><row r="2"><c r="A2"><v>1</v></c><c r="B2"><v>2</v></c></row>"#,
        r#"<row r="3"><c r="D3"><v>4</v></c></row>"#
    );
    let path = workbook(dir.path(), &[("S", data)], &[], false);
    assert_eq!(
        read(&path, &xlsx(|_| {})).0,
        ["a", "column_2", "column_3", "column_4"]
    );
    let preview = importer::preview(&path, &xlsx(|_| {}), 1).unwrap();
    assert_eq!(preview.columns, ["a", "column_2"]);
    assert_eq!(values(&preview.rows[0]), [Some("1"), Some("2")]);
    assert_eq!(preview.sheets, ["S"]);
}

#[test]
fn time_formats_and_the_1904_date_system() {
    let dir = tempfile::tempdir().unwrap();
    let data = r#"<row r="1"><c r="A1" s="4"><v>0.75</v></c><c r="B1" s="1"><v>0</v></c><c r="C1" s="1"><v>59</v></c><c r="D1" s="1"><v>61</v></c></row>"#;
    let (_, _, rows) = read(
        &workbook(dir.path(), &[("S", data)], &[], false),
        &xlsx(|o| o.has_header = false),
    );
    assert_eq!(
        values(&rows[0]),
        [
            Some("18:00:00"),
            Some("1899-12-31"),
            Some("1900-02-28"),
            Some("1900-03-01")
        ]
    );
    let (_, _, rows) = read(
        &workbook(dir.path(), &[("S", data)], &[], true),
        &xlsx(|o| o.has_header = false),
    );
    assert_eq!(rows[0].values[1].as_deref(), Some("1904-01-01"));
}

#[test]
fn empty_cells_can_be_kept_as_empty_strings() {
    let dir = tempfile::tempdir().unwrap();
    let data = r#"<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c><c r="B1" t="inlineStr"><is><t>b</t></is></c></row><row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2"><v>1</v></c></row>"#;
    let path = workbook(dir.path(), &[("S", data)], &["<t></t>"], false);
    assert_eq!(
        values(&read(&path, &xlsx(|o| o.empty_as_null = false)).2[0]),
        [Some(""), Some("1")]
    );
    assert!(read(&path, &xlsx(|_| {})).2[0].values[0].is_none());
}

#[test]
fn not_a_workbook() {
    let dir = tempfile::tempdir().unwrap();
    let path = file(dir.path(), "fake.xlsx", b"a,b\n1,2\n");
    assert_eq!(
        read_error(&path, &xlsx(|_| {})).message,
        "fake.xlsx isn't an .xlsx workbook (legacy .xls and password-protected workbooks aren't supported)"
    );
}

#[test]
fn custom_format_classification() {
    assert_eq!(format_kind("0.0\" days\""), None);
    assert_eq!(format_kind("#,##0;[Red]-#,##0"), None);
    assert_eq!(format_kind("General"), None);
    assert_eq!(format_kind("0.00E+00"), None);
    assert_eq!(format_kind("dd/mm/yyyy;@"), Some(DateKind::Date));
    assert_eq!(format_kind("[$-409]mmm-yy"), Some(DateKind::Date));
    assert_eq!(format_kind("[h]:mm:ss"), Some(DateKind::Time));
    assert_eq!(format_kind("m/d/yy h:mm AM/PM"), Some(DateKind::DateTime));
}
