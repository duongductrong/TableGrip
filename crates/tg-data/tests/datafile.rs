//! `DataFileStoreTest`, ported: CSV, Excel and JSON files opened as connections, edited through SQL and written back.

use std::collections::HashMap;
use std::io::{Read, Write};

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tg_core::connection::{ConnectionConfig, DataFileOptions};
use tg_core::paths::TableGripPaths;
use tg_data::datafile::xlsx::{column_letters, excel_serial};
use tg_data::datafile::{DataFileConnection, DataFileState, DataFileStore, DataValues, HeldReason};
use tg_driver::{ExecOptions, QueryResult, RelationalConnection};
use tg_sql::model::{DatabaseType, TableRef};

struct Env {
    dir: tempfile::TempDir,
    paths: TableGripPaths,
}

fn env() -> Env {
    let dir = tempfile::tempdir().unwrap();
    let paths = TableGripPaths::new(dir.path().join("home"));
    Env { dir, paths }
}

impl Env {
    fn file(&self, name: &str) -> PathBuf {
        self.dir.path().join(name)
    }

    fn config(
        &self,
        file: &Path,
        db_type: DatabaseType,
        options: Option<DataFileOptions>,
        read_only: bool,
    ) -> ConnectionConfig {
        let id = format!("{:?}", std::time::Instant::now())
            .replace(|c: char| !c.is_ascii_alphanumeric(), "");
        let mut config =
            ConnectionConfig::new(&id, &file.file_name().unwrap().to_string_lossy(), db_type);
        config.host = String::new();
        config.database = file.to_string_lossy().into_owned();
        config.data_file = options;
        config.read_only = read_only;
        config
    }

    async fn open(&self, file: &Path, db_type: DatabaseType) -> Arc<DataFileConnection> {
        self.open_with(file, db_type, None, false).await
    }

    async fn open_with(
        &self,
        file: &Path,
        db_type: DatabaseType,
        options: Option<DataFileOptions>,
        read_only: bool,
    ) -> Arc<DataFileConnection> {
        DataFileStore::connect(&self.config(file, db_type, options, read_only), &self.paths)
            .await
            .unwrap()
    }
}

async fn sql(c: &DataFileConnection, sql: &str) -> Result<QueryResult, tg_driver::DriverError> {
    c.open_session()
        .await?
        .execute(sql, ExecOptions::default())
        .await
}

async fn rows(c: &DataFileConnection, query: &str) -> Vec<Vec<Option<String>>> {
    match sql(c, query).await.unwrap() {
        QueryResult::Rows(r) => r.rows,
        other => panic!("{other:?}"),
    }
}

fn row(values: &[&str]) -> Vec<Option<String>> {
    values.iter().map(|v| Some((*v).to_owned())).collect()
}

async fn save(c: &DataFileConnection, force: bool) {
    let sync = c.sync.clone();
    tokio::task::spawn_blocking(move || sync.save(force))
        .await
        .unwrap();
}

async fn table_names(c: &DataFileConnection) -> Vec<String> {
    let ns = c.namespaces().await.unwrap().remove(0);
    c.tables(&ns)
        .await
        .unwrap()
        .into_iter()
        .map(|t| t.table.name)
        .collect()
}

async fn close(c: Arc<DataFileConnection>) {
    tokio::task::spawn_blocking(move || drop(c)).await.unwrap();
}

async fn eventually(what: &str, mut check: impl FnMut() -> bool) {
    for _ in 0..100 {
        if check() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("timed out waiting for {what}");
}

// CSV

#[tokio::test(flavor = "multi_thread")]
async fn a_csv_keeps_its_delimiter_encoding_line_endings_and_text_when_a_cell_changes() {
    let env = env();
    let file = env.file("orders.csv");
    let original = "id;code;price;note\r\n1;007;1.50;\"semi;colon\"\r\n2;12;3;\r\n3;;2.25;\"\"\r\n";
    std::fs::write(&file, original).unwrap();
    let c = env.open(&file, DatabaseType::CSV_FILE).await;
    assert_eq!(table_names(&c).await, ["orders"]);
    // "007" and "1.50" don't read back the same as numbers, so their columns stay text; id is a number.
    let types: HashMap<_, _> = rows(&c, "SELECT name, type FROM pragma_table_info('orders')")
        .await
        .into_iter()
        .map(|r| (r[0].clone().unwrap(), r[1].clone().unwrap()))
        .collect();
    let expected: HashMap<String, String> = [
        ("_row", "INTEGER"),
        ("id", "INTEGER"),
        ("code", "TEXT"),
        ("price", "TEXT"),
        ("note", "TEXT"),
    ]
    .into_iter()
    .map(|(a, b)| (a.into(), b.into()))
    .collect();
    assert_eq!(types, expected);
    save(&c, false).await;
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        original,
        "an unchanged table isn't written"
    );
    sql(&c, "UPDATE orders SET note = 'café' WHERE id = 2")
        .await
        .unwrap();
    save(&c, false).await;
    assert!(
        matches!(c.sync.state(), DataFileState::InSync { .. }),
        "{:?}",
        c.sync.state()
    );
    close(c).await;
    let text = std::fs::read_to_string(&file).unwrap();
    assert_eq!(
        text,
        "id;code;price;note\r\n1;007;1.50;\"semi;colon\"\r\n2;12;3;café\r\n3;;2.25;\"\"\r\n"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn rows_added_edited_and_deleted_through_sql_are_written_in_order_without_the_row_key() {
    let env = env();
    let file = env.file("people.csv");
    std::fs::write(&file, "name,age\nAda,36\nBob,41\n").unwrap();
    let c = env.open(&file, DatabaseType::CSV_FILE).await;
    sql(&c, "INSERT INTO people (name, age) VALUES ('Cy', 7)")
        .await
        .unwrap();
    sql(&c, "DELETE FROM people WHERE name = 'Ada'")
        .await
        .unwrap();
    sql(&c, "UPDATE people SET age = age + 1 WHERE name = 'Bob'")
        .await
        .unwrap();
    save(&c, false).await;
    close(c).await;
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        "name,age\nBob,42\nCy,7\n"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn console_changes_are_saved_by_polling_and_a_read_only_profile_never_writes() {
    let env = env();
    let file = env.file("poll.csv");
    std::fs::write(&file, "a\n1\n").unwrap();
    let c = env.open(&file, DatabaseType::CSV_FILE).await;
    sql(&c, "INSERT INTO poll (a) VALUES (2)").await.unwrap();
    eventually("the poll to save", || {
        std::fs::read_to_string(&file).unwrap().contains('2')
    })
    .await;
    close(c).await;
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "a\n1\n2\n");
    let c = env
        .open_with(&file, DatabaseType::CSV_FILE, None, true)
        .await;
    assert!(sql(&c, "INSERT INTO poll (a) VALUES (3)").await.is_err());
    close(c).await;
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "a\n1\n2\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_change_on_disk_is_read_in_when_nothing_is_unsaved_and_held_when_something_is() {
    let env = env();
    let file = env.file("live.csv");
    std::fs::write(&file, "k,v\na,1\n").unwrap();
    let c = env.open(&file, DatabaseType::CSV_FILE).await;
    std::thread::sleep(Duration::from_millis(20));
    std::fs::write(&file, "k,v\na,1\nb,2\n").unwrap();
    eventually("the file to be read again", || c.sync.revision() > 0).await;
    assert_eq!(
        rows(&c, "SELECT k, v FROM live ORDER BY k").await,
        [row(&["a", "1"]), row(&["b", "2"])]
    );

    // Hold a change made here against one made on disk.
    save(&c, false).await;
    std::thread::sleep(Duration::from_millis(20));
    std::fs::write(&file, "k,v\nz,9\n").unwrap();
    sql(&c, "UPDATE live SET v = 5 WHERE k = 'a'")
        .await
        .unwrap();
    eventually("the change to be held", || {
        matches!(c.sync.state(), DataFileState::Held { .. })
    })
    .await;
    assert!(matches!(
        c.sync.state(),
        DataFileState::Held {
            reason: HeldReason::ChangedOnDisk,
            ..
        }
    ));
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        "k,v\nz,9\n",
        "held changes don't overwrite the file"
    );
    save(&c, true).await;
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "k,v\na,5\nb,2\n");
    assert!(matches!(c.sync.state(), DataFileState::InSync { .. }));
    close(c).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_file_without_a_header_gets_numbered_columns_and_is_written_without_one() {
    let env = env();
    let file = env.file("raw.csv");
    std::fs::write(&file, "x,1\ny,2\n").unwrap();
    let options = DataFileOptions {
        has_header: false,
        ..DataFileOptions::default()
    };
    let c = env
        .open_with(&file, DatabaseType::CSV_FILE, Some(options), false)
        .await;
    assert_eq!(
        rows(&c, "SELECT column_1, column_2 FROM raw ORDER BY _row").await,
        [row(&["x", "1"]), row(&["y", "2"])]
    );
    sql(&c, "UPDATE raw SET column_2 = 3 WHERE column_1 = 'y'")
        .await
        .unwrap();
    save(&c, false).await;
    close(c).await;
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "x,1\ny,3\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn tables_beyond_the_files_one_stay_in_the_session() {
    let env = env();
    let file = env.file("one.csv");
    std::fs::write(&file, "a\n1\n").unwrap();
    let c = env.open(&file, DatabaseType::CSV_FILE).await;
    sql(&c, "CREATE TABLE scratch (b TEXT)").await.unwrap();
    save(&c, false).await;
    assert!(
        matches!(c.sync.state(), DataFileState::InSync { session_only, .. } if session_only == ["scratch"])
    );
    close(c).await;
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "a\n1\n");
}

#[tokio::test(flavor = "multi_thread")]
async fn an_unreadable_file_fails_with_whats_wrong_and_leaves_no_working_copy_behind() {
    let env = env();
    let file = env.file("bad.csv");
    std::fs::write(&file, [0xC3, 0x28, 0x0A]).unwrap();
    let options = DataFileOptions {
        charset: Some("UTF-8".into()),
        ..DataFileOptions::default()
    };
    assert!(
        DataFileStore::connect(
            &env.config(&file, DatabaseType::CSV_FILE, Some(options), false),
            &env.paths
        )
        .await
        .is_err()
    );
    assert!(
        DataFileStore::connect(
            &env.config(
                &env.file("missing.csv"),
                DatabaseType::CSV_FILE,
                None,
                false
            ),
            &env.paths
        )
        .await
        .is_err()
    );
    let copies: Vec<_> = std::fs::read_dir(env.paths.working_copies())
        .map(|d| d.flatten().collect())
        .unwrap_or_default();
    assert!(copies.is_empty(), "{copies:?}");
}

// Excel

const MAIN: &str = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const REL: &str = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

fn sales_sheet() -> String {
    format!(
        concat!(
            r#"<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{m}" xmlns:r="{r}"><dimension ref="A1:C3"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews>"#,
            r#"<cols><col min="1" max="1" width="30" customWidth="1"/></cols><sheetData>"#,
            r#"<row r="1"><c r="A1" s="2" t="s"><v>0</v></c><c r="B1" s="2" t="s"><v>1</v></c><c r="C1" s="2" t="s"><v>2</v></c></row>"#,
            r#"<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2" s="1"><v>45292</v></c><c r="C2"><v>3</v></c></row>"#,
            r#"<row r="3"><c r="A3" t="s"><v>4</v></c><c r="B3" s="1"><v>45293</v></c><c r="C3"><v>10</v></c></row>"#,
            r#"</sheetData><autoFilter ref="A1:C3"/><pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>"#
        ),
        m = MAIN,
        r = REL
    )
}

/// A workbook as Excel lays it out: a data sheet with a date column and frozen panes, a formula sheet and a calc chain.
fn workbook(file: &Path, replace: &[(&str, String)]) {
    let mut parts: Vec<(String, String)> = vec![
        ("[Content_Types].xml".into(), concat!(
            r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">"#,
            r#"<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>"#,
            r#"<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>"#,
            r#"<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"#,
            r#"<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"#,
            r#"<Override PartName="/xl/calcChain.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml"/></Types>"#
        ).into()),
        ("_rels/.rels".into(), format!(r#"<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>"#)),
        ("xl/workbook.xml".into(), format!(
            r#"<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="{MAIN}" xmlns:r="{REL}"><bookViews><workbookView activeTab="1"/></bookViews><sheets><sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Summary" sheetId="2" r:id="rId2"/></sheets><definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">Sales!$A$1:$C$3</definedName></definedNames></workbook>"#
        )),
        ("xl/_rels/workbook.xml.rels".into(), format!(
            r#"<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="{REL}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="{REL}/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="{REL}/styles" Target="styles.xml"/><Relationship Id="rId4" Type="{REL}/sharedStrings" Target="sharedStrings.xml"/><Relationship Id="rId5" Type="{REL}/calcChain" Target="calcChain.xml"/></Relationships>"#
        )),
        ("xl/styles.xml".into(), format!(r#"<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="{MAIN}"><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14" applyNumberFormat="1"/><xf numFmtId="0" fontId="1"/></cellXfs></styleSheet>"#)),
        ("xl/sharedStrings.xml".into(), format!(r#"<?xml version="1.0" encoding="UTF-8"?><sst xmlns="{MAIN}"><si><t>item</t></si><si><t>sold</t></si><si><t>qty</t></si><si><t>Pen</t></si><si><t>Ink</t></si></sst>"#)),
        ("xl/worksheets/sheet1.xml".into(), sales_sheet()),
        ("xl/worksheets/sheet2.xml".into(), format!(
            r#"<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{MAIN}"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>total</t></is></c></row><row r="2"><c r="A2"><f>SUM(Sales!C2:C3)</f><v>13</v></c></row></sheetData></worksheet>"#
        )),
        ("xl/calcChain.xml".into(), format!(r#"<?xml version="1.0" encoding="UTF-8"?><calcChain xmlns="{MAIN}"><c r="A2" i="2"/></calcChain>"#)),
    ];
    for (name, text) in replace {
        if let Some(p) = parts.iter_mut().find(|p| p.0 == *name) {
            p.1 = text.clone();
        }
    }
    let mut zip = zip::ZipWriter::new(std::fs::File::create(file).unwrap());
    for (name, text) in parts {
        zip.start_file(name, zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(text.as_bytes()).unwrap();
    }
    zip.finish().unwrap();
}

fn part(file: &Path, name: &str) -> Option<String> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(file).unwrap()).unwrap();
    let mut entry = zip.by_name(name).ok()?;
    let mut text = String::new();
    entry.read_to_string(&mut text).unwrap();
    Some(text)
}

#[tokio::test(flavor = "multi_thread")]
async fn an_edited_sheet_keeps_its_layout_styles_and_dates_and_untouched_sheets_are_copied_as_they_were()
 {
    let env = env();
    let file = env.file("book.xlsx");
    workbook(&file, &[]);
    let summary = part(&file, "xl/worksheets/sheet2.xml");
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    assert_eq!(table_names(&c).await, ["Sales", "Summary"]);
    assert_eq!(
        rows(&c, "SELECT item, sold, qty FROM Sales ORDER BY _row").await,
        [
            row(&["Pen", "2024-01-01", "3"]),
            row(&["Ink", "2024-01-02", "10"])
        ]
    );
    sql(
        &c,
        "UPDATE Sales SET qty = 4, sold = '2024-02-29' WHERE item = 'Pen'",
    )
    .await
    .unwrap();
    sql(
        &c,
        "INSERT INTO Sales (item, sold, qty) VALUES ('Nib', '2024-03-01', 1)",
    )
    .await
    .unwrap();
    save(&c, false).await;
    assert!(
        matches!(c.sync.state(), DataFileState::InSync { .. }),
        "{:?}",
        c.sync.state()
    );
    close(c).await;
    let sales = part(&file, "xl/worksheets/sheet1.xml").unwrap();
    assert!(
        sales.contains(r#"<pane ySplit="1" topLeftCell="A2" state="frozen"/>"#)
            && sales.contains(r#"<col min="1" max="1" width="30" customWidth="1"/>"#),
        "{sales}"
    );
    assert!(
        sales.contains(r#"<autoFilter ref="A1:C4"/>"#),
        "the filter grows with the new row: {sales}"
    );
    assert!(
        !sales.contains("<dimension"),
        "the stale dimension is dropped"
    );
    // Header keeps its style; dates go back as serials with the date style; numbers as numbers.
    assert!(
        sales.contains(r#"<c r="A1" s="2" t="inlineStr"><is><t>item</t></is></c>"#),
        "{sales}"
    );
    assert!(
        sales.contains(r#"<c r="B2" s="1"><v>45351</v></c>"#)
            && sales.contains(r#"<c r="C2"><v>4</v></c>"#),
        "{sales}"
    );
    assert!(sales.contains(r#"<row r="4"><c r="A4" t="inlineStr"><is><t>Nib</t></is></c><c r="B4" s="1"><v>45352</v></c><c r="C4"><v>1</v></c></row>"#), "{sales}");
    assert_eq!(
        part(&file, "xl/worksheets/sheet2.xml"),
        summary,
        "the formula sheet wasn't touched"
    );
    assert!(
        part(&file, "xl/calcChain.xml").is_none(),
        "Excel rebuilds the calc chain"
    );
    assert!(
        !part(&file, "[Content_Types].xml")
            .unwrap()
            .contains("calcChain")
    );
    assert!(
        !part(&file, "xl/_rels/workbook.xml.rels")
            .unwrap()
            .contains("calcChain")
    );

    // And it reads back the same.
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    assert_eq!(
        rows(&c, "SELECT item, sold, qty FROM Sales ORDER BY _row").await,
        [
            row(&["Pen", "2024-02-29", "4"]),
            row(&["Ink", "2024-01-02", "10"]),
            row(&["Nib", "2024-03-01", "1"])
        ]
    );
    close(c).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn data_rows_keep_their_own_look_when_a_blank_looking_row_sits_above_the_styled_header() {
    let env = env();
    let file = env.file("banner.xlsx");
    // Row 1 only holds a blank shared string (Excel leaves these behind); the styled header is row 2.
    let sheet = format!(
        concat!(
            r#"<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="{}"><sheetData>"#,
            r#"<row r="1"><c r="A1" t="s"><v>5</v></c></row>"#,
            r#"<row r="2"><c r="A2" s="2" t="s"><v>0</v></c><c r="B2" s="2" t="s"><v>2</v></c></row>"#,
            r#"<row r="3"><c r="A3" t="s"><v>3</v></c><c r="B3"><v>3</v></c></row>"#,
            r#"</sheetData></worksheet>"#
        ),
        MAIN
    );
    let strings = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?><sst xmlns="{MAIN}"><si><t>item</t></si><si><t>sold</t></si><si><t>qty</t></si><si><t>Pen</t></si><si><t>Ink</t></si><si><t xml:space="preserve"> </t></si></sst>"#
    );
    workbook(
        &file,
        &[
            ("xl/worksheets/sheet1.xml", sheet),
            ("xl/sharedStrings.xml", strings),
        ],
    );
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    assert_eq!(
        rows(&c, "SELECT item, qty FROM Sales").await,
        [row(&["Pen", "3"])]
    );
    sql(&c, "UPDATE Sales SET qty = 4").await.unwrap();
    save(&c, false).await;
    close(c).await;
    let sales = part(&file, "xl/worksheets/sheet1.xml").unwrap();
    assert!(
        sales.contains(r#"<row r="2"><c r="A2" s="2" t="inlineStr"><is><t>item</t></is></c>"#),
        "the header stays on row 2 with its style: {sales}"
    );
    assert!(
        sales.contains(r#"<sheetData><row r="1"><c r="A1" t="s"><v>5</v></c></row><row r="2">"#),
        "the row above the header is kept: {sales}"
    );
    assert!(sales.contains(r#"<row r="3"><c r="A3" t="inlineStr"><is><t>Pen</t></is></c><c r="B3"><v>4</v></c></row>"#), "data rows don't take the header's style: {sales}");
}

#[tokio::test(flavor = "multi_thread")]
async fn changing_a_sheet_with_formulas_asks_first_then_writes_their_values() {
    let env = env();
    let file = env.file("formulas.xlsx");
    workbook(&file, &[]);
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    assert_eq!(c.sync.notes().len(), 1);
    sql(&c, "UPDATE Summary SET total = 14").await.unwrap();
    save(&c, false).await;
    assert!(
        matches!(
            c.sync.state(),
            DataFileState::Held {
                reason: HeldReason::LosesContent,
                ..
            }
        ),
        "{:?}",
        c.sync.state()
    );
    assert!(
        part(&file, "xl/worksheets/sheet2.xml")
            .unwrap()
            .contains("<f>")
    );
    save(&c, true).await;
    assert!(
        matches!(c.sync.state(), DataFileState::InSync { .. }),
        "{:?}",
        c.sync.state()
    );
    close(c).await;
    assert!(
        !part(&file, "xl/worksheets/sheet2.xml")
            .unwrap()
            .contains("<f>")
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn tables_created_renamed_and_dropped_with_sql_become_sheets_renames_and_removals() {
    let env = env();
    let file = env.file("structure.xlsx");
    workbook(&file, &[]);
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    sql(&c, "CREATE TABLE Notes (id INTEGER, text TEXT)")
        .await
        .unwrap();
    sql(&c, "INSERT INTO Notes VALUES (1, 'a < b & c')")
        .await
        .unwrap();
    sql(&c, "ALTER TABLE Sales RENAME TO Orders").await.unwrap();
    sql(&c, "DROP TABLE Summary").await.unwrap();
    save(&c, false).await;
    assert!(
        matches!(c.sync.state(), DataFileState::InSync { .. }),
        "{:?}",
        c.sync.state()
    );
    close(c).await;
    let workbook = part(&file, "xl/workbook.xml").unwrap();
    assert!(
        workbook.contains(r#"name="Orders""#)
            && workbook.contains(r#"name="Notes""#)
            && !workbook.contains("Summary"),
        "{workbook}"
    );
    assert!(
        workbook.contains("Orders!"),
        "defined names follow the rename: {workbook}"
    );
    assert!(workbook.contains(r#"activeTab="0""#));
    assert!(
        workbook.find("Orders") < workbook.find("Notes"),
        "the new sheet goes last"
    );
    assert!(part(&file, "xl/worksheets/sheet2.xml").is_none());
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    let mut names = table_names(&c).await;
    names.sort();
    assert_eq!(names, ["Notes", "Orders"]);
    assert_eq!(
        rows(&c, "SELECT id, text FROM Notes").await,
        [row(&["1", "a < b & c"])]
    );
    close(c).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_table_name_a_sheet_cant_have_is_refused_with_the_reason() {
    let env = env();
    let file = env.file("names.xlsx");
    workbook(&file, &[]);
    let before = std::fs::read(&file).unwrap();
    let c = env.open(&file, DatabaseType::EXCEL_FILE).await;
    sql(&c, "CREATE TABLE \"a/b\" (x TEXT)").await.unwrap();
    save(&c, false).await;
    assert!(
        matches!(c.sync.state(), DataFileState::Failed(m) if m.contains("a/b")),
        "{:?}",
        c.sync.state()
    );
    close(c).await;
    assert_eq!(std::fs::read(&file).unwrap(), before);
}

// JSON

#[tokio::test(flavor = "multi_thread")]
async fn json_keeps_its_shape_indentation_value_types_and_missing_keys() {
    let env = env();
    let file = env.file("items.json");
    std::fs::write(
        &file,
        "[\n  {\n    \"id\": 1,\n    \"name\": \"pen\",\n    \"zip\": \"007\",\n    \"ok\": true,\n    \"tags\": [\"a\", \"b\"]\n  },\n  {\n    \"id\": 2,\n    \"name\": \"ink\",\n    \"zip\": \"123\",\n    \"ok\": false,\n    \"tags\": [],\n    \"extra\": null\n  }\n]\n",
    )
    .unwrap();
    let c = env.open(&file, DatabaseType::JSON_FILE).await;
    sql(
        &c,
        "UPDATE items SET name = 'nib', ok = 'true' WHERE id = 2",
    )
    .await
    .unwrap();
    save(&c, false).await;
    close(c).await;
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        "[\n  {\n    \"id\": 1,\n    \"name\": \"pen\",\n    \"zip\": \"007\",\n    \"ok\": true,\n    \"tags\": [\n      \"a\",\n      \"b\"\n    ],\n    \"extra\": null\n  },\n  {\n    \"id\": 2,\n    \"name\": \"nib\",\n    \"zip\": \"123\",\n    \"ok\": true,\n    \"tags\": [],\n    \"extra\": null\n  }\n]\n"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn ndjson_stays_one_record_per_line_and_leaves_out_keys_that_were_missing() {
    let env = env();
    let file = env.file("events.ndjson");
    std::fs::write(&file, "{\"t\":1,\"msg\":\"a\"}\n{\"t\":2}\n").unwrap();
    let c = env.open(&file, DatabaseType::JSON_FILE).await;
    sql(&c, "INSERT INTO events (t) VALUES (3)").await.unwrap();
    save(&c, false).await;
    close(c).await;
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        "{\"t\":1,\"msg\":\"a\"}\n{\"t\":2}\n{\"t\":3}\n"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn json_it_cant_write_back_opens_read_only() {
    let env = env();
    let file = env.file("wrapped.json");
    std::fs::write(&file, r#"{"data": [{"a": 1}, {"a": 2}]}"#).unwrap();
    let c = env.open(&file, DatabaseType::JSON_FILE).await;
    assert_eq!(rows(&c, "SELECT * FROM wrapped").await.len(), 2);
    assert!(sql(&c, "DELETE FROM wrapped").await.is_err());
    assert!(
        c.sync
            .read_only_reason
            .as_deref()
            .is_some_and(|r| !r.trim().is_empty())
    );
    close(c).await;
}

#[tokio::test(flavor = "multi_thread")]
async fn edits_through_the_table_viewers_path_are_saved_before_it_returns() {
    let env = env();
    let file = env.file("grid.csv");
    std::fs::write(&file, "id,v\n1,a\n").unwrap();
    let c = env.open(&file, DatabaseType::CSV_FILE).await;
    let ns = c.namespaces().await.unwrap().remove(0);
    let table = TableRef::new(ns, "grid");
    let columns: Vec<_> = c
        .table_columns(&table)
        .await
        .unwrap()
        .columns
        .into_iter()
        .filter(|c| c.name != "_row")
        .collect();
    let mut rows = vec![vec![Some("2".to_owned()), Some("b".to_owned())]].into_iter();
    c.insert_rows(
        &table,
        &columns,
        &mut rows,
        &|i| format!("row {i}"),
        &|_| {},
    )
    .await
    .unwrap();
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "id,v\n1,a\n2,b\n");
    close(c).await;
}

#[test]
fn profiles_for_dropped_files_are_found_by_extension() {
    assert_eq!(
        DataFileStore::profile_for(Path::new("/x/Book.XLSX"), "id", "").map(|c| c.db_type.id),
        Some(DatabaseType::EXCEL_FILE.id)
    );
    assert_eq!(
        DataFileStore::profile_for(Path::new("/x/a.tsv"), "id", "").map(|c| c.db_type.id),
        Some(DatabaseType::CSV_FILE.id)
    );
    assert!(DataFileStore::profile_for(Path::new("/x/a.db"), "id", "").is_none());
}

#[test]
fn numbers_only_become_numbers_when_they_read_back_unchanged() {
    assert!(DataValues::is_integer("42") && DataValues::is_integer("-7"));
    assert!(
        !(DataValues::is_integer("007")
            || DataValues::is_integer("+5")
            || DataValues::is_integer("-0")
            || DataValues::is_integer(" 1"))
    );
    assert!(
        DataValues::is_real("1.5")
            && DataValues::is_real("0.1")
            && DataValues::is_real("3")
            && DataValues::is_real("0.0000001")
    );
    assert!(
        !(DataValues::is_real("1.50")
            || DataValues::is_real("1e5")
            || DataValues::is_real("NaN")
            || DataValues::is_real("12345678901234567890"))
    );
    assert_eq!(excel_serial("2024-02-29", false).as_deref(), Some("45351"));
    assert_eq!(excel_serial("1900-01-01", false).as_deref(), Some("1"));
    assert_eq!(excel_serial("1900-03-01", false).as_deref(), Some("61"));
    assert_eq!(excel_serial("12:00:00", false).as_deref(), Some("0.5"));
    assert_eq!(column_letters(26), "AA");
}
