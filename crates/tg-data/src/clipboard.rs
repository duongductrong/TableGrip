//! A block of cells on the clipboard (`ClipboardTable.kt`): read from tab-separated text, and written as an HTML table
//! for spreadsheets.

pub struct ClipboardTable;

impl ClipboardTable {
    /// Splits tab-separated `text` into rows of cells, as `ResultWriter` and spreadsheets write it: a cell in double
    /// quotes may hold tabs, line breaks and doubled quotes, while a quote that doesn't close the cell is kept as
    /// text. The one line break spreadsheets add at the end is dropped. Rows may differ in length.
    pub fn parse(text: &str) -> Vec<Vec<String>> {
        let body = text.strip_suffix('\n').unwrap_or(text);
        let body = body.strip_suffix('\r').unwrap_or(body);
        if body.is_empty() {
            return Vec::new();
        }
        let body: Vec<char> = body.chars().collect();
        let mut rows = Vec::new();
        let mut row = Vec::new();
        let mut cell = String::new();
        let mut i = 0;
        loop {
            if i < body.len()
                && body[i] == '"'
                && let Some(end) = closing_quote(&body, i)
            {
                let inner: String = body[i + 1..end].iter().collect();
                cell.push_str(&inner.replace("\"\"", "\""));
                i = end + 1;
            }
            while i < body.len() && !matches!(body[i], '\t' | '\n' | '\r') {
                cell.push(body[i]);
                i += 1;
            }
            row.push(std::mem::take(&mut cell));
            if i >= body.len() {
                break;
            }
            if body[i] == '\t' {
                i += 1;
            } else {
                i += if body[i] == '\r' && body.get(i + 1) == Some(&'\n') {
                    2
                } else {
                    1
                };
                rows.push(std::mem::take(&mut row));
            }
        }
        rows.push(row);
        rows
    }

    /// `rows` as an HTML table, with `header` as its first row; NULLs are empty cells and line breaks stay in their
    /// cell.
    pub fn html(header: Option<&[String]>, rows: &[Vec<Option<String>>]) -> String {
        let mut out = String::from("<table>");
        if let Some(names) = header {
            out.push_str("<tr>");
            for name in names {
                out.push_str("<th>");
                out.push_str(&escape(name));
                out.push_str("</th>");
            }
            out.push_str("</tr>");
        }
        for row in rows {
            out.push_str("<tr>");
            for cell in row {
                out.push_str("<td>");
                out.push_str(&escape(cell.as_deref().unwrap_or("")));
                out.push_str("</td>");
            }
            out.push_str("</tr>");
        }
        out.push_str("</table>");
        out
    }
}

/// Where the quoted cell starting at `start` ends: a quote followed by a separator or the end; None when there's none.
fn closing_quote(text: &[char], start: usize) -> Option<usize> {
    let mut j = start + 1;
    while j < text.len() {
        if text[j] == '"' {
            if text.get(j + 1) == Some(&'"') {
                j += 2;
                continue;
            }
            return match text.get(j + 1) {
                None | Some('\t' | '\n' | '\r') => Some(j),
                _ => None,
            };
        }
        j += 1;
    }
    None
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\n', "<br>")
}
