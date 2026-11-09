//! Where TableGrip keeps its files (`TableGripPaths.kt`).

use std::path::{Path, PathBuf};

/// The environment variable that replaces the `tablegrip.home` system property of the Kotlin app.
pub const HOME_ENV: &str = "TABLEGRIP_HOME";

/// `~/.tablegrip` and what lives in it. Tests build one over a temporary directory with [`TableGripPaths::new`], so
/// they never touch the real one.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TableGripPaths {
    home: PathBuf,
}

impl TableGripPaths {
    pub fn new(home: impl Into<PathBuf>) -> Self {
        Self { home: home.into() }
    }

    /// `$TABLEGRIP_HOME` when set and not empty, else `.tablegrip` in the user's home directory.
    pub fn from_env() -> Self {
        Self::resolve(std::env::var_os(HOME_ENV), user_home())
    }

    fn resolve(over: Option<std::ffi::OsString>, user_home: PathBuf) -> Self {
        match over.filter(|v| !v.is_empty()) {
            Some(home) => Self::new(home),
            None => Self::new(user_home.join(".tablegrip")),
        }
    }

    pub fn home(&self) -> &Path {
        &self.home
    }

    pub fn connections(&self) -> PathBuf {
        self.home.join("connections.json")
    }

    pub fn settings(&self) -> PathBuf {
        self.home.join("settings.json")
    }

    /// The user's own theme files, read next to the built-in ones.
    pub fn themes(&self) -> PathBuf {
        self.home.join("themes")
    }

    pub fn history(&self) -> PathBuf {
        self.home.join("history.jsonl")
    }

    /// Saved queries: plain `.sql` files, in folders.
    pub fn queries(&self) -> PathBuf {
        self.home.join("queries")
    }

    /// Tabs and layout to reopen on the next start.
    pub fn session(&self) -> PathBuf {
        self.home.join("session.json")
    }

    /// Host keys pinned on first use by SSH tunnels (Kotlin: `TableGripPaths.home.resolve("known_hosts")`).
    pub fn known_hosts(&self) -> PathBuf {
        self.home.join("known_hosts")
    }

    /// Downloaded JDBC drivers and the user's own driver jars.
    pub fn drivers(&self) -> PathBuf {
        self.home.join("drivers")
    }

    /// SQLite working copies of open data files (CSV, Excel...).
    pub fn working_copies(&self) -> PathBuf {
        self.home.join("cache").join("datafiles")
    }

    /// Agent chats, one file each.
    pub fn chats(&self) -> PathBuf {
        self.home.join("chats")
    }

    /// Where CLI agents run.
    pub fn agent_workspace(&self) -> PathBuf {
        self.home.join("agent")
    }
}

/// The user's home directory, as Java's `user.home` is: `HOME` on Unix, `USERPROFILE` on Windows.
pub fn user_home() -> PathBuf {
    let var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    std::env::var_os(var)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_to_dot_tablegrip_in_the_user_home() {
        let paths = TableGripPaths::resolve(None, PathBuf::from("/home/me"));
        assert_eq!(paths.home(), Path::new("/home/me/.tablegrip"));
        assert_eq!(
            paths.settings(),
            Path::new("/home/me/.tablegrip/settings.json")
        );
        assert_eq!(
            paths.working_copies(),
            Path::new("/home/me/.tablegrip/cache/datafiles")
        );
    }

    #[test]
    fn the_environment_overrides_the_home_unless_empty() {
        let over = TableGripPaths::resolve(Some("/tmp/tg".into()), PathBuf::from("/home/me"));
        assert_eq!(over.connections(), Path::new("/tmp/tg/connections.json"));
        let empty = TableGripPaths::resolve(Some("".into()), PathBuf::from("/home/me"));
        assert_eq!(empty.home(), Path::new("/home/me/.tablegrip"));
    }
}
