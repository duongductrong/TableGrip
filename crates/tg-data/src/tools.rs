//! Command-line clients TableGrip runs for backups and restores (`tools/Tools.kt`): never bundled, found on the
//! machine or downloaded as official builds after the user agrees.

use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant, SystemTime};

/// Which client tools a family of engines dumps and restores with.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ToolFamily {
    PostgreSQL,
    MySQL,
}

impl ToolFamily {
    pub fn display_name(self) -> &'static str {
        match self {
            Self::PostgreSQL => "PostgreSQL",
            Self::MySQL => "MySQL / MariaDB",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Tool {
    PgDump,
    PgRestore,
    Psql,
    MySqlDump,
    MySql,
    MariaDbDump,
    MariaDb,
}

impl Tool {
    pub const ALL: [Tool; 7] = [
        Tool::PgDump,
        Tool::PgRestore,
        Tool::Psql,
        Tool::MySqlDump,
        Tool::MySql,
        Tool::MariaDbDump,
        Tool::MariaDb,
    ];

    pub fn command(self) -> &'static str {
        match self {
            Self::PgDump => "pg_dump",
            Self::PgRestore => "pg_restore",
            Self::Psql => "psql",
            Self::MySqlDump => "mysqldump",
            Self::MySql => "mysql",
            Self::MariaDbDump => "mariadb-dump",
            Self::MariaDb => "mariadb",
        }
    }

    pub fn family(self) -> ToolFamily {
        match self {
            Self::PgDump | Self::PgRestore | Self::Psql => ToolFamily::PostgreSQL,
            _ => ToolFamily::MySQL,
        }
    }

    pub fn purpose(self) -> &'static str {
        match self {
            Self::PgDump => "backs up a PostgreSQL database",
            Self::PgRestore => "restores custom and directory backups",
            Self::Psql => "restores plain SQL backups",
            Self::MySqlDump => "backs up a MySQL or MariaDB database",
            Self::MySql => "restores SQL backups",
            Self::MariaDbDump => "MariaDB's name for mysqldump",
            Self::MariaDb => "MariaDB's name for the mysql client",
        }
    }

    /// The Kotlin enum name, the key of custom paths in settings.
    pub fn name(self) -> &'static str {
        match self {
            Self::PgDump => "PgDump",
            Self::PgRestore => "PgRestore",
            Self::Psql => "Psql",
            Self::MySqlDump => "MySqlDump",
            Self::MySql => "MySql",
            Self::MariaDbDump => "MariaDbDump",
            Self::MariaDb => "MariaDb",
        }
    }

    /// The file name on this OS.
    pub fn executable(self) -> String {
        if cfg!(windows) {
            format!("{}.exe", self.command())
        } else {
            self.command().to_owned()
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ToolSource {
    /// Chosen by the user in Settings → Tools.
    Custom,
    /// Downloaded by TableGrip into `~/.tablegrip/tools`.
    Downloaded,
    /// Already installed: on PATH or in a well-known install folder.
    System,
}

impl ToolSource {
    pub fn label(self) -> &'static str {
        match self {
            Self::Custom => "Chosen by you",
            Self::Downloaded => "Downloaded",
            Self::System => "Installed on this machine",
        }
    }
}

/// A tool found on this machine. `version` is what `--version` printed, e.g. "18.6"; None if it couldn't run.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InstalledTool {
    pub tool: Tool,
    pub path: PathBuf,
    pub version: Option<String>,
    pub source: ToolSource,
}

impl InstalledTool {
    pub fn major(&self) -> Option<i32> {
        self.version.as_deref()?.split('.').next()?.parse().ok()
    }
}

/// An official build TableGrip can download after the user agrees: the archive is checked against `sha256` and only
/// `files` are kept, under `tools/<id>/`, preserving their folders (the binaries find their libraries relatively).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ToolPackage {
    pub id: String,
    pub family: ToolFamily,
    pub version: String,
    pub os: String,
    pub url: String,
    pub sha256: String,
    pub download_bytes: u64,
    pub publisher: String,
    pub license: String,
    /// Archive entries to keep.
    pub files: Vec<String>,
    /// Prefix to strip from entries, so tools land in `bin/` and `lib/`.
    pub strip_prefix: String,
}

impl ToolPackage {
    pub fn tools(&self) -> Vec<Tool> {
        Tool::ALL
            .into_iter()
            .filter(|t| {
                t.family() == self.family
                    && self
                        .files
                        .iter()
                        .any(|f| f.ends_with(&format!("bin/{}", t.executable())))
            })
            .collect()
    }
}

/// The id packages are published for: `macos`, `windows-x64`, or `linux` (none yet).
pub fn platform_id() -> &'static str {
    if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(windows) {
        "windows-x64"
    } else {
        "linux"
    }
}

const EDB_PUBLISHER: &str =
    "EnterpriseDB, the PostgreSQL project's official binaries for macOS and Windows";
const PG_LICENSE: &str =
    "PostgreSQL License (permissive); bundled libraries under their own licenses";

/// Official builds TableGrip can download. pg_dump reads servers back to 9.2 but not newer than itself, so one build
/// of the newest major serves every server. MySQL's clients are GPL, so TableGrip doesn't download them.
pub static PACKAGES: LazyLock<Vec<ToolPackage>> = LazyLock::new(|| {
    let mac_libs = [
        "libpq.5.dylib",
        "libzstd.1.dylib",
        "libzstd.1.5.7.dylib",
        "liblz4.1.10.0.dylib",
        "libcrypto.3.dylib",
        "libz.1.3.2.dylib",
        "libssl.3.dylib",
        "libgssapi_krb5.2.2.dylib",
        "libkrb5.3.3.dylib",
        "libk5crypto.3.1.dylib",
        "libcom_err.3.0.dylib",
        "libkrb5support.1.1.dylib",
        "libedit.0.dylib",
    ];
    let windows_libs = [
        "libpq.dll",
        "libcrypto-3-x64.dll",
        "libssl-3-x64.dll",
        "libintl-9.dll",
        "libiconv-2.dll",
        "libwinpthread-1.dll",
        "liblz4.dll",
        "libzstd.dll",
        "zlib1.dll",
        "libcurl.dll",
    ];
    vec![
        ToolPackage {
            id: "postgresql-18.6-2-macos".into(),
            family: ToolFamily::PostgreSQL,
            version: "18.6".into(),
            os: "macos".into(),
            url: "https://get.enterprisedb.com/postgresql/postgresql-18.6-2-osx-binaries.zip"
                .into(),
            sha256: "21c0432b3cb45ab763aab2b4efe9edfa91f658c3be09cddf272c7b52eb2aa3fc".into(),
            download_bytes: 445_683_554,
            publisher: EDB_PUBLISHER.into(),
            license: PG_LICENSE.into(),
            // pg_dump, pg_restore and psql with the libraries they load, not the whole server (~23 MB of ~1 GB).
            files: ["bin/pg_dump", "bin/pg_restore", "bin/psql"]
                .iter()
                .map(|s| (*s).to_owned())
                .chain(mac_libs.iter().map(|l| format!("lib/{l}")))
                .collect(),
            strip_prefix: "pgsql/".into(),
        },
        ToolPackage {
            id: "postgresql-18.6-2-windows-x64".into(),
            family: ToolFamily::PostgreSQL,
            version: "18.6".into(),
            os: "windows-x64".into(),
            url:
                "https://get.enterprisedb.com/postgresql/postgresql-18.6-2-windows-x64-binaries.zip"
                    .into(),
            sha256: "41bb1496f60666d3745ae8a855753b877d14fa9e34667f266867635b790fc323".into(),
            download_bytes: 343_811_176,
            publisher: EDB_PUBLISHER.into(),
            license: PG_LICENSE.into(),
            // Windows finds DLLs next to the exe; pgAdmin's wxWidgets and ICU aren't needed by the clients.
            files: ["bin/pg_dump.exe", "bin/pg_restore.exe", "bin/psql.exe"]
                .iter()
                .map(|s| (*s).to_owned())
                .chain(windows_libs.iter().map(|l| format!("bin/{l}")))
                .collect(),
            strip_prefix: "pgsql/".into(),
        },
    ]
});

pub fn package_for(family: ToolFamily, os: &str) -> Option<&'static ToolPackage> {
    PACKAGES.iter().find(|p| p.family == family && p.os == os)
}

/// How to get a family's tools when TableGrip can't download them.
pub fn install_hint(family: ToolFamily) -> &'static str {
    let mac = cfg!(target_os = "macos");
    let windows = cfg!(windows);
    match family {
        ToolFamily::PostgreSQL if mac => {
            "Or install them yourself: brew install libpq (or Postgres.app)."
        }
        ToolFamily::PostgreSQL if windows => {
            "Or install them yourself with the PostgreSQL installer from postgresql.org."
        }
        ToolFamily::PostgreSQL => {
            "Install your distribution's PostgreSQL client package, e.g. sudo apt install postgresql-client (apt.postgresql.org has the newest)."
        }
        ToolFamily::MySQL if mac => {
            "Install them with brew install mysql-client (or mariadb), then choose them here if they aren't found."
        }
        ToolFamily::MySQL if windows => {
            "Install MySQL Shell & Utilities or MariaDB from their installers, then choose mysqldump here if it isn't found."
        }
        ToolFamily::MySQL => "Install your distribution's mysql-client or mariadb-client package.",
    }
}

/// A download that failed or didn't match its pinned hash.
#[derive(Debug)]
pub struct DownloadError(pub String);

impl std::fmt::Display for DownloadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for DownloadError {}

type CustomPaths = Box<dyn Fn() -> HashMap<String, String> + Send + Sync>;
type SearchDirs = Box<dyn Fn() -> Vec<PathBuf> + Send + Sync>;

/// Finds client tools on this machine and installs official builds the user agreed to download. Lookup order: the
/// path the user chose, then downloads, then PATH and the usual install folders.
pub struct ToolLibrary {
    root: PathBuf,
    /// Paths chosen in Settings → Tools, by [`Tool::name`].
    custom_paths: CustomPaths,
    search_dirs: SearchDirs,
    versions: Mutex<HashMap<(PathBuf, Option<SystemTime>), String>>,
}

impl ToolLibrary {
    pub fn new(root: PathBuf, custom_paths: CustomPaths, search_dirs: SearchDirs) -> Self {
        Self {
            root,
            custom_paths,
            search_dirs,
            versions: Mutex::new(HashMap::new()),
        }
    }

    /// The library over `~/.tablegrip/tools`, searching PATH and the usual install folders.
    pub fn standard(paths: &tg_core::paths::TableGripPaths, custom_paths: CustomPaths) -> Self {
        Self::new(
            paths.home().join("tools"),
            custom_paths,
            Box::new(default_search_dirs),
        )
    }

    pub fn folder(&self) -> &Path {
        &self.root
    }

    /// Every copy of `tool` found, best source first. Blocking: runs `--version` (cached per file).
    pub fn find(&self, tool: Tool) -> Vec<InstalledTool> {
        let mut found: Vec<InstalledTool> = Vec::new();
        if let Some(p) = (self.custom_paths)()
            .get(tool.name())
            .map(PathBuf::from)
            .filter(|p| p.is_file())
        {
            found.push(self.installed(tool, p, ToolSource::Custom));
        }
        for pkg in self.installed_packages() {
            let p = self.root.join(&pkg.id).join("bin").join(tool.executable());
            if p.is_file() {
                found.push(self.installed(tool, p, ToolSource::Downloaded));
            }
        }
        let mut seen = Vec::new();
        for dir in (self.search_dirs)() {
            let p = dir.join(tool.executable());
            if !p.is_file() || !is_executable(&p) {
                continue;
            }
            let Ok(real) = p.canonicalize() else { continue };
            if seen.contains(&real) || found.iter().any(|f| f.path == real) {
                continue;
            }
            seen.push(real.clone());
            found.push(self.installed(tool, real, ToolSource::System));
        }
        found
    }

    /// The copy to use for a server of `server_major`: the user's choice if any, else the newest that can read the
    /// server (PostgreSQL's dump tools refuse servers newer than themselves).
    pub fn best(&self, tool: Tool, server_major: Option<i32>) -> Option<InstalledTool> {
        let all = self.find(tool);
        if let Some(custom) = all.iter().find(|t| t.source == ToolSource::Custom) {
            return Some(custom.clone());
        }
        all.into_iter()
            .filter(|t| t.version.is_some())
            .filter(|t| match (server_major, tool.family()) {
                (Some(major), ToolFamily::PostgreSQL) => t.major().unwrap_or(0) >= major,
                _ => true,
            })
            .max_by_key(|t| (t.major().unwrap_or(0), t.source == ToolSource::Downloaded))
    }

    fn installed(&self, tool: Tool, path: PathBuf, source: ToolSource) -> InstalledTool {
        let version = self.version(&path);
        InstalledTool {
            tool,
            path,
            version,
            source,
        }
    }

    /// "18.6" from `pg_dump (PostgreSQL) 18.6`, `mysqldump  Ver 8.4.3 for macos…`, `mariadb-dump from 11.4.2-MariaDB`.
    pub fn version(&self, path: &Path) -> Option<String> {
        let key = (
            path.to_owned(),
            std::fs::metadata(path).and_then(|m| m.modified()).ok(),
        );
        if let Some(v) = self.versions.lock().unwrap().get(&key) {
            return Some(v.clone());
        }
        let mut child = std::process::Command::new(path)
            .arg("--version")
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .ok()?;
        // `--version` prints one line, far less than a pipe holds, so waiting first can't deadlock.
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match child.try_wait() {
                Ok(Some(_)) => break,
                Ok(None) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(10))
                }
                _ => {
                    let _ = child.kill();
                    return None;
                }
            }
        }
        let mut output = String::new();
        child.stdout.take()?.read_to_string(&mut output).ok()?;
        if let Some(mut e) = child.stderr.take() {
            let _ = e.read_to_string(&mut output);
        }
        let version = parse_version(&output)?;
        self.versions.lock().unwrap().insert(key, version.clone());
        Some(version)
    }

    /// Packages downloaded into the root.
    pub fn installed_packages(&self) -> Vec<&'static ToolPackage> {
        PACKAGES
            .iter()
            .filter(|p| self.root.join(&p.id).join("bin").is_dir())
            .collect()
    }

    pub fn package_size(&self, pkg: &ToolPackage) -> u64 {
        fn size(dir: &Path) -> u64 {
            std::fs::read_dir(dir)
                .into_iter()
                .flatten()
                .flatten()
                .map(|e| {
                    let p = e.path();
                    if p.is_dir() {
                        size(&p)
                    } else {
                        e.metadata().map_or(0, |m| m.len())
                    }
                })
                .sum()
        }
        size(&self.root.join(&pkg.id))
    }

    /// Downloads `pkg`, checks its SHA-256 and keeps only its files. `progress` gets bytes downloaded and the total.
    /// Drop the future to stop; nothing half-installed is left behind.
    pub async fn install(
        &self,
        pkg: &ToolPackage,
        progress: &(dyn Fn(u64, u64) + Send + Sync),
    ) -> Result<(), DownloadError> {
        std::fs::create_dir_all(&self.root).map_err(|e| DownloadError(e.to_string()))?;
        let archive = self
            .root
            .join(format!("{}-{}.zip.part", pkg.id, std::process::id()));
        let staging = self.root.join(format!(".{}.staging", pkg.id));
        struct Cleanup(PathBuf, PathBuf);
        impl Drop for Cleanup {
            fn drop(&mut self) {
                let _ = std::fs::remove_file(&self.0);
                let _ = std::fs::remove_dir_all(&self.1);
            }
        }
        let _cleanup = Cleanup(archive.clone(), staging.clone());
        download(pkg, &archive, progress).await?;
        let _ = std::fs::remove_dir_all(&staging);
        extract(pkg, &archive, &staging).map_err(|e| DownloadError(e.to_string()))?;
        let target = self.root.join(&pkg.id);
        let _ = std::fs::remove_dir_all(&target);
        std::fs::rename(&staging, &target).map_err(|e| DownloadError(e.to_string()))
    }

    pub fn uninstall(&self, pkg: &ToolPackage) -> std::io::Result<()> {
        match std::fs::remove_dir_all(self.root.join(&pkg.id)) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}

#[cfg(unix)]
fn is_executable(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path).is_ok_and(|m| m.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn is_executable(_: &Path) -> bool {
    true
}

async fn download(
    pkg: &ToolPackage,
    target: &Path,
    progress: &(dyn Fn(u64, u64) + Send + Sync),
) -> Result<(), DownloadError> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .build()
        .map_err(|e| DownloadError(e.to_string()))?;
    let mut response = client
        .get(&pkg.url)
        .send()
        .await
        .map_err(|e| DownloadError(e.to_string()))?;
    if response.status().as_u16() != 200 {
        return Err(DownloadError(format!(
            "Download of {} failed: HTTP {}",
            pkg.url,
            response.status().as_u16()
        )));
    }
    let mut digest = Sha256::new();
    let mut out = std::fs::File::create(target).map_err(|e| DownloadError(e.to_string()))?;
    let mut done = 0u64;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|e| DownloadError(e.to_string()))?
    {
        std::io::Write::write_all(&mut out, &chunk).map_err(|e| DownloadError(e.to_string()))?;
        digest.update(&chunk);
        done += chunk.len() as u64;
        progress(done, pkg.download_bytes);
    }
    let actual: String = digest
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    if !actual.eq_ignore_ascii_case(&pkg.sha256) {
        return Err(DownloadError(format!(
            "{} doesn't match its pinned SHA-256 (got {actual}). Nothing was installed.",
            pkg.url
        )));
    }
    Ok(())
}

fn extract(pkg: &ToolPackage, archive: &Path, target: &Path) -> std::io::Result<()> {
    let other = |e: zip::result::ZipError| std::io::Error::other(e.to_string());
    let mut zip = zip::ZipArchive::new(std::fs::File::open(archive)?).map_err(other)?;
    let wanted: Vec<String> = pkg
        .files
        .iter()
        .map(|f| format!("{}{f}", pkg.strip_prefix))
        .collect();
    let mut kept = 0;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(other)?;
        let name = entry.name().map_err(other)?.into_owned();
        if entry.is_dir() || !wanted.contains(&name) {
            continue;
        }
        let relative = name
            .strip_prefix(&pkg.strip_prefix)
            .unwrap_or(&name)
            .to_owned();
        if relative.split('/').any(|p| p == "..") || Path::new(&relative).is_absolute() {
            return Err(std::io::Error::other(format!(
                "Archive entry escapes the install folder: {name}"
            )));
        }
        let out = target.join(&relative);
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut file = std::fs::File::create(&out)?;
        std::io::copy(&mut entry, &mut file)?;
        #[cfg(unix)]
        if relative.starts_with("bin/") {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&out, std::fs::Permissions::from_mode(0o755))?;
        }
        kept += 1;
    }
    let missing = wanted.len() - kept;
    if missing > 0 {
        return Err(std::io::Error::other(format!(
            "{} doesn't contain {missing} of the expected files. Nothing was installed.",
            pkg.url
        )));
    }
    Ok(())
}

pub fn parse_version(output: &str) -> Option<String> {
    static PATTERN: LazyLock<regex::Regex> = LazyLock::new(|| {
        regex::Regex::new(r"(?:\)|Ver|Distrib|from)\s+(\d+\.\d+(?:\.\d+)?)").unwrap()
    });
    static ANY: LazyLock<regex::Regex> =
        LazyLock::new(|| regex::Regex::new(r"\b(\d+\.\d+(?:\.\d+)?)\b").unwrap());
    PATTERN
        .captures(output)
        .or_else(|| ANY.captures(output))
        .map(|c| c[1].to_owned())
}

/// PATH, then where Homebrew, Postgres.app, the EDB and MySQL installers and distributions put the clients.
pub fn default_search_dirs() -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = std::env::var_os("PATH")
        .map(|p| {
            std::env::split_paths(&p)
                .filter(|p| !p.as_os_str().is_empty())
                .collect()
        })
        .unwrap_or_default();
    if cfg!(target_os = "macos") {
        for p in [
            "/opt/homebrew/opt/libpq/bin",
            "/usr/local/opt/libpq/bin",
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/opt/homebrew/opt/mysql-client/bin",
            "/usr/local/opt/mysql-client/bin",
            "/usr/local/mysql/bin",
        ] {
            out.push(PathBuf::from(p));
        }
        out.extend(version_dirs(Path::new("/opt/homebrew/opt"), "postgresql@"));
        out.extend(version_dirs(Path::new("/opt/homebrew/opt"), "mysql"));
        out.extend(version_dirs(
            Path::new("/Applications/Postgres.app/Contents/Versions"),
            "",
        ));
    } else if cfg!(windows) {
        out.extend(version_dirs(Path::new("C:\\Program Files\\PostgreSQL"), ""));
        out.extend(version_dirs(Path::new("C:\\Program Files\\MySQL"), ""));
        out.extend(version_dirs(Path::new("C:\\Program Files"), "MariaDB"));
    } else {
        out.extend(version_dirs(Path::new("/usr/lib/postgresql"), ""));
        out.push(PathBuf::from("/usr/bin"));
        out.push(PathBuf::from("/usr/local/bin"));
    }
    let mut seen = Vec::new();
    out.retain(|p| {
        if seen.contains(p) {
            false
        } else {
            seen.push(p.clone());
            true
        }
    });
    out
}

/// The `bin` folder of each folder in `parent` whose name starts with `prefix`, newest-looking names first.
fn version_dirs(parent: &Path, prefix: &str) -> Vec<PathBuf> {
    let mut dirs: Vec<PathBuf> = std::fs::read_dir(parent)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            p.is_dir()
                && p.file_name()
                    .is_some_and(|n| n.to_string_lossy().starts_with(prefix))
        })
        .collect();
    dirs.sort_by(|a, b| b.file_name().cmp(&a.file_name()));
    dirs.into_iter().map(|d| d.join("bin")).collect()
}
