//! `settings.json` (`settings/Settings.kt`): the app's settings, which people also edit by hand like VS Code's or
//! Zed's. Comments and trailing commas are read; only settings that differ from the defaults are written.

use crate::backup::BackupSchedule;
use crate::json::{Baseline, first_line, from_lenient_str, omit_defaults, to_pretty};
use crate::theme::ThemeOverride;
use indexmap::IndexMap;
use serde::{Deserialize, Deserializer};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const DEFAULT_DARK_THEME: &str = "Graphite Dark";
pub const DEFAULT_LIGHT_THEME: &str = "Graphite Light";

/// Which themes the app uses, after Zed's `"theme": { "mode", "light", "dark" }`. `mode` is `System`, `Light` or
/// `Dark`; `dark` and `light` are theme names. Names that no theme has fall back to Graphite.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(default)]
pub struct ThemeSelection {
    pub mode: String,
    pub dark: String,
    pub light: String,
}

impl Default for ThemeSelection {
    fn default() -> Self {
        Self {
            mode: "System".to_string(),
            dark: DEFAULT_DARK_THEME.to_string(),
            light: DEFAULT_LIGHT_THEME.to_string(),
        }
    }
}

impl ThemeSelection {
    pub fn with_mode(mode: &str) -> Self {
        Self {
            mode: mode.to_string(),
            ..Self::default()
        }
    }
}

impl Baseline for ThemeSelection {
    fn baseline(&self) -> Self {
        Self::default()
    }
}
omit_defaults!(ThemeSelection {
    mode: "mode",
    dark: "dark",
    light: "light"
});

/// Files from before themes had names hold `"theme": "Dark"`, which is `{ "mode": "Dark" }`.
fn theme_selection<'de, D: Deserializer<'de>>(deserializer: D) -> Result<ThemeSelection, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Form {
        Mode(String),
        Selection(ThemeSelection),
    }
    Ok(match Form::deserialize(deserializer)? {
        Form::Mode(mode) => ThemeSelection::with_mode(&mode),
        Form::Selection(selection) => selection,
    })
}

/// A key combination in a UI-free form: `key` is the AWT key code, `label` how it reads. `primary` is Cmd on macOS
/// and Ctrl elsewhere.
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct KeyBinding {
    pub key: i32,
    pub label: String,
    #[serde(default)]
    pub primary: bool,
    #[serde(default)]
    pub shift: bool,
    #[serde(default)]
    pub alt: bool,
    #[serde(default)]
    pub ctrl: bool,
}

impl Baseline for KeyBinding {
    fn baseline(&self) -> Self {
        Self {
            key: self.key,
            label: self.label.clone(),
            primary: false,
            shift: false,
            alt: false,
            ctrl: false,
        }
    }
}
omit_defaults!(KeyBinding {
    key: "key" = req,
    label: "label" = req,
    primary: "primary",
    shift: "shift",
    alt: "alt",
    ctrl: "ctrl"
});

#[derive(Clone, Debug, PartialEq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    /// Light or dark (or the OS's choice), and the theme used for each.
    #[serde(deserialize_with = "theme_selection")]
    pub theme: ThemeSelection,
    pub editor_font_size: i32,
    /// Rows fetched per page in table viewers.
    pub page_size: i32,
    /// Maximum rows kept from a console query.
    pub max_result_rows: i32,
    /// 0 means no timeout.
    pub query_timeout_seconds: i32,
    /// `Builder` (structured conditions) or `Sql` (a typed WHERE clause).
    pub filter_mode: String,
    /// Maven repository drivers download from. Blank means Maven Central.
    pub driver_repository: String,
    pub auto_download_drivers: bool,
    /// Id of the agent the chat panel last used; blank picks the first one found.
    pub agent: String,
    /// The model the chat asks each CLI agent for, by agent id.
    pub agent_models: IndexMap<String, String>,
    /// `Cli` or `Provider`.
    pub agent_mode: String,
    /// `Anthropic` or `OpenAi`.
    pub agent_provider: String,
    pub agent_base_url: String,
    pub agent_model: String,
    /// An `AiAccess` name; missing or unknown means `SchemaOnly`.
    pub ai_access: String,
    /// Client tools the user chose by hand, by tool name.
    pub tool_paths: IndexMap<String, String>,
    pub scheduled_backups: bool,
    pub backup_schedules: Vec<BackupSchedule>,

    // Appearance
    /// `#RRGGBB`; blank means the theme's own accent.
    pub accent_color: String,
    pub theme_overrides: IndexMap<String, ThemeOverride>,
    pub ui_font_size: i32,
    pub island_corner_radius: i32,
    /// Scale of the whole interface in percent.
    pub zoom: i32,
    pub syntax_scheme: String,
    pub syntax_colors: IndexMap<String, String>,
    pub environment_colors: IndexMap<String, String>,

    // SQL editor
    pub editor_font_family: String,
    pub editor_line_height: i32,
    pub tab_size: i32,
    pub indent_with_tabs: bool,
    pub auto_close_brackets: bool,
    pub completion_while_typing: bool,
    pub highlight_current_line: bool,
    pub show_line_numbers: bool,
    /// `Upper`, `Lower` or `Preserve`.
    pub format_keyword_case: String,

    // Data grid
    /// `Compact`, `Comfortable` or `Spacious`.
    pub grid_density: String,
    pub null_text: String,
    /// A `java.time` pattern for date-time cells; blank shows them as the database returns them.
    pub date_time_format: String,
    /// `Utc` or `Local`.
    pub date_time_zone: String,
    pub number_grouping: bool,
    pub zebra_rows: bool,

    // Keymap
    pub keymap: String,
    pub key_bindings: IndexMap<String, KeyBinding>,

    // Behaviour
    pub remember_window_bounds: bool,
    pub remember_panel_sizes: bool,
    pub restore_tabs: bool,
    /// `LastUsed`, `None` or a tool's name.
    pub startup_tool: String,
    /// `Closed`, `Open` or `LastUsed`.
    pub startup_agent: String,
    pub status_breadcrumbs: bool,
    pub status_selection_stats: bool,
    pub stripe_hidden: Vec<String>,
    pub stripe_order: Vec<String>,

    // Updates
    pub check_for_updates: bool,
    pub install_updates_automatically: bool,
    /// `Stable`, or `Beta` to also get pre-releases.
    pub update_channel: String,
}

fn s(text: &str) -> String {
    text.to_string()
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: ThemeSelection::default(),
            editor_font_size: 13,
            page_size: 200,
            max_result_rows: 1_000,
            query_timeout_seconds: 0,
            filter_mode: s("Builder"),
            driver_repository: String::new(),
            auto_download_drivers: false,
            agent: String::new(),
            agent_models: IndexMap::new(),
            agent_mode: s("Cli"),
            agent_provider: s("Anthropic"),
            agent_base_url: String::new(),
            agent_model: String::new(),
            ai_access: s("SchemaOnly"),
            tool_paths: IndexMap::new(),
            scheduled_backups: false,
            backup_schedules: Vec::new(),
            accent_color: String::new(),
            theme_overrides: IndexMap::new(),
            ui_font_size: 0,
            island_corner_radius: 10,
            zoom: 100,
            syntax_scheme: s("Theme"),
            syntax_colors: IndexMap::new(),
            environment_colors: IndexMap::new(),
            editor_font_family: String::new(),
            editor_line_height: 150,
            tab_size: 4,
            indent_with_tabs: false,
            auto_close_brackets: true,
            completion_while_typing: true,
            highlight_current_line: true,
            show_line_numbers: true,
            format_keyword_case: s("Upper"),
            grid_density: s("Comfortable"),
            null_text: s("<null>"),
            date_time_format: String::new(),
            date_time_zone: s("Local"),
            number_grouping: false,
            zebra_rows: false,
            keymap: s("Default"),
            key_bindings: IndexMap::new(),
            remember_window_bounds: true,
            remember_panel_sizes: true,
            restore_tabs: true,
            startup_tool: s("LastUsed"),
            startup_agent: s("Closed"),
            status_breadcrumbs: true,
            status_selection_stats: true,
            stripe_hidden: Vec::new(),
            stripe_order: Vec::new(),
            check_for_updates: true,
            install_updates_automatically: true,
            update_channel: s("Stable"),
        }
    }
}

impl Baseline for Settings {
    fn baseline(&self) -> Self {
        Self::default()
    }
}
omit_defaults!(Settings {
    theme: "theme",
    editor_font_size: "editorFontSize",
    page_size: "pageSize",
    max_result_rows: "maxResultRows",
    query_timeout_seconds: "queryTimeoutSeconds",
    filter_mode: "filterMode",
    driver_repository: "driverRepository",
    auto_download_drivers: "autoDownloadDrivers",
    agent: "agent",
    agent_models: "agentModels",
    agent_mode: "agentMode",
    agent_provider: "agentProvider",
    agent_base_url: "agentBaseUrl",
    agent_model: "agentModel",
    ai_access: "aiAccess",
    tool_paths: "toolPaths",
    scheduled_backups: "scheduledBackups",
    backup_schedules: "backupSchedules",
    accent_color: "accentColor",
    theme_overrides: "themeOverrides",
    ui_font_size: "uiFontSize",
    island_corner_radius: "islandCornerRadius",
    zoom: "zoom",
    syntax_scheme: "syntaxScheme",
    syntax_colors: "syntaxColors",
    environment_colors: "environmentColors",
    editor_font_family: "editorFontFamily",
    editor_line_height: "editorLineHeight",
    tab_size: "tabSize",
    indent_with_tabs: "indentWithTabs",
    auto_close_brackets: "autoCloseBrackets",
    completion_while_typing: "completionWhileTyping",
    highlight_current_line: "highlightCurrentLine",
    show_line_numbers: "showLineNumbers",
    format_keyword_case: "formatKeywordCase",
    grid_density: "gridDensity",
    null_text: "nullText",
    date_time_format: "dateTimeFormat",
    date_time_zone: "dateTimeZone",
    number_grouping: "numberGrouping",
    zebra_rows: "zebraRows",
    keymap: "keymap",
    key_bindings: "keyBindings",
    remember_window_bounds: "rememberWindowBounds",
    remember_panel_sizes: "rememberPanelSizes",
    restore_tabs: "restoreTabs",
    startup_tool: "startupTool",
    startup_agent: "startupAgent",
    status_breadcrumbs: "statusBreadcrumbs",
    status_selection_stats: "statusSelectionStats",
    stripe_hidden: "stripeHidden",
    stripe_order: "stripeOrder",
    check_for_updates: "checkForUpdates",
    install_updates_automatically: "installUpdatesAutomatically",
    update_channel: "updateChannel",
});

/// Reads and writes settings.json. While the file holds an edit that doesn't parse ([`SettingsStore::load_error`]),
/// [`SettingsStore::save`] leaves it alone, so a typo made by hand is never replaced by defaults.
#[derive(Debug)]
pub struct SettingsStore {
    file: PathBuf,
    load_error: Mutex<Option<String>>,
    last_written: Mutex<Option<String>>,
}

impl SettingsStore {
    pub fn new(file: impl Into<PathBuf>) -> Self {
        Self {
            file: file.into(),
            load_error: Mutex::new(None),
            last_written: Mutex::new(None),
        }
    }

    pub fn file(&self) -> &Path {
        &self.file
    }

    /// Why the file couldn't be read the last time [`load`](Self::load) tried, or None.
    pub fn load_error(&self) -> Option<String> {
        self.load_error.lock().expect("lock").clone()
    }

    /// The text this store last wrote, so a watcher can tell the app's own saves from edits made by hand.
    pub fn last_written(&self) -> Option<String> {
        self.last_written.lock().expect("lock").clone()
    }

    fn set_load_error(&self, error: Option<String>) {
        *self.load_error.lock().expect("lock") = error;
    }

    /// Falls back to defaults when the file is missing or unreadable, so a bad edit never blocks startup.
    pub fn load(&self) -> Settings {
        if !self.file.exists() {
            self.set_load_error(None);
            return Settings::default();
        }
        let parsed = std::fs::read_to_string(&self.file)
            .map_err(|e| e.to_string())
            .and_then(|text| {
                from_lenient_str::<Settings>(&text).map_err(|e| first_line(&e.to_string()))
            });
        match parsed {
            Ok(settings) => {
                self.set_load_error(None);
                settings
            }
            Err(message) => {
                self.set_load_error(Some(message));
                Settings::default()
            }
        }
    }

    /// Writes `settings`; false, leaving the file as it is, while it holds an edit that doesn't parse.
    pub fn save(&self, settings: &Settings) -> std::io::Result<bool> {
        if self.load_error().is_some() {
            return Ok(false);
        }
        if let Some(parent) = self.file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let text = Self::encode(settings);
        *self.last_written.lock().expect("lock") = Some(text.clone());
        std::fs::write(&self.file, text)?;
        Ok(true)
    }

    /// The file's text, or None when there is none.
    pub fn read_text(&self) -> Option<String> {
        std::fs::read_to_string(&self.file).ok()
    }

    pub fn encode(settings: &Settings) -> String {
        to_pretty(settings, "  ").expect("settings are JSON") + "\n"
    }
}
