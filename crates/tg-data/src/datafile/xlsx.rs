//! Excel workbooks (`datafile/XlsxFormat.kt`): a table per worksheet, named after it. Reading is the importer's XLSX
//! reader.
//!
//! Writing patches the workbook rather than generating a new one. A sheet whose table is unchanged is copied byte for
//! byte; a changed one keeps everything around its cells (column widths, frozen panes, filters, merged cells,
//! drawings, page setup) and has only its `<sheetData>` replaced. New cells take each column's style from the row
//! they replace, so dates stay dates, currencies stay currencies and the header keeps its look. Tables created with
//! SQL become new sheets; dropped ones are removed; renamed ones rename their sheet. Formulas in a rewritten sheet
//! become their values, which the document's notes say up front.

use super::{
    CellValue, DataFileDocument, DataFileError, DataValues, OutputTable, Result, SourceRows,
    TableInfo,
};
use crate::importer::xlsx::{self as reader, DateKind, XlsxSource};
use crate::importer::{ImportFormat, ImportOptions, ImportSource};
use regex::Regex;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock, Mutex};
use tg_core::connection::DataFileOptions;

const MAIN_NAMESPACE: &str = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
const RELATIONSHIPS_NAMESPACE: &str =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
const WORKSHEET_RELATIONSHIP: &str =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet";
const WORKSHEET_CONTENT_TYPE: &str =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml";
const MAX_ROWS: u64 = 1_048_576;
const MAX_COLUMNS: usize = 16_384;
const MAX_CELL_TEXT: usize = 32_767;

/// Sheet rows of a table's header (None without one) and first data row (None when it had none).
#[derive(Clone, Copy, Debug)]
struct RowLayout {
    header: Option<u64>,
    data: Option<u64>,
}

#[derive(Clone, Debug)]
struct Sheet {
    name: String,
    relation_id: String,
    /// Zip path of the part; None when the relationship points nowhere.
    path: Option<String>,
    is_worksheet: bool,
    has_values: bool,
    has_formulas: bool,
}

/// What patching needs to know about a workbook's parts.
struct Workbook {
    workbook_path: String,
    relationships_path: String,
    /// Relationship id -> (type, zip path).
    relationships: HashMap<String, (String, String)>,
    /// Every `<sheet>` of the workbook, chart sheets too, in tab order (`localSheetId` counts them all).
    sheets: Vec<Sheet>,
    date1904: bool,
    date_styles: Vec<Option<DateKind>>,
}

impl Workbook {
    fn directory(&self) -> &str {
        self.workbook_path.rsplit_once('/').map_or("", |(d, _)| d)
    }

    fn read(zip: &mut reader::Archive) -> Result<Self> {
        let root = reader::relationships(zip, "_rels/.rels", "")?;
        let workbook_path = root
            .values()
            .find(|(t, _)| t.ends_with("/officeDocument"))
            .map(|(_, p)| p.clone())
            .unwrap_or_else(|| "xl/workbook.xml".into());
        let directory = workbook_path
            .rsplit_once('/')
            .map_or("", |(d, _)| d)
            .to_owned();
        let file = workbook_path.rsplit('/').next().unwrap_or("");
        let relationships_path = format!("{}_rels/{file}.rels", slash(&directory));
        let relationships = reader::relationships(zip, &relationships_path, &directory)?;
        let text = reader::read_text(zip, &workbook_path, true)?.unwrap_or_default();
        let date1904 = WORKBOOK_PR
            .find(&text)
            .and_then(|m| attribute(m.as_str(), "date1904"))
            .is_some_and(|v| v == "1" || v.eq_ignore_ascii_case("true"));
        let mut sheets = Vec::new();
        for m in SHEET_ELEMENT.find_iter(&text) {
            let element = m.as_str();
            let name = attribute(element, "name").unwrap_or_default();
            let id = relation_id_attribute(element).unwrap_or_default();
            let (kind, target) = relationships.get(&id).cloned().unwrap_or_default();
            let path = (!target.is_empty())
                .then(|| reader::find_entry(zip, &target))
                .flatten();
            let worksheet = kind.ends_with("/worksheet") && path.is_some();
            let (values, formulas) = match (&path, worksheet) {
                (Some(p), true) => scan_sheet(zip, p)?,
                _ => (false, false),
            };
            sheets.push(Sheet {
                name,
                relation_id: id,
                path,
                is_worksheet: worksheet,
                has_values: values,
                has_formulas: formulas,
            });
        }
        let styles = relationships
            .values()
            .find(|(t, _)| t.ends_with("/styles"))
            .map(|(_, p)| p.clone())
            .unwrap_or_else(|| "xl/styles.xml".into());
        Ok(Self {
            workbook_path,
            relationships_path,
            relationships,
            sheets,
            date1904,
            date_styles: reader::read_styles(zip, &styles)?,
        })
    }
}

/// Whether the sheet has any value, and any formula.
fn scan_sheet(zip: &mut reader::Archive, path: &str) -> Result<(bool, bool)> {
    let text = reader::read_text(zip, path, false)?.unwrap_or_default();
    let values = VALUE_TAG.is_match(&text);
    let formulas = FORMULA_TAG.is_match(&text);
    Ok((values, formulas))
}

fn slash(s: &str) -> String {
    if s.is_empty() {
        String::new()
    } else {
        format!("{s}/")
    }
}

struct Document {
    path: PathBuf,
    has_header: bool,
    workbook: Workbook,
    /// Where each sheet's header and first data row are, by lower-case sheet name, as the reader found them. A save
    /// styles new cells after these rows.
    layouts: Arc<Mutex<HashMap<String, RowLayout>>>,
    tables: Vec<TableInfo>,
    sources: Vec<Option<XlsxSource>>,
}

fn not_a_workbook(path: &Path) -> DataFileError {
    DataFileError(format!(
        "{} isn't an .xlsx workbook (legacy .xls and password-protected workbooks aren't supported)",
        path.file_name()
            .map(|n| n.to_string_lossy())
            .unwrap_or_default()
    ))
}

pub fn open(path: &Path, options: &DataFileOptions) -> Result<Box<dyn DataFileDocument>> {
    let mut zip = reader::open_archive(path).map_err(|_| not_a_workbook(path))?;
    let workbook = Workbook::read(&mut zip)?;
    let mut tables = Vec::new();
    let mut sources = Vec::new();
    for sheet in workbook
        .sheets
        .iter()
        .filter(|s| s.is_worksheet && s.has_values)
    {
        let mut import = ImportOptions::new(ImportFormat::Xlsx);
        import.has_header = options.has_header;
        import.sheet = Some(sheet.name.clone());
        let source = XlsxSource::open(path, &import, None).map_err(|e| DataFileError(e.message))?;
        tables.push(TableInfo {
            name: sheet.name.clone(),
            columns: source.columns().to_vec(),
        });
        sources.push(Some(source));
    }
    Ok(Box::new(Document {
        path: path.to_owned(),
        has_header: options.has_header,
        workbook,
        layouts: Arc::new(Mutex::new(HashMap::new())),
        tables,
        sources,
    }))
}

impl DataFileDocument for Document {
    fn tables(&self) -> Vec<TableInfo> {
        self.tables.clone()
    }

    fn rows(&mut self, index: usize) -> Result<SourceRows<'_>> {
        let name = self.tables[index].name.clone();
        let source = self.sources[index]
            .as_mut()
            .ok_or_else(|| DataFileError(format!("Sheet \"{name}\" was already read")))?;
        let header = source.header_line;
        let layouts = self.layouts.clone();
        let key = name.to_lowercase();
        layouts
            .lock()
            .unwrap()
            .insert(key.clone(), RowLayout { header, data: None });
        let mut first = true;
        let rows = source.rows().map_err(|e| DataFileError(e.message))?;
        Ok(Box::new(rows.map(move |row| {
            let row = row.map_err(|e| DataFileError(e.message))?;
            if first {
                first = false;
                layouts.lock().unwrap().insert(
                    key.clone(),
                    RowLayout {
                        header,
                        data: Some(row.line),
                    },
                );
            }
            Ok(row.values)
        })))
    }

    fn multi_table(&self) -> bool {
        true
    }

    fn notes(&self) -> Vec<String> {
        self.workbook
            .sheets
            .iter()
            .filter(|s| s.has_formulas && s.is_worksheet && s.has_values)
            .map(|s| format!("Sheet \"{}\" has formulas: saving a change to it writes their current values instead.", s.name))
            .collect()
    }

    fn confirm_before_writing(&self, tables: &BTreeSet<String>) -> Option<String> {
        let sheets: Vec<String> = self
            .workbook
            .sheets
            .iter()
            .filter(|s| s.has_formulas && tables.iter().any(|t| t.eq_ignore_ascii_case(&s.name)))
            .map(|s| format!("\"{}\"", s.name))
            .collect();
        if sheets.is_empty() {
            return None;
        }
        let list = sheets.join(", ");
        Some(format!(
            "{} formulas. Saving your changes writes their current values in place of the formulas.",
            if sheets.len() == 1 {
                format!("Sheet {list} has")
            } else {
                format!("Sheets {list} have")
            }
        ))
    }

    fn write(
        &mut self,
        target: &Path,
        tables: &[OutputTable<'_>],
        removed: &BTreeSet<String>,
    ) -> Result<()> {
        if tables.is_empty() {
            return Err(DataFileError(
                "A workbook needs at least one sheet; it can't be saved without tables.".into(),
            ));
        }
        for t in tables {
            validate_sheet_name(&t.name)?;
        }
        let mut zip = reader::open_archive(&self.path).map_err(|_| not_a_workbook(&self.path))?;
        let workbook = Workbook::read(&mut zip)?;
        let layouts = self.layouts.lock().unwrap().clone();
        Patch::new(
            &mut zip,
            &workbook,
            self.has_header,
            tables,
            removed,
            &layouts,
        )?
        .write_to(&mut zip, target)?;
        // What was written is laid out from the header row down, with no gaps.
        let mut layouts = self.layouts.lock().unwrap();
        for table in tables {
            let before = table
                .source
                .as_ref()
                .and_then(|s| layouts.get(&s.to_lowercase()).copied());
            let first = before.and_then(|b| b.header.or(b.data)).unwrap_or(1);
            if let Some(source) = &table.source {
                layouts.remove(&source.to_lowercase());
            }
            layouts.insert(
                table.name.to_lowercase(),
                RowLayout {
                    header: self.has_header.then_some(first),
                    data: Some(if self.has_header { first + 1 } else { first }),
                },
            );
        }
        Ok(())
    }
}

/// How a column's cells look in the sheet being replaced: the header row's styles and the first data row's.
#[derive(Default)]
struct Template {
    first_row: u64,
    header_styles: Vec<Option<u32>>,
    data: Vec<Option<CellTemplate>>,
}

#[derive(Clone, Debug)]
struct CellTemplate {
    style: u32,
    kind: Option<String>,
}

impl CellTemplate {
    fn numeric(&self) -> bool {
        matches!(self.kind.as_deref(), None | Some("n"))
    }
}

impl Template {
    fn empty() -> Self {
        Self {
            first_row: 1,
            ..Self::default()
        }
    }

    /// The styles of `layout`'s header and first data row in the sheet. Without a layout (the sheet had no table
    /// before), the first row of the sheet is where writing starts and nothing is styled.
    fn read(text: &str, layout: Option<RowLayout>, has_header: bool) -> Self {
        let Some(layout) = layout else {
            return Self::empty();
        };
        let Some(first_row) = layout.header.or(layout.data) else {
            return Self::empty();
        };
        let wanted: Vec<u64> = [layout.header.filter(|_| has_header), layout.data]
            .into_iter()
            .flatten()
            .collect();
        let max = wanted.iter().copied().max().unwrap_or(0);
        let mut rows: HashMap<u64, HashMap<usize, CellTemplate>> = HashMap::new();
        let mut last_row = 0u64;
        for row in ROW_ELEMENT.captures_iter(text) {
            let start = row.get(1).map_or("", |m| m.as_str());
            let number = attribute(start, "r")
                .and_then(|r| r.parse().ok())
                .unwrap_or(last_row + 1);
            last_row = number;
            if number > max || rows.len() >= wanted.len() {
                break;
            }
            if !wanted.contains(&number) {
                continue;
            }
            let mut cells = HashMap::new();
            let mut last_column: i64 = -1;
            for cell in CELL_START.find_iter(row.get(2).map_or("", |m| m.as_str())) {
                let c = cell.as_str();
                let column = attribute(c, "r")
                    .and_then(|r| reader::column_index(&r))
                    .map_or(last_column + 1, |c| c as i64);
                last_column = column;
                cells.insert(
                    column as usize,
                    CellTemplate {
                        style: attribute(c, "s").and_then(|s| s.parse().ok()).unwrap_or(0),
                        kind: attribute(c, "t"),
                    },
                );
            }
            rows.insert(number, cells);
        }
        fn dense<T: Clone>(map: &HashMap<usize, T>) -> Vec<Option<T>> {
            let max = map.keys().copied().max().map_or(0, |m| m + 1);
            (0..max).map(|i| map.get(&i).cloned()).collect()
        }
        let empty = HashMap::new();
        let header = layout
            .header
            .filter(|_| has_header)
            .and_then(|h| rows.get(&h))
            .unwrap_or(&empty);
        let data = layout.data.and_then(|d| rows.get(&d)).unwrap_or(&empty);
        Self {
            first_row,
            header_styles: dense(header)
                .into_iter()
                .map(|c| c.map(|c| c.style))
                .collect(),
            data: dense(data),
        }
    }
}

struct NewSheet {
    table: usize,
    path: String,
    relation_id: String,
    sheet_id: u32,
}

/// One save: copies the workbook into the target with the tables' changes applied.
struct Patch<'p, 'a> {
    workbook: &'p Workbook,
    has_header: bool,
    tables: &'p [OutputTable<'a>],
    layouts: &'p HashMap<String, RowLayout>,
    dropped: Vec<&'p Sheet>,
    dropped_paths: HashSet<String>,
    /// Tables written over an existing sheet, by that sheet's lower-case part path.
    rewritten: HashMap<String, usize>,
    renamed: Vec<(&'p Sheet, String)>,
    added: Vec<NewSheet>,
    calc_chain: Option<String>,
}

impl<'p, 'a> Patch<'p, 'a> {
    fn new(
        zip: &mut reader::Archive,
        workbook: &'p Workbook,
        has_header: bool,
        tables: &'p [OutputTable<'a>],
        removed: &BTreeSet<String>,
        layouts: &'p HashMap<String, RowLayout>,
    ) -> Result<Self> {
        let removed: HashSet<String> = removed.iter().map(|r| r.to_lowercase()).collect();
        let dropped: Vec<&Sheet> = workbook
            .sheets
            .iter()
            .filter(|s| s.is_worksheet && removed.contains(&s.name.to_lowercase()))
            .collect();
        let dropped_paths = dropped
            .iter()
            .filter_map(|s| s.path.clone())
            .flat_map(|p| [rels_path_of(&p), p])
            .map(|p| p.to_lowercase())
            .collect();
        let mut patch = Self {
            workbook,
            has_header,
            tables,
            layouts,
            dropped,
            dropped_paths,
            rewritten: HashMap::new(),
            renamed: Vec::new(),
            added: Vec::new(),
            calc_chain: workbook
                .relationships
                .values()
                .find(|(t, _)| t.ends_with("/calcChain"))
                .map(|(_, p)| p.clone()),
        };
        for (i, table) in tables.iter().enumerate() {
            if let Some(sheet) = patch.target_of(table) {
                if table.changed || table.source.is_none() {
                    patch
                        .rewritten
                        .insert(sheet.path.clone().unwrap_or_default().to_lowercase(), i);
                }
                if sheet.name != table.name {
                    patch.renamed.push((sheet, table.name.clone()));
                }
            }
        }
        let mut names: HashSet<String> = zip
            .file_names()
            .filter_map(|n| n.ok())
            .map(|n| n.to_lowercase())
            .collect();
        let mut ids: HashSet<String> = workbook.relationships.keys().cloned().collect();
        let mut sheet_number = 1;
        let mut relation_number = 1;
        let text = reader::read_text(zip, &workbook.workbook_path, true)?.unwrap_or_default();
        let mut sheet_id = SHEET_ELEMENT
            .find_iter(&text)
            .filter_map(|m| attribute(m.as_str(), "sheetId").and_then(|i| i.parse::<u32>().ok()))
            .max()
            .unwrap_or(0);
        let directory = slash(workbook.directory());
        for (i, table) in tables.iter().enumerate() {
            if patch.target_of(table).is_some() {
                continue;
            }
            while names
                .contains(&format!("{directory}worksheets/sheet{sheet_number}.xml").to_lowercase())
            {
                sheet_number += 1;
            }
            let path = format!("{directory}worksheets/sheet{sheet_number}.xml");
            names.insert(path.to_lowercase());
            while ids.contains(&format!("rId{relation_number}")) {
                relation_number += 1;
            }
            let id = format!("rId{relation_number}");
            ids.insert(id.clone());
            sheet_id += 1;
            patch.added.push(NewSheet {
                table: i,
                path,
                relation_id: id,
                sheet_id,
            });
        }
        Ok(patch)
    }

    /// The worksheet a table is written to: the one it came from, else one of its name that held no values (so it
    /// was never a table), else none and it becomes a new sheet.
    fn target_of(&self, table: &OutputTable<'_>) -> Option<&'p Sheet> {
        let by_name = |name: &str| {
            self.workbook
                .sheets
                .iter()
                .find(|s| s.name.eq_ignore_ascii_case(name))
        };
        if let Some(source) = &table.source {
            return by_name(source);
        }
        by_name(&table.name).filter(|s| {
            s.is_worksheet
                && !self.dropped.iter().any(|d| std::ptr::eq(*d, *s))
                && !self.tables.iter().any(|o| {
                    o.source
                        .as_deref()
                        .is_some_and(|src| src.eq_ignore_ascii_case(&s.name))
                })
        })
    }

    fn touched(&self) -> bool {
        !self.rewritten.is_empty() || !self.added.is_empty() || !self.dropped.is_empty()
    }

    fn write_to(&self, zip: &mut reader::Archive, target: &Path) -> Result<()> {
        let mut out = zip::ZipWriter::new(std::fs::File::create(target)?);
        let options = zip::write::SimpleFileOptions::default();
        let zip_error = |e: zip::result::ZipError| DataFileError(e.to_string());
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i).map_err(zip_error)?;
            let name = entry.name().map_err(zip_error)?.into_owned();
            let lower = name.to_lowercase();
            if self.dropped_paths.contains(&lower) {
                continue;
            }
            // Excel rebuilds the calculation chain; a stale one makes it "repair" the workbook.
            if self.touched()
                && self
                    .calc_chain
                    .as_deref()
                    .is_some_and(|c| c.to_lowercase() == lower)
            {
                continue;
            }
            if entry.is_dir() {
                out.add_directory(name, options).map_err(zip_error)?;
                continue;
            }
            let mut bytes = Vec::new();
            entry.read_to_end(&mut bytes)?;
            out.start_file(name, options).map_err(zip_error)?;
            if let Some(&table) = self.rewritten.get(&lower) {
                let table = &self.tables[table];
                let key = table
                    .source
                    .as_deref()
                    .unwrap_or(&table.name)
                    .to_lowercase();
                let text = String::from_utf8_lossy(&bytes);
                let template =
                    Template::read(&text, self.layouts.get(&key).copied(), self.has_header);
                out.write_all(self.splice(&text, &template, table)?.as_bytes())?;
            } else if lower == self.workbook.workbook_path.to_lowercase() {
                out.write_all(
                    self.patch_workbook(&String::from_utf8_lossy(&bytes))?
                        .as_bytes(),
                )?;
            } else if lower == self.workbook.relationships_path.to_lowercase() {
                out.write_all(
                    self.patch_relationships(&String::from_utf8_lossy(&bytes))?
                        .as_bytes(),
                )?;
            } else if lower == "[content_types].xml" {
                out.write_all(
                    self.patch_content_types(&String::from_utf8_lossy(&bytes))?
                        .as_bytes(),
                )?;
            } else {
                out.write_all(&bytes)?;
            }
        }
        for sheet in &self.added {
            out.start_file(&sheet.path, options).map_err(zip_error)?;
            let mut text =
                String::from("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
            text += &format!(
                "<worksheet xmlns=\"{MAIN_NAMESPACE}\" xmlns:r=\"{RELATIONSHIPS_NAMESPACE}\">"
            );
            self.write_sheet_data(
                &mut text,
                "",
                &Template::empty(),
                &self.tables[sheet.table],
                &mut |_| {},
            )?;
            text += "</worksheet>";
            out.write_all(text.as_bytes())?;
        }
        out.finish().map_err(zip_error)?;
        Ok(())
    }

    /// Sheet list, defined names and the active tab, edited as text: the rest of workbook.xml stays exactly as it was.
    fn patch_workbook(&self, text: &str) -> Result<String> {
        if self.dropped.is_empty() && self.renamed.is_empty() && self.added.is_empty() {
            return Ok(text.to_owned());
        }
        let dropped_ids: HashSet<&str> = self
            .dropped
            .iter()
            .map(|s| s.relation_id.as_str())
            .collect();
        let kept: Vec<&Sheet> = self
            .workbook
            .sheets
            .iter()
            .filter(|s| !dropped_ids.contains(s.relation_id.as_str()))
            .collect();
        let new_index = |old: usize| -> Option<usize> {
            let sheet = self.workbook.sheets.get(old)?;
            kept.iter().position(|k| std::ptr::eq(*k, sheet))
        };
        let mut result = SHEET_ELEMENT
            .replace_all(text, |c: &regex::Captures| {
                let element = &c[0];
                let id = relation_id_attribute(element).unwrap_or_default();
                if dropped_ids.contains(id.as_str()) {
                    return String::new();
                }
                match self.renamed.iter().find(|(s, _)| s.relation_id == id) {
                    Some((_, name)) => set_attribute(element, "name", &escape_attribute(name)),
                    None => element.to_owned(),
                }
            })
            .into_owned();
        if !self.added.is_empty() {
            let close = SHEETS_CLOSE.captures(&result).ok_or_else(|| {
                DataFileError(
                    "The workbook's sheet list is malformed; it can't take a new sheet.".into(),
                )
            })?;
            let at = close.get(0).unwrap().start();
            let prefix = close.get(1).map_or("", |m| m.as_str()).to_owned();
            let relation_prefix = SHEET_ELEMENT
                .find(text)
                .and_then(|m| {
                    RELATION_PREFIX
                        .captures(m.as_str())
                        .map(|c| c[1].to_owned())
                })
                .unwrap_or_else(|| "r".into());
            let elements: String = self
                .added
                .iter()
                .map(|s| {
                    format!(
                        "<{prefix}sheet name=\"{}\" sheetId=\"{}\" {relation_prefix}:id=\"{}\"/>",
                        escape_attribute(&self.tables[s.table].name),
                        s.sheet_id,
                        s.relation_id
                    )
                })
                .collect();
            result.insert_str(at, &elements);
            if relation_prefix == "r" && !result.contains("xmlns:r=") {
                result = WORKBOOK_OPEN
                    .replace(&result, |c: &regex::Captures| {
                        format!("{} xmlns:r=\"{RELATIONSHIPS_NAMESPACE}\"", &c[0])
                    })
                    .into_owned();
            }
        }
        result = DEFINED_NAME
            .replace_all(&result, |c: &regex::Captures| {
                let mut element = c[0].to_owned();
                if let Some(local) =
                    attribute(&element, "localSheetId").and_then(|l| l.parse::<usize>().ok())
                {
                    match new_index(local) {
                        Some(moved) => {
                            element = set_attribute(&element, "localSheetId", &moved.to_string())
                        }
                        None => return String::new(),
                    }
                }
                let body = &c[2];
                if self.dropped.iter().any(|d| references(body, &d.name)) {
                    return String::new();
                }
                let mut new_body = body.to_owned();
                for (sheet, name) in &self.renamed {
                    new_body = rename_references(&new_body, &sheet.name, name);
                }
                if new_body != body {
                    element = element.replace(&format!(">{body}<"), &format!(">{new_body}<"));
                }
                element
            })
            .into_owned();
        if !self.dropped.is_empty() {
            result = ACTIVE_TAB.replace_all(&result, "$1=\"0\"").into_owned();
        }
        Ok(result)
    }

    fn patch_relationships(&self, text: &str) -> Result<String> {
        let mut result = text.to_owned();
        let mut remove: HashSet<String> =
            self.dropped.iter().map(|s| s.relation_id.clone()).collect();
        if self.touched() {
            remove.extend(
                self.workbook
                    .relationships
                    .iter()
                    .filter(|(_, (t, _))| t.ends_with("/calcChain"))
                    .map(|(id, _)| id.clone()),
            );
        }
        if !remove.is_empty() {
            result = RELATIONSHIP_ELEMENT
                .replace_all(&result, |c: &regex::Captures| {
                    if attribute(&c[0], "Id").is_some_and(|id| remove.contains(&id)) {
                        String::new()
                    } else {
                        c[0].to_owned()
                    }
                })
                .into_owned();
        }
        if !self.added.is_empty() {
            let close = RELATIONSHIPS_CLOSE.captures(&result).ok_or_else(|| {
                DataFileError(
                    "The workbook's relationships are malformed; it can't take a new sheet.".into(),
                )
            })?;
            let at = close.get(0).unwrap().start();
            let prefix = close.get(1).map_or("", |m| m.as_str()).to_owned();
            let base = slash(self.workbook.directory());
            let elements: String = self
                .added
                .iter()
                .map(|s| {
                    format!(
                        "<{prefix}Relationship Id=\"{}\" Type=\"{WORKSHEET_RELATIONSHIP}\" Target=\"{}\"/>",
                        s.relation_id,
                        s.path.strip_prefix(&base).unwrap_or(&s.path)
                    )
                })
                .collect();
            result.insert_str(at, &elements);
        }
        Ok(result)
    }

    fn patch_content_types(&self, text: &str) -> Result<String> {
        let mut result = text.to_owned();
        let mut remove: HashSet<String> = self
            .dropped
            .iter()
            .filter_map(|s| s.path.as_ref())
            .map(|p| format!("/{p}").to_lowercase())
            .collect();
        if self.touched()
            && let Some(c) = &self.calc_chain
        {
            remove.insert(format!("/{c}").to_lowercase());
        }
        if !remove.is_empty() {
            result = OVERRIDE_ELEMENT
                .replace_all(&result, |c: &regex::Captures| {
                    if attribute(&c[0], "PartName")
                        .is_some_and(|p| remove.contains(&p.to_lowercase()))
                    {
                        String::new()
                    } else {
                        c[0].to_owned()
                    }
                })
                .into_owned();
        }
        if !self.added.is_empty() {
            let close = TYPES_CLOSE.captures(&result).ok_or_else(|| {
                DataFileError(
                    "The workbook's content types are malformed; it can't take a new sheet.".into(),
                )
            })?;
            let at = close.get(0).unwrap().start();
            let prefix = close.get(1).map_or("", |m| m.as_str()).to_owned();
            let elements: String = self
                .added
                .iter()
                .map(|s| format!("<{prefix}Override PartName=\"/{}\" ContentType=\"{WORKSHEET_CONTENT_TYPE}\"/>", s.path))
                .collect();
            result.insert_str(at, &elements);
        }
        Ok(result)
    }

    /// Copies a sheet part, replacing `<sheetData>` with the table's rows and dropping `<dimension>` (Excel
    /// recomputes it).
    fn splice(&self, text: &str, template: &Template, table: &OutputTable<'_>) -> Result<String> {
        let mut out = String::with_capacity(text.len());
        let mut cursor = Cursor { text, at: 0 };
        let mut skipping: Option<String> = None;
        let mut last_row: Option<u64> = None;
        while let Some(c) = cursor.next() {
            if c != '<' {
                if skipping.is_none() {
                    out.push(c);
                }
                continue;
            }
            let tag = cursor.read_tag();
            if let Some(s) = &skipping {
                if tag.is_end && &tag.local_name == s {
                    skipping = None;
                }
                continue;
            }
            if tag.is_start && tag.local_name == "dimension" {
                if !tag.self_closing {
                    skipping = Some("dimension".into());
                }
            } else if tag.is_start && tag.local_name == "sheetData" {
                let mut ended = tag.self_closing;
                let first_row = template.first_row;
                last_row = Some(self.write_sheet_data(
                    &mut out,
                    &tag.prefix,
                    template,
                    table,
                    &mut |out| {
                        if !ended {
                            ended = copy_rows_above(&mut cursor, out, first_row);
                        }
                    },
                )?);
                if !ended {
                    skipping = Some("sheetData".into());
                }
            } else if tag.is_start && tag.local_name == "autoFilter" && last_row.is_some() {
                // A filter over the table grows and shrinks with it.
                out.push_str(&resize_filter(
                    &tag.text,
                    template.first_row,
                    last_row.unwrap_or(0),
                ));
            } else {
                out.push_str(&tag.text);
            }
        }
        Ok(out)
    }

    /// Writes the rows after `above`'s; returns the last row number written.
    fn write_sheet_data(
        &self,
        out: &mut String,
        prefix: &str,
        template: &Template,
        table: &OutputTable<'_>,
        above: &mut dyn FnMut(&mut String),
    ) -> Result<u64> {
        if table.columns.len() > MAX_COLUMNS {
            return Err(DataFileError(format!(
                "\"{}\" has {} columns; a sheet holds at most {MAX_COLUMNS}.",
                table.name,
                table.columns.len()
            )));
        }
        let letters: Vec<String> = (0..table.columns.len()).map(column_letters).collect();
        let mut row_number = template.first_row;
        out.push_str(&format!("<{prefix}sheetData>"));
        above(out);
        let start_row = |out: &mut String, n: u64| -> Result<()> {
            if n > MAX_ROWS {
                return Err(DataFileError(format!(
                    "\"{}\" has more rows than a sheet holds ({MAX_ROWS}).",
                    table.name
                )));
            }
            out.push_str(&format!("<{prefix}row r=\"{n}\">"));
            Ok(())
        };
        if self.has_header {
            start_row(out, row_number)?;
            for (i, column) in table.columns.iter().enumerate() {
                let style = template.header_styles.get(i).copied().flatten();
                inline_string(
                    out,
                    prefix,
                    &format!("{}{row_number}", letters[i]),
                    style,
                    &column.name,
                    table,
                )?;
            }
            out.push_str(&format!("</{prefix}row>"));
            row_number += 1;
        }
        for values in (table.rows)()? {
            start_row(out, row_number)?;
            for (i, value) in values.iter().enumerate() {
                let reference = format!("{}{row_number}", letters[i]);
                self.cell(
                    out,
                    prefix,
                    &reference,
                    template.data.get(i).cloned().flatten(),
                    value,
                    table,
                )?;
            }
            out.push_str(&format!("</{prefix}row>"));
            row_number += 1;
        }
        out.push_str(&format!("</{prefix}sheetData>"));
        Ok((row_number - 1).max(template.first_row))
    }

    fn cell(
        &self,
        out: &mut String,
        prefix: &str,
        reference: &str,
        template: Option<CellTemplate>,
        value: &CellValue,
        table: &OutputTable<'_>,
    ) -> Result<()> {
        let style = template.as_ref().map(|t| t.style).filter(|s| *s != 0);
        let style_attribute = style.map(|s| format!(" s=\"{s}\"")).unwrap_or_default();
        let number = |out: &mut String, v: &str| {
            out.push_str(&format!("<{prefix}c r=\"{reference}\"{style_attribute}><{prefix}v>{v}</{prefix}v></{prefix}c>"))
        };
        match value {
            CellValue::Null => {
                if style.is_some() {
                    out.push_str(&format!("<{prefix}c r=\"{reference}\"{style_attribute}/>"));
                }
            }
            CellValue::Integer(i) => number(out, &i.to_string()),
            CellValue::Real(f) => number(out, &DataValues::real(*f)),
            other => {
                let text = DataValues::text(other).unwrap_or_default();
                let date_kind = style
                    .and_then(|s| self.workbook.date_styles.get(s as usize).copied().flatten());
                let serial = date_kind.and_then(|_| excel_serial(&text, self.workbook.date1904));
                if template
                    .as_ref()
                    .is_some_and(|t| t.kind.as_deref() == Some("b"))
                    && (text == "true" || text == "false")
                {
                    out.push_str(&format!(
                        "<{prefix}c r=\"{reference}\"{style_attribute} t=\"b\"><{prefix}v>{}</{prefix}v></{prefix}c>",
                        u8::from(text == "true")
                    ));
                } else if let Some(serial) = serial {
                    number(out, &serial);
                } else if template.as_ref().is_some_and(CellTemplate::numeric)
                    && (DataValues::is_integer(&text) || DataValues::is_real(&text))
                {
                    number(out, &text);
                } else {
                    inline_string(out, prefix, reference, style, &text, table)?;
                }
            }
        }
        Ok(())
    }
}

fn inline_string(
    out: &mut String,
    prefix: &str,
    reference: &str,
    style: Option<u32>,
    text: &str,
    table: &OutputTable<'_>,
) -> Result<()> {
    let length = text.encode_utf16().count();
    if length > MAX_CELL_TEXT {
        return Err(DataFileError(format!(
            "A value in \"{}\" (cell {reference}) is {length} characters long; a cell holds at most {MAX_CELL_TEXT}.",
            table.name
        )));
    }
    let style_attribute = style
        .filter(|s| *s != 0)
        .map(|s| format!(" s=\"{s}\""))
        .unwrap_or_default();
    let space = if text.starts_with(char::is_whitespace) || text.ends_with(char::is_whitespace) {
        " xml:space=\"preserve\""
    } else {
        ""
    };
    out.push_str(&format!(
        "<{prefix}c r=\"{reference}\"{style_attribute} t=\"inlineStr\"><{prefix}is><{prefix}t{space}>{}</{prefix}t></{prefix}is></{prefix}c>",
        escape_text(text)
    ));
    Ok(())
}

struct Cursor<'t> {
    text: &'t str,
    at: usize,
}

struct Tag {
    text: String,
    is_start: bool,
    is_end: bool,
    self_closing: bool,
    prefix: String,
    local_name: String,
}

impl Cursor<'_> {
    fn next(&mut self) -> Option<char> {
        let c = self.text[self.at..].chars().next()?;
        self.at += c.len_utf8();
        Some(c)
    }

    /// Reads a tag after its `<`: comments, processing instructions and CDATA come back whole with no name.
    fn read_tag(&mut self) -> Tag {
        let start = self.at - 1;
        let rest = &self.text[start..];
        let whole = |end: &str, s: &mut Self| -> Tag {
            let stop = rest.find(end).map_or(rest.len(), |i| i + end.len());
            s.at = start + stop;
            Tag {
                text: rest[..stop].to_owned(),
                is_start: false,
                is_end: false,
                self_closing: false,
                prefix: String::new(),
                local_name: String::new(),
            }
        };
        if rest.starts_with("<!--") {
            return whole("-->", self);
        }
        if rest.starts_with("<![CDATA[") {
            return whole("]]>", self);
        }
        let mut quote: Option<char> = None;
        while let Some(c) = self.next() {
            match quote {
                Some(q) if c == q => quote = None,
                Some(_) => {}
                None if c == '"' || c == '\'' => quote = Some(c),
                None if c == '>' => break,
                None => {}
            }
        }
        let body = &self.text[start..self.at];
        if body.starts_with("<?") || body.starts_with("<!") {
            return Tag {
                text: body.to_owned(),
                is_start: false,
                is_end: false,
                self_closing: false,
                prefix: String::new(),
                local_name: String::new(),
            };
        }
        let is_end = body.starts_with("</");
        let name: String = body
            .trim_start_matches("</")
            .trim_start_matches('<')
            .chars()
            .take_while(|c| !c.is_whitespace() && *c != '/' && *c != '>')
            .collect();
        let (prefix, local) = match name.split_once(':') {
            Some((p, l)) => (format!("{p}:"), l.to_owned()),
            None => (String::new(), name),
        };
        Tag {
            text: body.to_owned(),
            is_start: !is_end,
            is_end,
            self_closing: body.ends_with("/>"),
            prefix,
            local_name: local,
        }
    }
}

/// Copies the original rows above `first_row` (blank-looking rows the reader skipped, which may still carry
/// formatting) and stops at the first row of the table. Returns whether it reached the end of `<sheetData>`.
fn copy_rows_above(cursor: &mut Cursor<'_>, out: &mut String, first_row: u64) -> bool {
    let mut copying = false;
    loop {
        let Some(c) = cursor.next() else { return true };
        if c != '<' {
            if copying {
                out.push(c);
            }
            continue;
        }
        let tag = cursor.read_tag();
        if copying {
            out.push_str(&tag.text);
            if tag.is_end && tag.local_name == "row" {
                copying = false;
            }
            continue;
        }
        if tag.is_end && tag.local_name == "sheetData" {
            return true;
        }
        if tag.is_start && tag.local_name == "row" {
            let number = attribute(&tag.text, "r").and_then(|r| r.parse::<u64>().ok());
            match number {
                Some(n) if n < first_row => {
                    out.push_str(&tag.text);
                    if !tag.self_closing {
                        copying = true;
                    }
                }
                _ => return false,
            }
        }
    }
}

/// Sets the end row of a filter's `ref` that starts at the table's first row; any other filter stays.
fn resize_filter(tag: &str, first_row: u64, last_row: u64) -> String {
    let Some(r) = attribute(tag, "ref") else {
        return tag.to_owned();
    };
    let Some(m) = FILTER_REF.captures(&r) else {
        return tag.to_owned();
    };
    if m[2].parse::<u64>().ok() != Some(first_row) {
        return tag.to_owned();
    }
    set_attribute(
        tag,
        "ref",
        &format!("{}{first_row}:{}{last_row}", &m[1], &m[3]),
    )
}

macro_rules! regex {
    ($name:ident, $re:expr) => {
        static $name: LazyLock<Regex> = LazyLock::new(|| Regex::new($re).unwrap());
    };
}
regex!(SHEET_ELEMENT, r"<(?:\w+:)?sheet\b[^>]*?/>");
regex!(SHEETS_CLOSE, r"</(\w+:)?sheets>");
regex!(
    DEFINED_NAME,
    r"(?s)<((?:\w+:)?definedName)\b[^>]*>(.*?)</(?:\w+:)?definedName>"
);
regex!(RELATIONSHIP_ELEMENT, r"<(?:\w+:)?Relationship\b[^>]*?/>");
regex!(RELATIONSHIPS_CLOSE, r"</(\w+:)?Relationships>");
regex!(OVERRIDE_ELEMENT, r"<(?:\w+:)?Override\b[^>]*?/>");
regex!(TYPES_CLOSE, r"</(\w+:)?Types>");
regex!(WORKBOOK_PR, r"<(?:\w+:)?workbookPr\b[^>]*>");
regex!(WORKBOOK_OPEN, r"<(\w+:)?workbook\b");
regex!(RELATION_PREFIX, r"\s(\w+):id\s*=");
regex!(ACTIVE_TAB, r#"\b(activeTab|firstSheet)="\d+""#);
regex!(FILTER_REF, r"^([A-Z]+)(\d+):([A-Z]+)(\d+)$");
regex!(
    ROW_ELEMENT,
    r"(?s)(<(?:\w+:)?row\b[^>]*?)(?:/>|>(.*?)</(?:\w+:)?row>)"
);
regex!(CELL_START, r"<(?:\w+:)?c\b[^>]*>");
regex!(VALUE_TAG, r"<(?:\w+:)?(?:v|is)\b");
regex!(FORMULA_TAG, r"<(?:\w+:)?f\b");

fn attribute(element: &str, name: &str) -> Option<String> {
    let re = Regex::new(&format!(
        r#"\s{}\s*=\s*("([^"]*)"|'([^']*)')"#,
        regex::escape(name)
    ))
    .ok()?;
    let c = re.captures(element)?;
    let value = c.get(2).or_else(|| c.get(3)).map_or("", |m| m.as_str());
    Some(unescape_attribute(value))
}

fn relation_id_attribute(element: &str) -> Option<String> {
    static RE: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"\s\w+:id\s*=\s*"([^"]*)""#).unwrap());
    RE.captures(element).map(|c| c[1].to_owned())
}

/// `value` must already be escaped.
fn set_attribute(element: &str, name: &str, value: &str) -> String {
    let re = Regex::new(&format!(
        r#"(\s{}\s*=\s*)("[^"]*"|'[^']*')"#,
        regex::escape(name)
    ))
    .unwrap();
    re.replace_all(element, |c: &regex::Captures| {
        format!("{}\"{value}\"", &c[1])
    })
    .into_owned()
}

/// Whether a formula (as stored in a defined name, XML-escaped) refers to sheet `name`.
fn references(formula: &str, name: &str) -> bool {
    rename_references(formula, name, "\u{0}") != formula
}

fn plain_name(s: &str) -> bool {
    s.chars()
        .all(|c| c.is_alphanumeric() || c == '_' || c == '.')
}

fn rename_references(formula: &str, from: &str, to: &str) -> String {
    let quoted = escape_text(&format!("'{}'!", from.replace('\'', "''")));
    let target = if plain_name(to) && to.chars().next().is_some_and(char::is_alphabetic) {
        format!("{to}!")
    } else {
        format!("'{}'!", to.replace('\'', "''"))
    };
    let target = escape_text(&target);
    let mut result = formula.replace(&quoted, &target);
    if plain_name(from) {
        // A bare `Name!` not preceded by a word character, a dot or a quote.
        let needle = format!("{from}!");
        let mut out = String::new();
        let mut rest = result.as_str();
        while let Some(i) = rest.find(&needle) {
            let before = out.chars().last().or_else(|| rest[..i].chars().last());
            let preceded = rest[..i].chars().last().or(before);
            out.push_str(&rest[..i]);
            if preceded.is_some_and(|c| c.is_alphanumeric() || c == '_' || c == '.' || c == '\'') {
                out.push_str(&needle);
            } else {
                out.push_str(&target);
            }
            rest = &rest[i + needle.len()..];
        }
        out.push_str(rest);
        result = out;
    }
    result
}

fn escape_attribute(text: &str) -> String {
    escape_text(text).replace('"', "&quot;")
}

fn unescape_attribute(text: &str) -> String {
    text.replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

/// Escapes text for XML, writing characters XML can't hold as `_xHHHH_` (Excel's own escape) and protecting literal
/// `_xHHHH_` sequences with `_x005F_`, the inverse of what the reader unescapes.
pub fn escape_text(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len() + 16);
    for (i, &c) in chars.iter().enumerate() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '_' if i + 6 < chars.len()
                && chars[i + 1] == 'x'
                && chars[i + 6] == '_'
                && u16::from_str_radix(&chars[i + 2..i + 6].iter().collect::<String>(), 16)
                    .is_ok() =>
            {
                out.push_str("_x005F_")
            }
            c if ((c as u32) < 0x20 && !matches!(c, '\t' | '\n' | '\r'))
                || c == '\u{fffe}'
                || c == '\u{ffff}' =>
            {
                out.push_str(&format!("_x{:04X}_", c as u32))
            }
            c => out.push(c),
        }
    }
    out
}

fn rels_path_of(part: &str) -> String {
    let (directory, file) = part.rsplit_once('/').unwrap_or(("", part));
    format!("{}_rels/{file}.rels", slash(directory))
}

/// `A`, `B`… `Z`, `AA`… for a 0-based column.
pub fn column_letters(index: usize) -> String {
    let mut n = index + 1;
    let mut letters = Vec::new();
    while n > 0 {
        let rem = (n - 1) % 26;
        letters.push((b'A' + rem as u8) as char);
        n = (n - 1) / 26;
    }
    letters.iter().rev().collect()
}

/// Sheet names Excel accepts: 1–31 characters, none of `[]:*?/\`, not starting or ending with an apostrophe.
pub fn validate_sheet_name(name: &str) -> Result<()> {
    let problem = if name.trim().is_empty() {
        Some("is blank")
    } else if name.encode_utf16().count() > 31 {
        Some("is longer than 31 characters")
    } else if name.chars().any(|c| "[]:*?/\\".contains(c)) {
        Some("contains one of [ ] : * ? / \\")
    } else if name.starts_with('\'') || name.ends_with('\'') {
        Some("starts or ends with an apostrophe")
    } else if name.eq_ignore_ascii_case("History") {
        Some("is reserved by Excel")
    } else {
        None
    };
    match problem {
        Some(p) => Err(DataFileError(format!(
            "Table \"{name}\" can't be a sheet: its name {p}. Rename the table to save the workbook."
        ))),
        None => Ok(()),
    }
}

/// The serial number Excel stores for an ISO date, date-time or time as the reader writes them (`2024-01-31`,
/// `2024-01-31 13:45:00`, `13:45:00`), or None when `text` isn't one. The inverse of the reader's conversion,
/// including the 1900 system's phantom leap day.
pub fn excel_serial(text: &str, date1904: bool) -> Option<String> {
    use chrono::{NaiveDate, NaiveDateTime, NaiveTime, Timelike};
    let iso = text.replace(' ', "T");
    let date_time = NaiveDateTime::parse_from_str(&iso, "%Y-%m-%dT%H:%M:%S%.f")
        .or_else(|_| NaiveDateTime::parse_from_str(&iso, "%Y-%m-%dT%H:%M"))
        .ok()
        .or_else(|| {
            NaiveDate::parse_from_str(text, "%Y-%m-%d")
                .ok()
                .and_then(|d| d.and_hms_opt(0, 0, 0))
        });
    let date_time = match date_time {
        Some(d) => d,
        None => {
            let time = NaiveTime::parse_from_str(text, "%H:%M:%S%.f")
                .or_else(|_| NaiveTime::parse_from_str(text, "%H:%M"))
                .ok()?;
            return Some(DataValues::real(
                f64::from(time.num_seconds_from_midnight()) / 86_400.0,
            ));
        }
    };
    let date = date_time.date();
    let epoch = if date1904 {
        NaiveDate::from_ymd_opt(1904, 1, 1)?
    } else if date < NaiveDate::from_ymd_opt(1900, 3, 1)? {
        NaiveDate::from_ymd_opt(1899, 12, 31)?
    } else {
        NaiveDate::from_ymd_opt(1899, 12, 30)?
    };
    let days = (date - epoch).num_days();
    if days < 0 {
        return None;
    }
    let seconds = date_time.time().num_seconds_from_midnight();
    Some(DataValues::real(
        days as f64 + f64::from(seconds) / 86_400.0,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn helpers() {
        assert_eq!(column_letters(0), "A");
        assert_eq!(column_letters(25), "Z");
        assert_eq!(column_letters(26), "AA");
        assert_eq!(column_letters(701), "ZZ");
        assert_eq!(column_letters(702), "AAA");
        assert_eq!(excel_serial("2000-01-01", false).as_deref(), Some("36526"));
        assert_eq!(
            excel_serial("2000-01-01 12:00:00", false).as_deref(),
            Some("36526.5")
        );
        assert_eq!(excel_serial("1900-02-28", false).as_deref(), Some("59"));
        assert_eq!(excel_serial("18:00:00", false).as_deref(), Some("0.75"));
        assert_eq!(excel_serial("x", false), None);
        assert_eq!(
            escape_text("a<b&_x000D_\u{1}"),
            "a&lt;b&amp;_x005F_x000D__x0001_"
        );
        assert!(validate_sheet_name("History").is_err());
        assert!(validate_sheet_name("ok name").is_ok());
        assert_eq!(
            rename_references("Old!$A$1:'Old'!B2", "Old", "New Name"),
            "'New Name'!$A$1:'New Name'!B2"
        );
        assert!(references("'My Sheet'!A1", "My Sheet"));
        assert!(!references("Other!A1", "Old"));
    }
}
