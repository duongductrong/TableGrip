//! Backups and restores (`backup/Backup.kt`): which engines are backed up and how, the tool command lines, and running
//! them with their secrets kept off the command line. Schedules live in `tg_core::backup`.

use crate::tools::{Tool, ToolFamily};
use indexmap::IndexMap;
use regex::Regex;
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::LazyLock;
use tg_core::connection::ConnectionConfig;
use tg_sql::dialect::SqlDialect;
use tg_sql::model::{DatabaseType, EngineFamily, Paradigm};

pub use tg_core::backup::discard;

/// How an engine is backed up, if at all.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BackupSupport {
    /// With the family's command-line tools.
    WithTools(ToolFamily),
    /// By the database itself, into a file or folder: SQLite's `VACUUM INTO`, DuckDB's `EXPORT DATABASE`.
    Native(BackupFormat),
    NotYet(String),
    /// Key-value and document stores: no Backup item at all.
    NotApplicable,
}

impl BackupSupport {
    pub fn of(db_type: &DatabaseType) -> Self {
        if db_type.paradigm != Paradigm::Relational || db_type.is_data_file() {
            // A data file is the data; its working copy is scratch.
            Self::NotApplicable
        } else if *db_type == DatabaseType::SQLITE {
            Self::Native(BackupFormat::SqliteCopy)
        } else if *db_type == DatabaseType::DUCKDB {
            Self::Native(BackupFormat::DuckDbExport)
        } else if db_type.family == EngineFamily::PostgreSQL
            && db_type.dialect() != Some(SqlDialect::CockroachDB)
        {
            // pg_dump can't read CockroachDB's catalog; TiDB and PlanetScale have their own dump tools.
            Self::WithTools(ToolFamily::PostgreSQL)
        } else if *db_type == DatabaseType::MYSQL || *db_type == DatabaseType::MARIADB {
            Self::WithTools(ToolFamily::MySQL)
        } else {
            Self::NotYet(format!(
                "Backups don't support {} yet. They support PostgreSQL, MySQL, MariaDB, SQLite and DuckDB.",
                db_type.display_name
            ))
        }
    }

    /// Restoring needs the tools; a SQLite or DuckDB backup is opened as a database of its own instead.
    pub fn restore_of(db_type: &DatabaseType) -> Self {
        match Self::of(db_type) {
            Self::Native(_) => Self::NotYet(format!(
                "A {} backup is a database of its own: add it as a new connection to open it.",
                db_type.display_name
            )),
            other => other,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum BackupFormat {
    PgCustom,
    PgPlain,
    PgDirectory,
    MySqlSql,
    SqliteCopy,
    DuckDbExport,
}

impl BackupFormat {
    pub fn label(self) -> &'static str {
        match self {
            Self::PgCustom => "Custom (compressed, restore selectively)",
            Self::PgPlain => "Plain SQL",
            Self::PgDirectory => "Directory (parallel)",
            Self::MySqlSql => "SQL",
            Self::SqliteCopy => "Database file",
            Self::DuckDbExport => "Export folder (schema + Parquet)",
        }
    }

    pub fn extension(self) -> Option<&'static str> {
        match self {
            Self::PgCustom => Some("dump"),
            Self::PgPlain | Self::MySqlSql => Some("sql"),
            Self::SqliteCopy => Some("db"),
            Self::PgDirectory | Self::DuckDbExport => None,
        }
    }

    pub fn is_folder(self) -> bool {
        matches!(self, Self::PgDirectory | Self::DuckDbExport)
    }

    pub fn for_family(family: ToolFamily) -> &'static [BackupFormat] {
        match family {
            ToolFamily::PostgreSQL => &[Self::PgCustom, Self::PgPlain, Self::PgDirectory],
            ToolFamily::MySQL => &[Self::MySqlSql],
        }
    }

    /// What a backup file was made as, from its name: folders are directory dumps, `.sql` is plain.
    pub fn of_file(path: &Path) -> Self {
        if path.is_dir() {
            Self::PgDirectory
        } else if path
            .file_name()
            .is_some_and(|n| n.to_string_lossy().to_lowercase().ends_with(".sql"))
        {
            Self::PgPlain
        } else {
            Self::PgCustom
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum BackupContent {
    #[default]
    All,
    SchemaOnly,
    DataOnly,
}

impl BackupContent {
    pub fn label(self) -> &'static str {
        match self {
            Self::All => "Schema and data",
            Self::SchemaOnly => "Schema only",
            Self::DataOnly => "Data only",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BackupOptions {
    pub format: BackupFormat,
    /// The file, or the folder for [`BackupFormat::is_folder`] formats.
    pub target: PathBuf,
    pub database: String,
    pub content: BackupContent,
    /// Limits the backup to these schemas (PostgreSQL); empty means all.
    pub schemas: Vec<String>,
    /// Limits the backup to these tables, `schema.table` on PostgreSQL; empty means all.
    pub tables: Vec<String>,
    pub no_owner: bool,
    pub no_privileges: bool,
    /// Plain SQL only: DROP each object before creating it.
    pub clean: bool,
    /// Parallel jobs for [`BackupFormat::PgDirectory`].
    pub jobs: u32,
    /// MySQL: also routines, triggers and events.
    pub routines: bool,
}

impl BackupOptions {
    pub fn new(format: BackupFormat, target: impl Into<PathBuf>, database: &str) -> Self {
        Self {
            format,
            target: target.into(),
            database: database.to_owned(),
            content: BackupContent::All,
            schemas: Vec::new(),
            tables: Vec::new(),
            no_owner: false,
            no_privileges: false,
            clean: false,
            jobs: 4,
            routines: true,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RestoreOptions {
    pub source: PathBuf,
    pub format: BackupFormat,
    /// The database restored into.
    pub database: String,
    pub no_owner: bool,
    pub no_privileges: bool,
    /// Drop objects before recreating them (custom and directory backups).
    pub clean: bool,
    pub jobs: u32,
    /// `pg_restore --list` entries to restore; None restores everything.
    pub entries: Option<Vec<RestoreEntry>>,
}

impl RestoreOptions {
    pub fn new(source: impl Into<PathBuf>, format: BackupFormat, database: &str) -> Self {
        Self {
            source: source.into(),
            format,
            database: database.to_owned(),
            no_owner: false,
            no_privileges: false,
            clean: false,
            jobs: 1,
            entries: None,
        }
    }
}

/// One line of `pg_restore --list`, e.g. `215; 1259 16386 TABLE public orders postgres`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RestoreEntry {
    pub line: String,
    pub kind: String,
    pub schema: Option<String>,
    pub name: String,
}

static ENTRY: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^\d+;\s*\d+\s+\d+\s+(.+)$").unwrap());

const KINDS: &[&str] = &[
    "TABLE DATA",
    "SEQUENCE SET",
    "SEQUENCE OWNED BY",
    "FK CONSTRAINT",
    "MATERIALIZED VIEW DATA",
    "MATERIALIZED VIEW",
    "DEFAULT ACL",
    "SCHEMA",
    "TABLE",
    "VIEW",
    "SEQUENCE",
    "INDEX",
    "CONSTRAINT",
    "TRIGGER",
    "FUNCTION",
    "PROCEDURE",
    "TYPE",
    "DOMAIN",
    "EXTENSION",
    "COMMENT",
    "ACL",
    "DEFAULT",
    "POLICY",
    "RULE",
    "AGGREGATE",
    "PUBLICATION",
    "ENCODING",
    "STDSTRINGS",
    "SEARCHPATH",
    "DATABASE",
];

impl RestoreEntry {
    pub fn label(&self) -> String {
        let place: Vec<&str> = self
            .schema
            .iter()
            .map(String::as_str)
            .chain([self.name.as_str()])
            .collect();
        format!("{} {}", self.kind, place.join("."))
    }

    /// The restorable entries in `pg_restore --list` output; comments and blank lines are skipped.
    pub fn parse(listing: &str) -> Vec<RestoreEntry> {
        listing
            .lines()
            .filter_map(|line| {
                let line = line.trim();
                let rest = ENTRY.captures(line)?.get(1)?.as_str();
                let kind = KINDS
                    .iter()
                    .find(|k| rest.starts_with(&format!("{k} ")))
                    .map(|k| k.to_string())
                    .unwrap_or_else(|| rest.split(' ').next().unwrap_or("").to_owned());
                let words: Vec<&str> = rest
                    .strip_prefix(kind.as_str())
                    .unwrap_or(rest)
                    .trim()
                    .split(' ')
                    .collect();
                // "<schema> <name> <owner>"; schema is "-" for global objects.
                let schema = words.first().filter(|s| **s != "-").map(|s| s.to_string());
                let middle = if words.len() > 2 {
                    words[1..words.len() - 1].join(" ")
                } else {
                    String::new()
                };
                let name = if middle.trim().is_empty() {
                    words
                        .get(1)
                        .or(words.first())
                        .map(|s| s.to_string())
                        .unwrap_or_default()
                } else {
                    middle
                };
                Some(RestoreEntry {
                    line: line.to_owned(),
                    kind,
                    schema,
                    name,
                })
            })
            .collect()
    }
}

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct BackupError(pub String);

/// Host, port, user and password the tools connect with: through an SSH tunnel's local end when there is one.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ServerEndpoint {
    pub host: String,
    pub port: i32,
    pub user: String,
    pub password: Option<String>,
    pub properties: IndexMap<String, String>,
}

impl ServerEndpoint {
    /// The server `config` points at, by host fields or by its URL. Doesn't open the tunnel.
    pub fn of(config: &ConnectionConfig, password: Option<&str>) -> Result<Self, BackupError> {
        let default_port = config.db_type.default_port.ok_or_else(|| {
            BackupError(format!(
                "{} doesn't use a network connection.",
                config.db_type.display_name
            ))
        })?;
        let url = config
            .url
            .as_deref()
            .and_then(|u| tg_core::connection::string::parse(u).ok());
        let host = url
            .as_ref()
            .and_then(|u| u.host.clone())
            .unwrap_or_else(|| config.host.clone());
        if host.contains(',') {
            return Err(BackupError(
                "Backups need a single host, not a list.".into(),
            ));
        }
        Ok(Self {
            host: host.trim_matches(['[', ']']).to_owned(),
            port: url
                .and_then(|u| u.port)
                .or(config.port)
                .unwrap_or(default_port as i32),
            user: config.user.clone(),
            password: password.map(str::to_owned),
            properties: config.properties.clone(),
        })
    }
}

/// A tool run: arguments, environment and files only it may read. Secrets go in `environment` or `secret_files`, never
/// in `arguments`, which other users of the machine can see in the process list; [`ToolCommand::display`] is safe to
/// show and copy.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ToolCommand {
    pub arguments: Vec<String>,
    pub environment: IndexMap<String, String>,
    pub secret_files: BTreeMap<PathBuf, String>,
    /// Fed to the tool's standard input, e.g. a SQL file for `mysql`.
    pub input: Option<PathBuf>,
}

fn absolute(path: &Path) -> String {
    std::path::absolute(path)
        .unwrap_or_else(|_| path.to_owned())
        .to_string_lossy()
        .into_owned()
}

fn quote(text: &str) -> String {
    if !text.is_empty()
        && text
            .chars()
            .all(|c| c.is_alphanumeric() || "-_./=:@,+".contains(c))
    {
        text.to_owned()
    } else {
        format!("'{}'", text.replace('\'', "'\\''"))
    }
}

impl ToolCommand {
    /// The command as a person would type it, with the password left to an environment variable or prompt.
    pub fn display(&self) -> String {
        let mut out = String::new();
        for (key, value) in &self.environment {
            if key != "PGPASSWORD" && key != "PGAPPNAME" {
                out.push_str(&format!("{key}={} ", quote(value)));
            }
        }
        out.push_str(
            &self
                .arguments
                .iter()
                .map(|a| quote(a))
                .collect::<Vec<_>>()
                .join(" "),
        );
        if let Some(input) = &self.input {
            out.push_str(&format!(" < {}", quote(&absolute(input))));
        }
        out
    }
}

/// Builds the tool command lines. Pure, so what is run can be tested and shown before running it.
pub mod commands {
    use super::*;

    pub fn pg_dump(tool: &Path, server: &ServerEndpoint, options: &BackupOptions) -> ToolCommand {
        let mut args = vec![absolute(tool)];
        args.extend(pg_connection(server, &options.database));
        args.push(format!(
            "--format={}",
            match options.format {
                BackupFormat::PgCustom => "custom",
                BackupFormat::PgDirectory => "directory",
                _ => "plain",
            }
        ));
        if options.format == BackupFormat::PgDirectory && options.jobs > 1 {
            args.push(format!("--jobs={}", options.jobs));
        }
        match options.content {
            BackupContent::SchemaOnly => args.push("--schema-only".into()),
            BackupContent::DataOnly => args.push("--data-only".into()),
            BackupContent::All => {}
        }
        if options.no_owner {
            args.push("--no-owner".into());
        }
        if options.no_privileges {
            args.push("--no-privileges".into());
        }
        if options.clean && options.format == BackupFormat::PgPlain {
            args.extend(["--clean".into(), "--if-exists".into()]);
        }
        args.extend(options.schemas.iter().map(|s| format!("--schema={s}")));
        args.extend(options.tables.iter().map(|t| format!("--table={t}")));
        args.push("--verbose".into());
        args.push(format!("--file={}", absolute(&options.target)));
        ToolCommand {
            arguments: args,
            environment: pg_environment(server),
            ..Default::default()
        }
    }

    pub fn pg_restore_list(tool: &Path, source: &Path) -> ToolCommand {
        ToolCommand {
            arguments: vec![absolute(tool), "--list".into(), absolute(source)],
            ..Default::default()
        }
    }

    /// `pg_restore` for custom and directory backups; `list_file` holds the chosen entries, when some were left out.
    pub fn pg_restore(
        tool: &Path,
        server: &ServerEndpoint,
        options: &RestoreOptions,
        list_file: Option<&Path>,
    ) -> ToolCommand {
        let mut args = vec![absolute(tool)];
        args.extend(pg_connection(server, &options.database));
        if options.no_owner {
            args.push("--no-owner".into());
        }
        if options.no_privileges {
            args.push("--no-privileges".into());
        }
        if options.clean {
            args.extend(["--clean".into(), "--if-exists".into()]);
        }
        if options.jobs > 1 {
            args.push(format!("--jobs={}", options.jobs));
        }
        if let Some(list) = list_file {
            args.push(format!("--use-list={}", absolute(list)));
        }
        args.extend([
            "--exit-on-error".into(),
            "--verbose".into(),
            absolute(&options.source),
        ]);
        ToolCommand {
            arguments: args,
            environment: pg_environment(server),
            ..Default::default()
        }
    }

    /// `psql` for plain SQL backups, stopping at the first error.
    pub fn psql_restore(
        tool: &Path,
        server: &ServerEndpoint,
        options: &RestoreOptions,
    ) -> ToolCommand {
        let mut args = vec![absolute(tool)];
        args.extend(pg_connection(server, &options.database));
        args.extend([
            "--no-psqlrc".into(),
            "--set=ON_ERROR_STOP=1".into(),
            "--echo-errors".into(),
            format!("--file={}", absolute(&options.source)),
        ]);
        ToolCommand {
            arguments: args,
            environment: pg_environment(server),
            ..Default::default()
        }
    }

    fn pg_connection(server: &ServerEndpoint, database: &str) -> Vec<String> {
        let mut args = vec![
            format!("--host={}", server.host),
            format!("--port={}", server.port),
        ];
        if !server.user.trim().is_empty() {
            args.push(format!("--username={}", server.user));
        }
        args.push(format!("--dbname={database}"));
        args.push("--no-password".into());
        args
    }

    /// libpq reads the password and TLS mode from the environment; nothing secret is on the command line.
    fn pg_environment(server: &ServerEndpoint) -> IndexMap<String, String> {
        let mut env = IndexMap::new();
        if let Some(password) = server.password.as_ref().filter(|p| !p.is_empty()) {
            env.insert("PGPASSWORD".into(), password.clone());
        }
        env.insert("PGAPPNAME".into(), "TableGrip".into());
        env.insert("PGCONNECT_TIMEOUT".into(), "10".into());
        if let Some(mode) = server.properties.get("sslmode") {
            env.insert("PGSSLMODE".into(), mode.clone());
        }
        if let Some(cert) = server.properties.get("sslrootcert") {
            env.insert("PGSSLROOTCERT".into(), cert.clone());
        }
        env
    }

    /// `mysqldump` (or `mariadb-dump`) of the options' database, or of its tables. The dump has no `CREATE DATABASE`,
    /// so it restores into whichever database is chosen.
    pub fn mysql_dump(
        tool: &Path,
        server: &ServerEndpoint,
        options: &BackupOptions,
        secrets: &Path,
    ) -> ToolCommand {
        let mut args = vec![
            absolute(tool),
            // Must come first: the tools only read option files named before every other option.
            format!("--defaults-extra-file={}", absolute(secrets)),
            format!("--host={}", server.host),
            format!("--port={}", server.port),
            "--protocol=TCP".into(),
            "--single-transaction".into(),
        ];
        if options.routines {
            args.extend(["--routines".into(), "--triggers".into(), "--events".into()]);
        }
        match options.content {
            BackupContent::SchemaOnly => args.push("--no-data".into()),
            BackupContent::DataOnly => args.push("--no-create-info".into()),
            BackupContent::All => {}
        }
        args.push("--verbose".into());
        args.push(format!("--result-file={}", absolute(&options.target)));
        args.push(options.database.clone());
        args.extend(options.tables.iter().cloned());
        ToolCommand {
            arguments: args,
            secret_files: BTreeMap::from([(secrets.to_owned(), mysql_option_file(tool, server))]),
            ..Default::default()
        }
    }

    pub fn mysql_restore(
        tool: &Path,
        server: &ServerEndpoint,
        options: &RestoreOptions,
        secrets: &Path,
    ) -> ToolCommand {
        ToolCommand {
            arguments: vec![
                absolute(tool),
                format!("--defaults-extra-file={}", absolute(secrets)),
                format!("--host={}", server.host),
                format!("--port={}", server.port),
                "--protocol=TCP".into(),
                "--verbose".into(),
                options.database.clone(),
            ],
            secret_files: BTreeMap::from([(secrets.to_owned(), mysql_option_file(tool, server))]),
            input: Some(options.source.clone()),
            ..Default::default()
        }
    }

    /// The `[client]` option file with the user and password, written readable by the owner only.
    fn mysql_option_file(tool: &Path, server: &ServerEndpoint) -> String {
        let mut out = String::from("[client]\n");
        if !server.user.trim().is_empty() {
            out.push_str(&format!("user={}\n", option_value(&server.user)));
        }
        if let Some(password) = server.password.as_ref().filter(|p| !p.is_empty()) {
            out.push_str(&format!("password={}\n", option_value(password)));
        }
        // Oracle's tools take ssl-mode; MariaDB's take ssl, so only MySQL's get it.
        let mysql_tools = !tool
            .file_name()
            .is_some_and(|n| n.to_string_lossy().starts_with("mariadb"));
        let ssl_mode = server.properties.get("sslMode").map(|m| m.to_uppercase());
        if mysql_tools && ssl_mode.is_some_and(|m| m != "DISABLED" && m != "DISABLE") {
            out.push_str("ssl-mode=REQUIRED\n");
        }
        out
    }

    /// Quoted with backslash escapes, as MySQL option files read them.
    fn option_value(text: &str) -> String {
        format!("\"{}\"", text.replace('\\', "\\\\").replace('"', "\\\""))
    }
}

/// Which tool backs up or restores, preferring MariaDB's own names for MariaDB servers.
pub fn tools_for(db_type: &DatabaseType, restore: bool, format: BackupFormat) -> Vec<Tool> {
    let maria = *db_type == DatabaseType::MARIADB;
    if db_type.family == EngineFamily::MySQL {
        match (restore, maria) {
            (true, true) => vec![Tool::MariaDb, Tool::MySql],
            (true, false) => vec![Tool::MySql, Tool::MariaDb],
            (false, true) => vec![Tool::MariaDbDump, Tool::MySqlDump],
            (false, false) => vec![Tool::MySqlDump, Tool::MariaDbDump],
        }
    } else if !restore {
        vec![Tool::PgDump]
    } else if format == BackupFormat::PgPlain {
        vec![Tool::Psql]
    } else {
        vec![Tool::PgRestore]
    }
}

/// A finished run's outcome. `log` is what the tool printed, kept in the log file too.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToolRun {
    pub exit_code: i32,
    pub log: String,
}

#[derive(Debug, thiserror::Error)]
pub enum ToolError {
    #[error("{0}")]
    Io(#[from] std::io::Error),
    /// The tool ran and exited with an error; the message ends with its last words.
    #[error("{message}")]
    Failed { message: String, log: String },
}

/// Deletes the secret files and writes the log file however the run ends, cancelled included.
struct Cleanup<'a> {
    command: &'a ToolCommand,
    log_file: Option<&'a Path>,
    log: String,
}

impl Drop for Cleanup<'_> {
    fn drop(&mut self) {
        if let Some(file) = self.log_file {
            let _ = std::fs::write(
                file,
                format!("$ {}\n\n{}", self.command.display(), self.log),
            );
        }
        for path in self.command.secret_files.keys() {
            let _ = std::fs::remove_file(path);
        }
    }
}

/// Kills a run's whole process group unless it finished.
struct ProcessGroup(Option<u32>);

impl Drop for ProcessGroup {
    fn drop(&mut self) {
        #[cfg(unix)]
        if let Some(pid) = self.0 {
            // SAFETY: kill(2) on a process group this run created; no memory is involved.
            unsafe {
                libc::kill(-(pid as libc::pid_t), libc::SIGKILL);
            }
        }
    }
}

fn write_owner_only(path: &Path, text: &str) -> std::io::Result<()> {
    let _ = std::fs::remove_file(path);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    std::io::Write::write_all(&mut options.open(path)?, text.as_bytes())
}

/// Runs [`ToolCommand`]s: writes their secret files (owner-only), feeds `on_line` each line the tool prints (standard
/// output and error together), and kills the tool when the future is dropped. Secret files are always deleted.
pub struct ToolRunner;

impl ToolRunner {
    pub async fn run(
        command: &ToolCommand,
        log_file: Option<&Path>,
        mut on_line: impl FnMut(&str) + Send,
    ) -> Result<ToolRun, ToolError> {
        let mut cleanup = Cleanup {
            command,
            log_file,
            log: String::new(),
        };
        for (path, text) in &command.secret_files {
            write_owner_only(path, text)?;
        }
        if let Some(parent) = log_file.and_then(Path::parent) {
            std::fs::create_dir_all(parent)?;
        }
        let Some((program, args)) = command.arguments.split_first() else {
            return Err(ToolError::Io(std::io::Error::other("no command")));
        };
        let (reader, writer) = std::io::pipe()?;
        let mut process = tokio::process::Command::new(program);
        process
            .args(args)
            .envs(&command.environment)
            .stdout(Stdio::from(writer.try_clone()?))
            .stderr(Stdio::from(writer))
            .stdin(match &command.input {
                Some(input) => Stdio::from(std::fs::File::open(input)?),
                None => Stdio::null(),
            })
            .kill_on_drop(true);
        // Its own process group, so a cancelled run takes the tool's helpers with it.
        #[cfg(unix)]
        process.process_group(0);
        let mut child = process.spawn()?;
        let mut group = ProcessGroup(child.id());
        // The pipe's write ends live in the child alone now, so the reader ends when it exits.
        drop(process);
        let (lines, mut received) = tokio::sync::mpsc::unbounded_channel::<String>();
        tokio::task::spawn_blocking(move || {
            for line in BufReader::new(reader).lines() {
                let Ok(line) = line else { break };
                if lines.send(line).is_err() {
                    break;
                }
            }
        });
        while let Some(line) = received.recv().await {
            cleanup.log.push_str(&line);
            cleanup.log.push('\n');
            on_line(&line);
        }
        let status = child.wait().await?;
        group.0 = None;
        Ok(ToolRun {
            exit_code: status.code().unwrap_or(-1),
            log: cleanup.log.clone(),
        })
    }

    /// Runs `command` and fails with the tool's last words unless it exits with 0.
    pub async fn run_checked(
        command: &ToolCommand,
        what: &str,
        log_file: Option<&Path>,
        on_line: impl FnMut(&str) + Send,
    ) -> Result<ToolRun, ToolError> {
        let run = Self::run(command, log_file, on_line).await?;
        if run.exit_code != 0 {
            let lines: Vec<&str> = run.log.lines().filter(|l| !l.trim().is_empty()).collect();
            let tail = lines[lines.len().saturating_sub(6)..].join("\n");
            return Err(ToolError::Failed {
                message: format!("{what} failed (exit code {}).\n{tail}", run.exit_code),
                log: run.log,
            });
        }
        Ok(run)
    }
}
