//! CSV and TSV (`importer/CsvSource.kt`): an RFC 4180 record reader tolerant of what real exports contain, encoding
//! and delimiter detection, and the streaming source.

use super::charset::{self, CharReader, Charset};
use super::{
    ImportError, ImportOptions, ImportRow, ImportSource, Result, Rows, fit_row,
    generated_column_names, normalize_column_names,
};
use std::collections::VecDeque;
use std::fs::File;
use std::io::Read;
use std::path::Path;

const NO_HEADER_SCAN: usize = 100;
const SAMPLE_RECORDS: usize = 20;
const SAMPLE_CHARS: usize = 256 * 1024;
const SNIFF_BYTES: usize = 1024 * 1024;
const DELIMITERS: [char; 4] = [',', ';', '\t', '|'];
/// Caps memory when an unclosed quote would otherwise swallow the rest of a multi-GB file.
const MAX_FIELD_CHARS: usize = 16_000_000;

#[derive(Clone, Copy, PartialEq)]
enum State {
    Start,
    Unquoted,
    Quoted,
    AfterQuote,
}

/// A stray quote inside an unquoted field is kept literally, text after a closing quote is appended (`"ab"c` ->
/// `abc`), spaces before an opening quote are padding, and blank lines are skipped. Line breaks may be CRLF, LF or
/// CR; inside quotes they are kept verbatim.
pub struct CsvParser {
    reader: CharReader,
    delimiter: char,
    quote: char,
    empty_as_null: bool,
    encoding: String,
    started: bool,
    /// Physical line of the next unread character.
    pub line: u64,
    /// Physical line on which the record last returned by [`next`](Self::next) starts.
    pub record_line: u64,
}

impl CsvParser {
    pub fn new(
        reader: CharReader,
        delimiter: char,
        quote: char,
        empty_as_null: bool,
        encoding: &str,
    ) -> Self {
        Self {
            reader,
            delimiter,
            quote,
            empty_as_null,
            encoding: encoding.to_owned(),
            started: false,
            line: 1,
            record_line: 0,
        }
    }

    fn read(&mut self) -> Result<Option<char>> {
        let c = self.reader.next_char().map_err(|_| {
            ImportError::at(
                format!(
                    "The file isn't valid {} text near line {}; choose its encoding (e.g. windows-1252) and try again",
                    self.encoding, self.line
                ),
                self.line,
            )
        })?;
        // A BOM decodes to U+FEFF; drop it so it doesn't end up in the first header name.
        if !self.started {
            self.started = true;
            if c == Some('\u{feff}') {
                return self.read();
            }
        }
        Ok(c)
    }

    /// After a CR: swallows the LF of a CRLF.
    fn skip_lf(&mut self) -> Result<()> {
        match self.read()? {
            Some('\n') | None => {}
            Some(c) => self.reader.unread(c),
        }
        Ok(())
    }

    /// Skips `count` physical lines (ignoring quotes: these are report titles and the like, not records).
    pub fn skip_lines(&mut self, count: u64) -> Result<()> {
        let mut left = count;
        while left > 0 {
            match self.read()? {
                None => return Ok(()),
                Some('\n') => {
                    self.line += 1;
                    left -= 1;
                }
                Some('\r') => {
                    self.skip_lf()?;
                    self.line += 1;
                    left -= 1;
                }
                _ => {}
            }
        }
        Ok(())
    }

    /// The next record's fields, or None at the end of input.
    #[allow(clippy::should_implement_trait, unused_assignments)]
    pub fn next(&mut self) -> Result<Option<Vec<Option<String>>>> {
        let (q, d) = (self.quote, self.delimiter);
        loop {
            self.record_line = self.line;
            let mut fields: Vec<Option<String>> = Vec::new();
            let mut field = String::new();
            let mut state = State::Start;
            let mut quoted = false;
            let mut first_quoted = false;
            macro_rules! end_field {
                () => {{
                    if fields.is_empty() {
                        first_quoted = quoted;
                    }
                    fields.push(if !quoted && self.empty_as_null && field.is_empty() {
                        None
                    } else {
                        Some(std::mem::take(&mut field))
                    });
                    field.clear();
                    quoted = false;
                    state = State::Start;
                }};
            }
            macro_rules! append {
                ($c:expr) => {{
                    if field.len() >= MAX_FIELD_CHARS {
                        return Err(ImportError::at(
                            format!(
                                "A field in the record starting on line {} is longer than {}M characters; the file probably has an unclosed quote or a wrong delimiter",
                                self.record_line,
                                MAX_FIELD_CHARS / 1_000_000
                            ),
                            self.record_line,
                        ));
                    }
                    field.push($c);
                }};
            }
            loop {
                let c = self.read()?;
                match state {
                    State::Start => match c {
                        None => {
                            if fields.is_empty() && field.is_empty() {
                                return Ok(None);
                            }
                            end_field!();
                            break;
                        }
                        Some(c) if c == q => {
                            field.clear();
                            quoted = true;
                            state = State::Quoted;
                        }
                        Some(c) if c == d => end_field!(),
                        Some('\n') => {
                            self.line += 1;
                            end_field!();
                            break;
                        }
                        Some('\r') => {
                            self.skip_lf()?;
                            self.line += 1;
                            end_field!();
                            break;
                        }
                        // Stay in Start so `a, "b"` still reads b as quoted; the spaces are dropped if a quote follows.
                        Some(' ') => append!(' '),
                        Some(c) => {
                            append!(c);
                            state = State::Unquoted;
                        }
                    },
                    State::Unquoted => match c {
                        None => {
                            end_field!();
                            break;
                        }
                        Some(c) if c == d => end_field!(),
                        Some('\n') => {
                            self.line += 1;
                            end_field!();
                            break;
                        }
                        Some('\r') => {
                            self.skip_lf()?;
                            self.line += 1;
                            end_field!();
                            break;
                        }
                        Some(c) => append!(c),
                    },
                    State::Quoted => match c {
                        None => {
                            return Err(ImportError::at(
                                format!(
                                    "Unclosed quote in the record starting on line {}",
                                    self.record_line
                                ),
                                self.record_line,
                            ));
                        }
                        Some(c) if c == q => state = State::AfterQuote,
                        Some('\n') => {
                            append!('\n');
                            self.line += 1;
                        }
                        Some('\r') => {
                            append!('\r');
                            match self.read()? {
                                Some('\n') => append!('\n'),
                                Some(other) => self.reader.unread(other),
                                None => {}
                            }
                            self.line += 1;
                        }
                        Some(c) => append!(c),
                    },
                    State::AfterQuote => match c {
                        Some(c) if c == q => {
                            append!(c);
                            state = State::Quoted;
                        }
                        None => {
                            end_field!();
                            break;
                        }
                        Some(c) if c == d => end_field!(),
                        Some('\n') => {
                            self.line += 1;
                            end_field!();
                            break;
                        }
                        Some('\r') => {
                            self.skip_lf()?;
                            self.line += 1;
                            end_field!();
                            break;
                        }
                        Some(' ') => {}
                        Some(c) => {
                            append!(c);
                            state = State::Unquoted;
                        }
                    },
                }
            }
            if fields.len() == 1
                && !first_quoted
                && fields[0].as_deref().is_none_or(|f| f.trim().is_empty())
            {
                continue;
            }
            return Ok(Some(fields));
        }
    }
}

fn file_reader(path: &Path) -> Result<Box<dyn Read + Send>> {
    Ok(Box::new(std::io::BufReader::new(File::open(path)?)))
}

/// BOM first; else UTF-8 if the first MB decodes cleanly (a cut multi-byte sequence at the end is fine), else
/// windows-1252.
pub fn detect_charset(path: &Path) -> Result<Charset> {
    let mut head = Vec::new();
    File::open(path)?
        .take(SNIFF_BYTES as u64)
        .read_to_end(&mut head)?;
    if let Some((c, _)) = charset::bom_charset(&head) {
        return Ok(c);
    }
    let complete = head.len() < SNIFF_BYTES;
    let valid = match std::str::from_utf8(&head) {
        Ok(_) => true,
        // A sequence cut off by the sample's end isn't an error.
        Err(e) => e.error_len().is_none() && !complete,
    };
    Ok(if valid {
        charset::UTF_8
    } else {
        charset::WINDOWS_1252
    })
}

/// Strict for UTF-8 so a misdetected file fails loudly (naming the line) instead of importing U+FFFD; single-byte
/// code pages map nearly every byte, and the few gaps are replaced.
fn strict(charset: Charset) -> bool {
    charset == charset::UTF_8
}

/// Up to [`SAMPLE_CHARS`] characters after `skip` physical lines; the flag says whether that's the whole rest of the
/// file.
fn read_sample(path: &Path, charset: Charset, skip: u64) -> Result<(String, bool)> {
    let mut reader = CharReader::new(file_reader(path)?, charset, false);
    let mut next = || reader.next_char().ok().flatten();
    let mut left = skip;
    let mut carry = None;
    while left > 0 {
        match next() {
            None => return Ok((String::new(), true)),
            Some('\n') => left -= 1,
            Some('\r') => {
                left -= 1;
                match next() {
                    Some('\n') | None => {}
                    Some(c) => carry = Some(c),
                }
            }
            _ => {}
        }
    }
    let mut text: String = carry.into_iter().collect();
    let mut count = text.chars().count();
    let mut complete = true;
    while count < SAMPLE_CHARS {
        match next() {
            Some(c) => {
                text.push(c);
                count += 1;
            }
            None => break,
        }
    }
    if count >= SAMPLE_CHARS {
        complete = next().is_none();
    }
    Ok((
        text.strip_prefix('\u{feff}')
            .map(str::to_owned)
            .unwrap_or(text),
        complete,
    ))
}

/// Picks the candidate whose field count (above 1) is shared by the most of the first [`SAMPLE_RECORDS`] records,
/// parsing with quotes so delimiters inside quoted text don't count. Ties go to the wider split, then to list order;
/// nothing splitting means a one-column file, read with a comma.
pub fn detect_delimiter(sample: &(String, bool), quote: char) -> char {
    let (text, complete) = sample;
    let mut best = ',';
    let mut best_score = (0usize, 0usize);
    for candidate in DELIMITERS {
        if candidate == quote {
            continue;
        }
        let reader = CharReader::new(
            Box::new(std::io::Cursor::new(text.clone().into_bytes())),
            charset::UTF_8,
            false,
        );
        let mut parser = CsvParser::new(reader, candidate, quote, true, "UTF-8");
        let mut counts = Vec::new();
        let mut hit_end = false;
        while counts.len() <= SAMPLE_RECORDS {
            match parser.next() {
                Ok(Some(record)) => counts.push(record.len()),
                Ok(None) => {
                    hit_end = true;
                    break;
                }
                // An unclosed quote in a cut-off sample: the records before it are still evidence.
                Err(_) => break,
            }
        }
        // The last record of a cut-off sample may be partial.
        if hit_end && !complete {
            counts.pop();
        }
        let mut tally: std::collections::BTreeMap<usize, usize> = std::collections::BTreeMap::new();
        for width in counts.iter().take(SAMPLE_RECORDS).filter(|w| **w > 1) {
            *tally.entry(*width).or_default() += 1;
        }
        let Some((width, matches)) = tally.into_iter().max_by_key(|(w, m)| (*m, *w)) else {
            continue;
        };
        if (matches, width) > best_score {
            best = candidate;
            best_score = (matches, width);
        }
    }
    best
}

/// A CSV/TSV file. Opening reads a bounded sample to detect the encoding and delimiter, then streams records;
/// without a header the first [`NO_HEADER_SCAN`] records are buffered to size the columns.
pub struct CsvSource {
    options: ImportOptions,
    columns: Vec<String>,
    parser: CsvParser,
    pending: VecDeque<ImportRow>,
    consumed: bool,
}

impl CsvSource {
    pub fn open(path: &Path, requested: &ImportOptions) -> Result<Self> {
        let charset = match requested.charset {
            Some(c) => c,
            None => detect_charset(path)?,
        };
        let delimiter = match requested.delimiter {
            Some(d) => d,
            None => detect_delimiter(
                &read_sample(path, charset, requested.skip_rows)?,
                requested.quote,
            ),
        };
        let options = ImportOptions {
            charset: Some(charset),
            delimiter: Some(delimiter),
            ..requested.clone()
        };
        let reader = CharReader::new(file_reader(path)?, charset, strict(charset));
        let mut parser = CsvParser::new(
            reader,
            delimiter,
            requested.quote,
            requested.empty_as_null,
            charset.name(),
        );
        parser.skip_lines(requested.skip_rows)?;
        let mut pending = VecDeque::new();
        let columns = if requested.has_header {
            normalize_column_names(&parser.next()?.unwrap_or_default())
        } else {
            while pending.len() < NO_HEADER_SCAN {
                let Some(values) = parser.next()? else { break };
                pending.push_back(ImportRow {
                    line: parser.record_line,
                    values,
                });
            }
            generated_column_names(pending.iter().map(|r| r.values.len()).max().unwrap_or(0))
        };
        Ok(Self {
            options,
            columns,
            parser,
            pending,
            consumed: false,
        })
    }
}

impl ImportSource for CsvSource {
    fn columns(&self) -> &[String] {
        &self.columns
    }

    fn options(&self) -> &ImportOptions {
        &self.options
    }

    fn rows(&mut self) -> Result<Rows<'_>> {
        if self.consumed {
            return Err(ImportError::new("rows() can only be read once"));
        }
        self.consumed = true;
        let width = self.columns.len();
        let mut failed = false;
        Ok(Box::new(std::iter::from_fn(move || {
            if failed {
                return None;
            }
            let row = match self.pending.pop_front() {
                Some(r) => Ok(Some(r)),
                None => self.parser.next().map(|v| {
                    v.map(|values| ImportRow {
                        line: self.parser.record_line,
                        values,
                    })
                }),
            };
            let result = match row {
                Ok(Some(r)) => Some(fit_row(r.line, r.values, width, "Line")),
                Ok(None) => None,
                Err(e) => Some(Err(e)),
            };
            failed = matches!(result, Some(Err(_)));
            result
        })))
    }
}
