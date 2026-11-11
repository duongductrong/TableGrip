package dev.tablegrip.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.tablegrip.app.ui.parseHexColor
import dev.tablegrip.app.AppState
import dev.tablegrip.app.backup.BackupsPanel
import dev.tablegrip.app.tools.ToolsPanel
import dev.tablegrip.app.update.UpdateChannel
import dev.tablegrip.app.update.UpdatesPanel
import dev.tablegrip.core.tools.ToolFamily
import dev.tablegrip.app.AppTheme
import dev.tablegrip.core.agent.provider.AgentMode
import dev.tablegrip.core.agent.provider.ProviderProtocol
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.i18n.Language
import dev.tablegrip.core.i18n.tr
import java.util.Locale
import dev.tablegrip.app.LeftTool
import dev.tablegrip.app.console.SyntaxPreview
import dev.tablegrip.app.console.SyntaxScheme
import dev.tablegrip.app.console.SyntaxToken
import dev.tablegrip.app.console.syntaxColor
import dev.tablegrip.app.grid.CellFormat
import dev.tablegrip.app.ui.DefaultAccent
import dev.tablegrip.app.ui.theme.ThemeRegistry
import dev.tablegrip.app.ui.theme.colorTheme
import dev.tablegrip.app.ui.theme.isDarkMode
import dev.tablegrip.app.ui.KeymapPreset
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.UiZoom
import dev.tablegrip.app.ui.defaultEnvironmentColor
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.sql.KeywordCase

/** One editable setting, declared once here and rendered by the settings dialog. */
sealed interface SettingField {
    val label: String
    /** Shown under the control. */
    val hint: String?
}

/** Picks one of [options], e.g. from a combo box. */
class ChoiceField<T>(
    override val label: String,
    val options: List<T>,
    val optionLabel: (T) -> String,
    val get: (Settings) -> T,
    val set: (Settings, T) -> Settings,
    override val hint: String? = null,
) : SettingField

/** A whole number typed into a text field, checked against [range] when applied. */
class IntField(
    override val label: String,
    val range: IntRange,
    val get: (Settings) -> Int,
    val set: (Settings, Int) -> Settings,
    /** Shown after the field, e.g. "seconds". */
    val unit: String? = null,
    override val hint: String? = null,
) : SettingField {
    /** Why [text] isn't acceptable, or null if it is. */
    fun error(text: String): String? =
        if (text.trim().toIntOrNull()?.takeIf { it in range } == null) tr("Must be a number from {0} to {1}", range.first, range.last) else null
}

/** Free text, applied as typed; [placeholder] shows when it's empty. */
class StringField(
    override val label: String,
    val get: (Settings) -> String,
    val set: (Settings, String) -> Settings,
    val placeholder: String = "",
    override val hint: String? = null,
    /** Why a text can't be applied, or null if it can; rejected text stays in the field with the error. */
    val error: (String) -> String? = { null },
) : SettingField

/** On or off, shown as a check box with [label] beside it. */
class BooleanField(
    override val label: String,
    val get: (Settings) -> Boolean,
    val set: (Settings, Boolean) -> Settings,
    override val hint: String? = null,
) : SettingField

/**
 * A colour as `#RRGGBB`, typed or picked from [swatches]. Blank means [fallback], which the field shows as its
 * placeholder and swatch, so clearing it goes back to the built-in colour.
 */
class ColorField(
    override val label: String,
    val get: (Settings) -> String,
    val set: (Settings, String) -> Settings,
    val fallback: (Settings) -> Color,
    val swatches: List<Color> = emptyList(),
    override val hint: String? = null,
) : SettingField {
    fun error(text: String): String? = if (text.isBlank() || parseHexColor(text) != null) null else tr("A colour like #3574F0")
}

/** A list computed the first time it is read, e.g. the fonts installed here, so building the pages stays cheap. */
fun <T> lazyList(compute: () -> List<T>): List<T> =
    object : AbstractList<T>() {
        private val items by lazy(compute)
        override val size: Int
            get() = items.size

        override fun get(index: Int): T = items[index]
    }

class SettingsSection(
    val title: String?,
    val fields: List<SettingField>,
    /** Shown under [fields], for what isn't a single setting, e.g. managing the installed drivers. */
    val panel: (@Composable (AppState) -> Unit)? = null,
    /** More words the sidebar search finds this section by, e.g. what [panel] lists. */
    val keywords: List<String> = emptyList(),
    /** What "Reset to defaults" does for what [panel] edits, given the current and the default settings. */
    val reset: ((Settings, Settings) -> Settings)? = null,
)

/** A page of the settings dialog; [group] is the sidebar heading it sits under. */
class SettingsPage(
    val id: String,
    val group: String,
    val title: String,
    val description: String,
    val sections: List<SettingsSection>,
) {
    val fields: List<SettingField>
        get() = sections.flatMap { it.fields }

    /** Whether the sidebar search [query] finds this page, by its names or any of its settings. */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        val texts = listOf(title, group, description) + sections.mapNotNull { it.title } + sections.flatMap { it.keywords } + fields.flatMap { listOfNotNull(it.label, it.hint) }
        return texts.any { it.contains(needle, ignoreCase = true) }
    }
}

/** Every settings page, in sidebar order. Adding a setting means adding a field to a page here (and to [Settings]). */
object SettingsPages {
    val all: List<SettingsPage> =
        listOf(
            SettingsPage(
                id = "appearance",
                group = tr("Appearance & Behavior"),
                title = tr("Appearance"),
                description = tr("How TableGrip looks."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Language"),
                            listOf(
                                ChoiceField(
                                    tr("Language"),
                                    listOf(Language.System) + Language.entries.map { it.tag },
                                    { tag -> Language.ofTag(tag)?.nativeName ?: tr("System ({0})", Language.ofLocale(Locale.getDefault()).nativeName) },
                                    Settings::language,
                                    { s, v -> s.copy(language = v) },
                                    hint = tr("The language of menus, dialogs and messages. Data, SQL and logs stay as they are."),
                                ),
                            ),
                            panel = { app -> LanguagePanel(app) },
                            keywords = listOf("language", "locale", "translation", "English", "Tiếng Việt", "中文", "简体中文", "繁體中文"),
                        ),
                        SettingsSection(
                            tr("Theme"),
                            listOf(
                                ChoiceField(
                                    tr("Mode"),
                                    AppTheme.entries,
                                    AppTheme::label,
                                    get = { s -> AppTheme.of(s.theme.mode) },
                                    set = { s, mode -> s.copy(theme = s.theme.copy(mode = mode.name)) },
                                    hint = tr("\"Sync with OS\" follows the system's light or dark mode."),
                                ),
                                themeField(dark = true),
                                themeField(dark = false),
                                ColorField(
                                    tr("Accent colour"),
                                    Settings::accentColor,
                                    { s, v -> s.copy(accentColor = v) },
                                    fallback = { s -> s.copy(accentColor = "").colorTheme(s.isDarkMode()).islands.accent },
                                    swatches = AccentSwatches,
                                    hint = tr("Over every theme: selected tool buttons, primary buttons, the statement marker in consoles and other highlights."),
                                ),
                            ),
                            panel = { app -> ThemeFilesPanel(app) },
                            keywords = listOf("dark", "light", "colour theme", "color theme", "settings.json", "json", "VS Code", "Dracula", "One Dark", "Catppuccin", "Contrast"),
                        ),
                        SettingsSection(
                            tr("Layout"),
                            listOf(
                                ChoiceField(
                                    tr("Zoom"),
                                    UiZoom.Levels,
                                    { if (it == UiZoom.Default) tr("{0}% (default)", it) else "$it%" },
                                    get = { s -> UiZoom.snap(s.zoom) },
                                    set = { s, v -> s.copy(zoom = v) },
                                    hint = tr("Scales everything in the window. {0} / {1} zoom in and out, {2} resets.", Shortcuts.ZoomIn.label, Shortcuts.ZoomOut.label, Shortcuts.ResetZoom.label),
                                ),
                                ChoiceField(
                                    tr("Font size"),
                                    listOf(0, 11, 12, 13, 14, 15, 16),
                                    { if (it == 0) tr("Default") else tr("{0} pt", it) },
                                    Settings::uiFontSize,
                                    { s, v -> s.copy(uiFontSize = v) },
                                    hint = tr("Labels, menus and controls. The SQL editor has its own size."),
                                ),
                                ChoiceField(
                                    tr("Panel corners"),
                                    listOf(0, 6, 10, 14),
                                    { when (it) { 0 -> tr("Square"); 6 -> tr("Slightly rounded"); 10 -> tr("Rounded (default)"); else -> tr("Very rounded") } },
                                    Settings::islandCornerRadius,
                                    { s, v -> s.copy(islandCornerRadius = v) },
                                ),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "color-scheme",
                group = tr("Appearance & Behavior"),
                title = tr("Color Scheme"),
                description = tr("Colours of SQL in consoles and of connection environments. Empty fields use the scheme's colour."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Syntax"),
                            listOf(
                                ChoiceField(
                                    tr("Scheme"),
                                    SyntaxScheme.entries,
                                    SyntaxScheme::label,
                                    get = { s -> SyntaxScheme.of(s.syntaxScheme) },
                                    set = { s, scheme -> s.copy(syntaxScheme = scheme.name) },
                                    hint = tr("\"Theme's own\" uses the colour theme's tokens. Other schemes have a dark and a light variant, picked by the mode. The preview shows the current one."),
                                ),
                            ),
                            panel = { SyntaxPreview() },
                        ),
                        SettingsSection(tr("Dark theme colours"), SyntaxToken.entries.map { syntaxColorField(it, dark = true) }, reset = { s, _ -> s.copy(syntaxColors = emptyMap()) }),
                        SettingsSection(tr("Light theme colours"), SyntaxToken.entries.map { syntaxColorField(it, dark = false) }),
                        SettingsSection(
                            tr("Environments"),
                            Environment.entries.filter { it != Environment.None }.map { environment ->
                                ColorField(
                                    environment.name,
                                    { s -> s.environmentColors[environment.name] ?: "" },
                                    { s, v -> s.copy(environmentColors = if (v.isEmpty()) s.environmentColors - environment.name else s.environmentColors + (environment.name to v)) },
                                    fallback = { defaultEnvironmentColor(environment)!! },
                                )
                            },
                            keywords = listOf("production", "staging", "badge"),
                        ),
                    ),
            ),
            SettingsPage(
                id = "behavior",
                group = tr("Appearance & Behavior"),
                title = tr("Startup & Layout"),
                description = tr("What TableGrip opens with, what it remembers, and what the tool stripe and status bar show."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("On start"),
                            listOf(
                                BooleanField(tr("Reopen tabs"), Settings::restoreTabs, { s, v -> s.copy(restoreTabs = v) }, hint = tr("Consoles (with unsaved text) and tables from the last session.")),
                                ChoiceField(
                                    tr("Tool window"),
                                    listOf("LastUsed", "None") + LeftTool.entries.map { it.name },
                                    ::startupToolLabel,
                                    Settings::startupTool,
                                    { s, v -> s.copy(startupTool = v) },
                                ),
                                ChoiceField(
                                    tr("Agent chat"),
                                    listOf("Closed", "Open", "LastUsed"),
                                    { when (it) { "Open" -> "Open"; "LastUsed" -> tr("As last time"); else -> tr("Closed") } },
                                    Settings::startupAgent,
                                    { s, v -> s.copy(startupAgent = v) },
                                ),
                                BooleanField(tr("Remember window size"), Settings::rememberWindowBounds, { s, v -> s.copy(rememberWindowBounds = v) }),
                                BooleanField(tr("Remember panel widths"), Settings::rememberPanelSizes, { s, v -> s.copy(rememberPanelSizes = v) }, hint = tr("The tool window's and the agent chat's.")),
                            ),
                        ),
                        SettingsSection(
                            tr("Status bar"),
                            listOf(
                                BooleanField(tr("Breadcrumbs"), Settings::statusBreadcrumbs, { s, v -> s.copy(statusBreadcrumbs = v) }, hint = tr("Connection, schema and table of the tab in front.")),
                                BooleanField(tr("Selection summary"), Settings::statusSelectionStats, { s, v -> s.copy(statusSelectionStats = v) }, hint = tr("Count, sum and average of the selected cells.")),
                            ),
                        ),
                        SettingsSection(
                            tr("Tool stripe"),
                            emptyList(),
                            panel = { app -> StripePanel(app) },
                            keywords = listOf("sidebar", "buttons", "hide", "order"),
                            reset = { s, d -> s.copy(stripeHidden = d.stripeHidden, stripeOrder = d.stripeOrder) },
                        ),
                    ),
            ),
            SettingsPage(
                id = "keymap",
                group = tr("Appearance & Behavior"),
                title = tr("Keymap"),
                description = tr("Keyboard shortcuts. Start from a preset, then click a shortcut to record another one."),
                sections =
                    listOf(
                        SettingsSection(
                            null,
                            listOf(
                                ChoiceField(
                                    tr("Preset"),
                                    KeymapPreset.entries,
                                    KeymapPreset::label,
                                    get = { s -> KeymapPreset.of(s.keymap) },
                                    set = { s, preset -> s.copy(keymap = preset.name) },
                                    hint = tr("Your own changes stay on top of whichever preset is chosen."),
                                ),
                            ),
                            panel = { app -> KeymapPanel(app) },
                            keywords = listOf("shortcut", "keyboard", "hotkey", "binding"),
                            reset = { s, d -> s.copy(keyBindings = d.keyBindings) },
                        ),
                    ),
            ),
            SettingsPage(
                id = "updates",
                group = tr("Appearance & Behavior"),
                title = tr("Updates"),
                description = tr("New versions of TableGrip, from its GitHub releases."),
                sections =
                    listOf(
                        SettingsSection(
                            null,
                            listOf(
                                BooleanField(tr("Check for updates automatically"), Settings::checkForUpdates, { s, v -> s.copy(checkForUpdates = v) }, hint = tr("Once a day.")),
                                BooleanField(
                                    tr("Download and install updates automatically"),
                                    Settings::installUpdatesAutomatically,
                                    { s, v -> s.copy(installUpdatesAutomatically = v) },
                                    hint = tr("Downloads in the background and installs the next time you quit. Off: asks first, with the release notes. On Windows, TableGrip always asks before installing."),
                                ),
                                ChoiceField(
                                    tr("Channel"),
                                    UpdateChannel.entries,
                                    UpdateChannel::label,
                                    get = { s -> UpdateChannel.of(s.updateChannel) },
                                    set = { s, channel -> s.copy(updateChannel = channel.name) },
                                    hint = tr("Beta also offers pre-releases, which get new features first and may have rough edges."),
                                ),
                            ),
                            panel = { app -> UpdatesPanel(app) },
                            keywords = listOf("version", "upgrade", "release", "Sparkle", "beta"),
                        ),
                    ),
            ),
            SettingsPage(
                id = "editor",
                group = tr("Editor"),
                title = tr("SQL Editor"),
                description = tr("Typing and reading SQL in consoles."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Font"),
                            listOf(
                                ChoiceField(
                                    tr("Font"),
                                    lazyList { listOf("") + monospaceFonts() },
                                    { it.ifEmpty { tr("JetBrains Mono (built in)") } },
                                    Settings::editorFontFamily,
                                    { s, v -> s.copy(editorFontFamily = v) },
                                    hint = tr("Monospaced fonts installed on this machine. Also used by result grids."),
                                ),
                                IntField(tr("Font size"), 9..32, Settings::editorFontSize, { s, v -> s.copy(editorFontSize = v) }, unit = tr("pt")),
                                IntField(tr("Line height"), 100..300, Settings::editorLineHeight, { s, v -> s.copy(editorLineHeight = v) }, unit = "%", hint = tr("Of the font size.")),
                            ),
                        ),
                        SettingsSection(
                            tr("Indentation"),
                            listOf(
                                ChoiceField(tr("Tab size"), listOf(2, 4, 8), { "$it" }, Settings::tabSize, { s, v -> s.copy(tabSize = v) }),
                                ChoiceField(tr("Indent with"), listOf(false, true), { if (it) tr("Tabs") else tr("Spaces") }, Settings::indentWithTabs, { s, v -> s.copy(indentWithTabs = v) }),
                            ),
                        ),
                        SettingsSection(
                            tr("Typing"),
                            listOf(
                                BooleanField(tr("Close brackets and quotes"), Settings::autoCloseBrackets, { s, v -> s.copy(autoCloseBrackets = v) }),
                                BooleanField(
                                    tr("Complete as you type"),
                                    Settings::completionWhileTyping,
                                    { s, v -> s.copy(completionWhileTyping = v) },
                                    hint = tr("Off: suggestions only open with {0}.", Shortcuts.Completion),
                                ),
                            ),
                        ),
                        SettingsSection(
                            tr("Display"),
                            listOf(
                                BooleanField(tr("Line numbers"), Settings::showLineNumbers, { s, v -> s.copy(showLineNumbers = v) }),
                                BooleanField(tr("Highlight the current line"), Settings::highlightCurrentLine, { s, v -> s.copy(highlightCurrentLine = v) }),
                            ),
                        ),
                        SettingsSection(
                            tr("Reformat SQL"),
                            listOf(
                                ChoiceField(
                                    tr("Keywords"),
                                    KeywordCase.entries,
                                    { when (it) { KeywordCase.Upper -> tr("UPPER CASE"); KeywordCase.Lower -> tr("lower case"); KeywordCase.Preserve -> tr("As typed") } },
                                    get = { s -> KeywordCase.of(s.formatKeywordCase) },
                                    set = { s, case -> s.copy(formatKeywordCase = case.name) },
                                    hint = tr("Indents follow the settings above."),
                                ),
                            ),
                            keywords = listOf("format", "style"),
                        ),
                    ),
            ),
            SettingsPage(
                id = "queries",
                group = tr("Database"),
                title = tr("Query Execution"),
                description = tr("Limits applied when running statements from a console."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Results"),
                            listOf(
                                IntField(
                                    tr("Max result rows"),
                                    10..1_000_000,
                                    Settings::maxResultRows,
                                    { s, v -> s.copy(maxResultRows = v) },
                                    unit = tr("rows"),
                                    hint = tr("Rows beyond this are not fetched; the result says it was cut short."),
                                ),
                            ),
                        ),
                        SettingsSection(
                            tr("Timeouts"),
                            listOf(
                                IntField(
                                    tr("Query timeout"),
                                    0..86_400,
                                    Settings::queryTimeoutSeconds,
                                    { s, v -> s.copy(queryTimeoutSeconds = v) },
                                    unit = tr("seconds"),
                                    hint = tr("0 waits as long as the query takes."),
                                ),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "drivers",
                group = tr("Database"),
                title = tr("Drivers"),
                description = tr("Where JDBC drivers come from, and the ones on this machine. None ships with TableGrip; each is downloaded the first time it's needed."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Downloads"),
                            listOf(
                                ChoiceField(
                                    tr("Downloading drivers"),
                                    listOf(false, true),
                                    { if (it) tr("Automatic for permissive licenses") else tr("Ask every time") },
                                    Settings::autoDownloadDrivers,
                                    { s, v -> s.copy(autoDownloadDrivers = v) },
                                    hint = tr("LGPL drivers (MariaDB) always ask. GPL and vendor drivers (MySQL Connector/J, Oracle) are never downloaded: you choose their jars."),
                                ),
                                StringField(
                                    tr("Maven repository"),
                                    Settings::driverRepository,
                                    { s, v -> s.copy(driverRepository = v.trim()) },
                                    placeholder = DriverLibrary.MavenCentral,
                                    hint = tr("Leave empty for Maven Central, or use your company's mirror. Downloads are checked against TableGrip's checksums either way."),
                                ),
                            ),
                        ),
                        SettingsSection(
                            tr("Installed"),
                            emptyList(),
                            panel = { app -> DriversPanel(app, installed = true) },
                            keywords = DriverId.entries.map { it.displayName },
                        ),
                        SettingsSection(tr("Available"), emptyList(), panel = { app -> DriversPanel(app, installed = false) }),
                    ),
            ),
            SettingsPage(
                id = "tools",
                group = tr("Database"),
                title = tr("Tools"),
                description =
                    tr("Command-line clients TableGrip runs for backups and restores. They're never bundled: TableGrip uses the ones on this machine, ") +
                        tr("or downloads official builds when you agree to it."),
                sections =
                    listOf(
                        SettingsSection("PostgreSQL", emptyList(), panel = { app -> ToolsPanel(app, ToolFamily.PostgreSQL) }, keywords = listOf("pg_dump", "pg_restore", "psql", "backup")),
                        SettingsSection(tr("MySQL and MariaDB"), emptyList(), panel = { app -> ToolsPanel(app, ToolFamily.MySQL) }, keywords = listOf("mysqldump", "mariadb-dump", "mysql", "backup")),
                    ),
            ),
            SettingsPage(
                id = "backups",
                group = tr("Database"),
                title = tr("Backups"),
                description = tr("Backups TableGrip makes on a schedule. Nothing runs unless you turn it on."),
                sections = listOf(SettingsSection(null, emptyList(), panel = { app -> BackupsPanel(app) }, keywords = listOf("schedule", "backup", "pg_dump", "mysqldump"))),
            ),
            SettingsPage(
                id = "data-editor",
                group = tr("Database"),
                title = tr("Data Editor"),
                description = tr("Browsing and editing table data, and how result grids show values."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Paging"),
                            listOf(
                                IntField(
                                    tr("Page size"),
                                    10..10_000,
                                    Settings::pageSize,
                                    { s, v -> s.copy(pageSize = v) },
                                    unit = tr("rows"),
                                    hint = tr("Rows loaded per page when opening a table."),
                                ),
                            ),
                        ),
                        SettingsSection(
                            tr("Grid"),
                            listOf(
                                ChoiceField(tr("Row density"), listOf("Compact", "Comfortable", "Spacious"), { when (it) { "Compact" -> tr("Compact"); "Spacious" -> tr("Spacious"); else -> tr("Comfortable") } }, Settings::gridDensity, { s, v -> s.copy(gridDensity = v) }),
                                BooleanField(tr("Stripe alternate rows"), Settings::zebraRows, { s, v -> s.copy(zebraRows = v) }),
                            ),
                        ),
                        SettingsSection(
                            tr("Values"),
                            listOf(
                                StringField(
                                    tr("NULL shows as"),
                                    Settings::nullText,
                                    { s, v -> s.copy(nullText = v) },
                                    placeholder = tr("(empty)"),
                                    hint = tr("Only how cells read; copying and editing still see NULL."),
                                ),
                                StringField(
                                    tr("Date-time format"),
                                    Settings::dateTimeFormat,
                                    { s, v -> s.copy(dateTimeFormat = v.trim()) },
                                    placeholder = tr("As the database returns it"),
                                    hint = tr("A java.time pattern, e.g. yyyy-MM-dd HH:mm:ss or dd/MM/yyyy HH:mm. Copying keeps the original value."),
                                    error = { if (CellFormat.isValidPattern(it.trim())) null else tr("Not a date-time pattern") },
                                ),
                                ChoiceField(
                                    tr("Date-time zone"),
                                    listOf("Local", "Utc"),
                                    { if (it == "Utc") tr("UTC") else tr("This computer's ({0})", java.time.ZoneId.systemDefault().id) },
                                    Settings::dateTimeZone,
                                    { s, v -> s.copy(dateTimeZone = v) },
                                    hint = tr("For values that carry an offset, when a format is set. Values without one are shown as stored."),
                                ),
                                BooleanField(tr("Group thousands"), Settings::numberGrouping, { s, v -> s.copy(numberGrouping = v) }, hint = tr("1234567.5 reads 1,234,567.5.")),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "agent",
                group = tr("Tools"),
                title = tr("Agent"),
                description = tr("Where the agent chat gets its answers. Changing these sends nothing: the agent is only asked when you send a message."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Source"),
                            listOf(
                                ChoiceField(
                                    tr("Agents from"),
                                    AgentMode.entries,
                                    AgentMode::label,
                                    get = { s -> AgentMode.of(s.agentMode) },
                                    set = { s, mode -> s.copy(agentMode = mode.name) },
                                    hint = tr("Command-line agents run the CLIs installed here (Claude Code, Codex, Gemini CLI…) on their own accounts. An API provider is called directly with your key."),
                                ),
                            ),
                        ),
                        SettingsSection(
                            tr("API provider"),
                            listOf(
                                ChoiceField(
                                    tr("Protocol"),
                                    ProviderProtocol.entries,
                                    ProviderProtocol::title,
                                    get = { s -> ProviderProtocol.of(s.agentProvider) },
                                    set = { s, protocol -> s.copy(agentProvider = protocol.name) },
                                    hint = tr("OpenAI-compatible also covers OpenRouter, LiteLLM, Ollama, LM Studio and vLLM. Used when agents come from an API provider."),
                                ),
                                StringField(
                                    tr("Base URL"),
                                    Settings::agentBaseUrl,
                                    { s, v -> s.copy(agentBaseUrl = v.trim()) },
                                    placeholder = tr("Protocol default"),
                                    hint = tr("Empty uses {0}. For a local server, e.g. http://localhost:11434/v1.", ProviderProtocol.entries.joinToString(tr(" or ")) { it.defaultBaseUrl }),
                                ),
                                StringField(
                                    tr("Model"),
                                    Settings::agentModel,
                                    { s, v -> s.copy(agentModel = v.trim()) },
                                    placeholder = tr("Protocol default"),
                                    hint = tr("Empty uses {0}.", ProviderProtocol.entries.joinToString(tr(" or ")) { it.defaultModel }),
                                ),
                            ),
                            panel = { app -> ApiKeyPanel(app) },
                            keywords = listOf("API key", "token", "Anthropic", "OpenAI", "Ollama"),
                        ),
                        SettingsSection(
                            tr("Data shared with agents"),
                            listOf(
                                ChoiceField(
                                    tr("Connections share"),
                                    AiAccess.entries,
                                    AiAccess::label,
                                    get = { s -> AiAccess.of(s.aiAccess) },
                                    set = { s, access -> s.copy(aiAccess = access.name) },
                                    hint =
                                        tr("What a connection may send to the agent, unless its own Options say otherwise. Schema only sends names, types and your SQL, ") +
                                            tr("never values from the data. Production connections never share data unless their own Options allow it."),
                                ),
                            ),
                            keywords = listOf("privacy", "AI", "sample data", "sensitive"),
                        ),
                    ),
            ),
            SettingsPage(
                id = "mcp",
                group = tr("Tools"),
                title = tr("MCP"),
                description = tr("Lets AI apps such as Claude, Cursor and VS Code use your connections through tablegrip mcp, under the same rules as the agent chat."),
                sections =
                    listOf(
                        SettingsSection(
                            tr("Access"),
                            listOf(
                                BooleanField(
                                    tr("Share connections with AI apps (MCP)"),
                                    Settings::mcpEnabled,
                                    { s, v -> s.copy(mcpEnabled = v) },
                                    hint =
                                        tr("Apps see the connections whose AI agents option isn't Off: their schema, and their data only where it is shared. ") +
                                            tr("They can change data only on connections whose Agent writes option allows it, and production asks first."),
                                ),
                            ),
                            keywords = listOf("MCP", "Model Context Protocol", "Claude", "Cursor", "VS Code", "AI"),
                        ),
                        SettingsSection(
                            tr("Limits"),
                            listOf(
                                IntField(tr("Rows per result"), 1..5_000, Settings::mcpMaxRows, { s, v -> s.copy(mcpMaxRows = v) }, hint = tr("Larger results are cut off, and the app is told so.")),
                                IntField(tr("Statement timeout"), 0..3_600, Settings::mcpTimeoutSeconds, { s, v -> s.copy(mcpTimeoutSeconds = v) }, unit = tr("seconds"), hint = tr("0 means no limit.")),
                                IntField(tr("Result size"), 4_000..1_000_000, Settings::mcpResultBudget, { s, v -> s.copy(mcpResultBudget = v) }, unit = tr("characters")),
                            ),
                        ),
                        SettingsSection(
                            tr("Connect an app"),
                            emptyList(),
                            panel = { app -> McpSetupPanel(app) },
                            keywords = listOf("tablegrip mcp", "command line", "PATH", "Claude Desktop", "Claude Code", "Windsurf"),
                        ),
                        SettingsSection(tr("Recent activity"), emptyList(), panel = { app -> McpActivityPanel(app) }, keywords = listOf("log", "audit")),
                    ),
            ),
        )

    fun byId(id: String): SettingsPage? = all.firstOrNull { it.id == id }
}

/** Picks the theme used in [dark] or light mode from the built-in and the user's themes; an unknown name shows as Graphite. */
private fun themeField(dark: Boolean): ChoiceField<String> =
    ChoiceField(
        if (dark) tr("Dark theme") else tr("Light theme"),
        liveList { ThemeRegistry.names(dark) },
        { it },
        get = { s -> ThemeRegistry.resolve(if (dark) s.theme.dark else s.theme.light, dark).name },
        set = { s, name -> s.copy(theme = if (dark) s.theme.copy(dark = name) else s.theme.copy(light = name)) },
        hint = if (dark) tr("Used in dark mode. Your own themes go in ~/.tablegrip/themes.") else tr("Used in light mode."),
    )

/** A list read afresh on every access, e.g. themes that can change while the dialog is open. */
private fun <T> liveList(read: () -> List<T>): List<T> =
    object : AbstractList<T>() {
        override val size: Int
            get() = read().size

        override fun get(index: Int): T = read()[index]
    }

private val AccentSwatches =
    listOf(
        DefaultAccent,
        Color(0xFF7C5CDB),
        Color(0xFFD95C9A),
        Color(0xFFDB4B4B),
        Color(0xFFE08A3C),
        Color(0xFF4CA35A),
        Color(0xFF2FA6A9),
        Color(0xFF6F7A8C),
    )

private fun syntaxColorField(token: SyntaxToken, dark: Boolean): ColorField =
    ColorField(
        token.label,
        { s -> s.syntaxColors[token.settingKey(dark)] ?: "" },
        { s, v ->
            val key = token.settingKey(dark)
            s.copy(syntaxColors = if (v.isEmpty()) s.syntaxColors - key else s.syntaxColors + (key to v))
        },
        // The scheme's colour, not a custom one already set.
        fallback = { s -> s.copy(syntaxColors = emptyMap()).syntaxColor(token, s.colorTheme(dark)) },
    )

private fun startupToolLabel(value: String): String =
    when (value) {
        "LastUsed" -> tr("As last time")
        "None" -> tr("None")
        LeftTool.Explorer.name -> tr("Connections")
        LeftTool.History.name -> tr("Query History")
        LeftTool.Queries.name -> tr("Saved Queries")
        else -> value
    }

/** Families installed here whose narrow and wide letters are equally wide, by name. */
private fun monospaceFonts(): List<String> =
    runCatching {
        val canvas = java.awt.Canvas()
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.filter { name ->
            val metrics = canvas.getFontMetrics(java.awt.Font(name, java.awt.Font.PLAIN, 12))
            metrics.charWidth('i') == metrics.charWidth('W') && metrics.charWidth('i') > 0 && !name.startsWith(".")
        }
    }.getOrDefault(emptyList())
