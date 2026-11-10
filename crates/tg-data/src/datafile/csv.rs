//! CSV and TSV (`datafile/CsvFormat.kt`): one table, named after the file. Reading is the importer's CSV reader;
//! writing keeps the file's encoding, byte-order mark, delimiter and line ending, and quotes only fields that need
//! it. An empty string is written `""` so it reads back as empty, not as a missing value.

use super::{
    DataFileDocument, DataFileError, DataValues, OutputTable, Result, SourceRows, TableInfo,
    table_name,
};
use crate::importer::charset::{self, Charset};
use crate::importer::{self, ImportFormat, ImportOptions, ImportSource};
use std::collections::BTreeSet;
use std::io::{Read, Write};
use std::path::Path;
use tg_core::connection::DataFileOptions;

const LINE_ENDING_SCAN: u64 = 64 * 1024;

struct Layout {
    charset: Charset,
    bom: bool,
    delimiter: char,
    quote: char,
    has_header: bool,
    line_ending: &'static str,
}

struct Document {
    name: String,
    source: Option<Box<dyn ImportSource>>,
    columns: Vec<String>,
    layout: Layout,
}

pub fn open(path: &Path, options: &DataFileOptions) -> Result<Box<dyn DataFileDocument>> {
    let charset = match &options.charset {
        Some(name) => Some(
            charset::for_name(name)
                .ok_or_else(|| DataFileError(format!("Unknown encoding \"{name}\"")))?,
        ),
        None => None,
    };
    let lower = path.to_string_lossy().to_lowercase();
    let mut import = ImportOptions::new(ImportFormat::Csv);
    import.delimiter = options
        .delimiter
        .or_else(|| (lower.ends_with(".tsv") || lower.ends_with(".tab")).then_some('\t'));
    import.has_header = options.has_header;
    import.charset = charset;
    let source = importer::open(path, &import)?;
    let mut head = Vec::new();
    std::fs::File::open(path)?
        .take(LINE_ENDING_SCAN)
        .read_to_end(&mut head)?;
    let detected = source.options().charset.unwrap_or(charset::UTF_8);
    let (text, _, _) = detected.decode(&head);
    let layout = Layout {
        charset: detected,
        bom: charset::bom_charset(&head).is_some_and(|(c, _)| c == detected),
        delimiter: source.options().delimiter.unwrap_or(','),
        quote: source.options().quote,
        has_header: options.has_header,
        line_ending: if text.contains("\r\n") { "\r\n" } else { "\n" },
    };
    Ok(Box::new(Document {
        name: table_name(path),
        columns: source.columns().to_vec(),
        source: Some(source),
        layout,
    }))
}

impl Document {
    fn field(&self, value: &str) -> String {
        let q = self.layout.quote;
        let needs = value.is_empty()
            || value
                .chars()
                .any(|c| c == self.layout.delimiter || c == q || c == '\n' || c == '\r');
        if needs {
            format!("{q}{}{q}", value.replace(q, &format!("{q}{q}")))
        } else {
            value.to_owned()
        }
    }
}

impl DataFileDocument for Document {
    fn tables(&self) -> Vec<TableInfo> {
        vec![TableInfo {
            name: self.name.clone(),
            columns: self.columns.clone(),
        }]
    }

    fn rows(&mut self, _index: usize) -> Result<SourceRows<'_>> {
        let source = self
            .source
            .as_mut()
            .ok_or_else(|| DataFileError("The file's rows were already read".into()))?;
        let rows = source.rows()?;
        Ok(Box::new(
            rows.map(|r| r.map(|r| r.values).map_err(DataFileError::from)),
        ))
    }

    fn multi_table(&self) -> bool {
        false
    }

    fn write(
        &mut self,
        target: &Path,
        tables: &[OutputTable<'_>],
        _removed: &BTreeSet<String>,
    ) -> Result<()> {
        let table = &tables[0];
        let layout = &self.layout;
        let mut out = std::io::BufWriter::with_capacity(64 * 1024, std::fs::File::create(target)?);
        if layout.bom {
            out.write_all(match layout.charset {
                c if c == charset::UTF_16BE => &[0xFE, 0xFF][..],
                c if c == charset::UTF_16LE => &[0xFF, 0xFE][..],
                _ => &[0xEF, 0xBB, 0xBF][..],
            })?;
        }
        let mut line = 0u64;
        let mut record = |values: &[Option<String>], out: &mut dyn Write| -> Result<()> {
            line += 1;
            let mut text = String::new();
            for (i, value) in values.iter().enumerate() {
                if i > 0 {
                    text.push(layout.delimiter);
                }
                if let Some(v) = value {
                    text.push_str(&self.field(v));
                }
            }
            text.push_str(layout.line_ending);
            let bytes = charset::encode(&text, layout.charset).ok_or_else(|| {
                DataFileError(format!(
                    "Line {line} has characters {} can't hold. Change the connection's encoding to UTF-8.",
                    layout.charset.name()
                ))
            })?;
            out.write_all(&bytes)?;
            Ok(())
        };
        if layout.has_header {
            let names: Vec<Option<String>> =
                table.columns.iter().map(|c| Some(c.name.clone())).collect();
            record(&names, &mut out)?;
        }
        for row in (table.rows)()? {
            let values: Vec<Option<String>> = row.iter().map(DataValues::text).collect();
            record(&values, &mut out)?;
        }
        out.flush()?;
        Ok(())
    }
}
