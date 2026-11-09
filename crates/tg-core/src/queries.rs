//! Saved queries on disk: one `.sql` file each, folders as plain directories (`queries/QueryLibrary.kt`).

use regex::Regex;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

/// A saved query: a `.sql` file under the library's root. `folder` is its directory relative to the root (`""` at the
/// top, `reports/monthly` nested). `connection` is the profile it runs on, by name, from its `-- connection: ...` first
/// line; None runs it on the current connection.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SavedQuery {
    pub file: PathBuf,
    pub name: String,
    pub folder: String,
    pub connection: Option<String>,
    pub sql: String,
}

// Java's `\s` is ASCII-only and its `.` stops at every line terminator.
static CONNECTION_LINE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)^--[ \t\n\x0B\f\r]*connection:[ \t\n\x0B\f\r]*([^\n\r\x{85}\x{2028}\x{2029}]+?)[ \t\n\x0B\f\r]*$")
        .unwrap()
});
static UNSAFE_NAME: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r#"[\\/:*?"<>|\x00-\x1F]"#).unwrap());

#[derive(Clone, Debug)]
pub struct QueryLibrary {
    pub root: PathBuf,
}

impl QueryLibrary {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    /// Every saved query, by folder then name. Unreadable files are skipped.
    pub fn list(&self) -> Vec<SavedQuery> {
        let mut files = Vec::new();
        walk(&self.root, &mut files);
        files.sort();
        let mut queries: Vec<SavedQuery> = files
            .iter()
            .filter(|f| f.extension().is_some_and(|e| e.eq_ignore_ascii_case("sql")))
            .filter_map(|f| self.read(f).ok())
            .collect();
        queries.sort_by_cached_key(|q| (q.folder.to_lowercase(), q.name.to_lowercase()));
        queries
    }

    pub fn read(&self, file: &Path) -> io::Result<SavedQuery> {
        let text = String::from_utf8(std::fs::read(file)?)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        let folder = file
            .parent()
            .and_then(|p| p.strip_prefix(&self.root).ok())
            .map(|p| {
                p.components()
                    .map(|c| c.as_os_str().to_string_lossy().into_owned())
                    .collect::<Vec<_>>()
                    .join("/")
            })
            .unwrap_or_default();
        let name = file
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        Ok(SavedQuery {
            file: file.to_path_buf(),
            name,
            folder,
            connection: connection_of(&text),
            sql: text,
        })
    }

    /// Where a query named `name` in `folder` lives; names become safe file names.
    pub fn file_for(&self, name: &str, folder: &str) -> PathBuf {
        let mut directory = self.root.clone();
        for part in folder
            .split(['/', '\\'])
            .map(safe_name)
            .filter(|p| !p.is_empty())
        {
            directory.push(part);
        }
        let name = safe_name(name);
        let name = if name.is_empty() {
            "query".to_string()
        } else {
            name
        };
        directory.join(format!("{name}.sql"))
    }

    /// Writes `sql` as `name` in `folder`, replacing an existing file of that name. With a `connection`, the file
    /// starts with its `-- connection:` line (replacing any there was); None removes it.
    pub fn save(
        &self,
        name: &str,
        folder: &str,
        sql: &str,
        connection: Option<&str>,
    ) -> io::Result<SavedQuery> {
        let file = self.file_for(name, folder);
        if let Some(parent) = file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&file, with_connection(sql, connection))?;
        self.read(&file)
    }

    /// Moves `query` to `name` in `folder` (its own when None); refuses to overwrite another query.
    pub fn rename(
        &self,
        query: &SavedQuery,
        name: &str,
        folder: Option<&str>,
    ) -> io::Result<SavedQuery> {
        let target = self.file_for(name, folder.unwrap_or(&query.folder));
        if target == query.file {
            return Ok(query.clone());
        }
        if target.exists() {
            let name = target
                .file_stem()
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_default();
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                format!("A query named {name} already exists there"),
            ));
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::rename(&query.file, &target)?;
        self.read(&target)
    }

    pub fn delete(&self, query: &SavedQuery) -> io::Result<()> {
        match std::fs::remove_file(&query.file) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}

fn walk(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            walk(&path, out);
        } else if path.is_file() {
            out.push(path);
        }
    }
}

/// The text up to the first line break (`\n`, `\r\n` or `\r`).
fn first_line(text: &str) -> &str {
    text.find(['\n', '\r']).map_or(text, |i| &text[..i])
}

/// The connection named on `sql`'s first line, if any.
pub fn connection_of(sql: &str) -> Option<String> {
    CONNECTION_LINE
        .captures(first_line(sql))
        .map(|c| c[1].to_string())
}

/// `sql` with its first-line `-- connection:` set to `connection`, or removed when None.
pub fn with_connection(sql: &str, connection: Option<&str>) -> String {
    let body = if connection_of(sql).is_some() {
        sql.split_once('\n').map_or("", |(_, rest)| rest)
    } else {
        sql
    };
    match connection {
        None => body.to_string(),
        Some(c) => format!("-- connection: {c}\n{body}"),
    }
}

fn safe_name(name: &str) -> String {
    UNSAFE_NAME
        .replace_all(name.trim(), "_")
        .trim_matches(['.', ' '])
        .to_string()
}
