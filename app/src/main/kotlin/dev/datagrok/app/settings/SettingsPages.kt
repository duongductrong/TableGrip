package dev.datagrok.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.datagrok.app.ui.parseHexColor
import dev.datagrok.app.AppState
import dev.datagrok.app.backup.BackupsPanel
import dev.datagrok.app.tools.ToolsPanel
import dev.datagrok.core.tools.ToolFamily
import dev.datagrok.app.AppTheme
import dev.datagrok.core.agent.provider.AgentMode
import dev.datagrok.core.agent.provider.ProviderProtocol
import dev.datagrok.core.connection.AiAccess
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.settings.Settings
import dev.datagrok.app.LeftTool
import dev.datagrok.app.console.SyntaxPreview
import dev.datagrok.app.console.SyntaxScheme
import dev.datagrok.app.console.SyntaxToken
import dev.datagrok.app.console.syntaxColor
import dev.datagrok.app.grid.CellFormat
import dev.datagrok.app.ui.DefaultAccent
import dev.datagrok.app.ui.theme.ThemeRegistry
import dev.datagrok.app.ui.theme.colorTheme
import dev.datagrok.app.ui.theme.isDarkMode
import dev.datagrok.app.ui.KeymapPreset
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.app.ui.defaultEnvironmentColor
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.sql.KeywordCase

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
        if (text.trim().toIntOrNull()?.takeIf { it in range } == null) "Must be a number from ${range.first} to ${range.last}" else null
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
    fun error(text: String): String? = if (text.isBlank() || parseHexColor(text) != null) null else "A colour like #3574F0"
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
                group = "Appearance & Behavior",
                title = "Appearance",
                description = "How Datagrok looks.",
                sections =
                    listOf(
                        SettingsSection(
                            "Theme",
                            listOf(
                                ChoiceField(
                                    "Mode",
                                    AppTheme.entries,
                                    AppTheme::label,
                                    get = { s -> AppTheme.of(s.theme.mode) },
                                    set = { s, mode -> s.copy(theme = s.theme.copy(mode = mode.name)) },
                                    hint = "\"Sync with OS\" follows the system's light or dark mode.",
                                ),
                                themeField(dark = true),
                                themeField(dark = false),
                                ColorField(
                                    "Accent colour",
                                    Settings::accentColor,
                                    { s, v -> s.copy(accentColor = v) },
                                    fallback = { s -> s.copy(accentColor = "").colorTheme(s.isDarkMode()).islands.accent },
                                    swatches = AccentSwatches,
                                    hint = "Over every theme: selected tool buttons, primary buttons, the statement marker in consoles and other highlights.",
                                ),
                            ),
                            panel = { app -> ThemeFilesPanel(app) },
                            keywords = listOf("dark", "light", "colour theme", "color theme", "settings.json", "json", "VS Code", "Dracula", "One Dark", "Catppuccin", "Contrast"),
                        ),
                        SettingsSection(
                            "Layout",
                            listOf(
                                ChoiceField(
                                    "Font size",
                                    listOf(0, 11, 12, 13, 14, 15, 16),
                                    { if (it == 0) "Default" else "$it pt" },
                                    Settings::uiFontSize,
                                    { s, v -> s.copy(uiFontSize = v) },
                                    hint = "Labels, menus and controls. The SQL editor has its own size.",
                                ),
                                ChoiceField(
                                    "Panel corners",
                                    listOf(0, 6, 10, 14),
                                    { when (it) { 0 -> "Square"; 6 -> "Slightly rounded"; 10 -> "Rounded (default)"; else -> "Very rounded" } },
                                    Settings::islandCornerRadius,
                                    { s, v -> s.copy(islandCornerRadius = v) },
                                ),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "color-scheme",
                group = "Appearance & Behavior",
                title = "Color Scheme",
                description = "Colours of SQL in consoles and of connection environments. Empty fields use the scheme's colour.",
                sections =
                    listOf(
                        SettingsSection(
                            "Syntax",
                            listOf(
                                ChoiceField(
                                    "Scheme",
                                    SyntaxScheme.entries,
                                    SyntaxScheme::label,
                                    get = { s -> SyntaxScheme.of(s.syntaxScheme) },
                                    set = { s, scheme -> s.copy(syntaxScheme = scheme.name) },
                                    hint = "\"Theme's own\" uses the colour theme's tokens. Other schemes have a dark and a light variant, picked by the mode. The preview shows the current one.",
                                ),
                            ),
                            panel = { SyntaxPreview() },
                        ),
                        SettingsSection("Dark theme colours", SyntaxToken.entries.map { syntaxColorField(it, dark = true) }, reset = { s, _ -> s.copy(syntaxColors = emptyMap()) }),
                        SettingsSection("Light theme colours", SyntaxToken.entries.map { syntaxColorField(it, dark = false) }),
                        SettingsSection(
                            "Environments",
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
                group = "Appearance & Behavior",
                title = "Startup & Layout",
                description = "What Datagrok opens with, what it remembers, and what the tool stripe and status bar show.",
                sections =
                    listOf(
                        SettingsSection(
                            "On start",
                            listOf(
                                BooleanField("Reopen tabs", Settings::restoreTabs, { s, v -> s.copy(restoreTabs = v) }, hint = "Consoles (with unsaved text) and tables from the last session."),
                                ChoiceField(
                                    "Tool window",
                                    listOf("LastUsed", "None") + LeftTool.entries.map { it.name },
                                    ::startupToolLabel,
                                    Settings::startupTool,
                                    { s, v -> s.copy(startupTool = v) },
                                ),
                                ChoiceField(
                                    "Agent chat",
                                    listOf("Closed", "Open", "LastUsed"),
                                    { when (it) { "Open" -> "Open"; "LastUsed" -> "As last time"; else -> "Closed" } },
                                    Settings::startupAgent,
                                    { s, v -> s.copy(startupAgent = v) },
                                ),
                                BooleanField("Remember window size", Settings::rememberWindowBounds, { s, v -> s.copy(rememberWindowBounds = v) }),
                                BooleanField("Remember panel widths", Settings::rememberPanelSizes, { s, v -> s.copy(rememberPanelSizes = v) }, hint = "The tool window's and the agent chat's."),
                            ),
                        ),
                        SettingsSection(
                            "Status bar",
                            listOf(
                                BooleanField("Breadcrumbs", Settings::statusBreadcrumbs, { s, v -> s.copy(statusBreadcrumbs = v) }, hint = "Connection, schema and table of the tab in front."),
                                BooleanField("Selection summary", Settings::statusSelectionStats, { s, v -> s.copy(statusSelectionStats = v) }, hint = "Count, sum and average of the selected cells."),
                            ),
                        ),
                        SettingsSection(
                            "Tool stripe",
                            emptyList(),
                            panel = { app -> StripePanel(app) },
                            keywords = listOf("sidebar", "buttons", "hide", "order"),
                            reset = { s, d -> s.copy(stripeHidden = d.stripeHidden, stripeOrder = d.stripeOrder) },
                        ),
                    ),
            ),
            SettingsPage(
                id = "keymap",
                group = "Appearance & Behavior",
                title = "Keymap",
                description = "Keyboard shortcuts. Start from a preset, then click a shortcut to record another one.",
                sections =
                    listOf(
                        SettingsSection(
                            null,
                            listOf(
                                ChoiceField(
                                    "Preset",
                                    KeymapPreset.entries,
                                    KeymapPreset::label,
                                    get = { s -> KeymapPreset.of(s.keymap) },
                                    set = { s, preset -> s.copy(keymap = preset.name) },
                                    hint = "Your own changes stay on top of whichever preset is chosen.",
                                ),
                            ),
                            panel = { app -> KeymapPanel(app) },
                            keywords = listOf("shortcut", "keyboard", "hotkey", "binding"),
                            reset = { s, d -> s.copy(keyBindings = d.keyBindings) },
                        ),
                    ),
            ),
            SettingsPage(
                id = "editor",
                group = "Editor",
                title = "SQL Editor",
                description = "Typing and reading SQL in consoles.",
                sections =
                    listOf(
                        SettingsSection(
                            "Font",
                            listOf(
                                ChoiceField(
                                    "Font",
                                    lazyList { listOf("") + monospaceFonts() },
                                    { it.ifEmpty { "JetBrains Mono (built in)" } },
                                    Settings::editorFontFamily,
                                    { s, v -> s.copy(editorFontFamily = v) },
                                    hint = "Monospaced fonts installed on this machine. Also used by result grids.",
                                ),
                                IntField("Font size", 9..32, Settings::editorFontSize, { s, v -> s.copy(editorFontSize = v) }, unit = "pt"),
                                IntField("Line height", 100..300, Settings::editorLineHeight, { s, v -> s.copy(editorLineHeight = v) }, unit = "%", hint = "Of the font size."),
                            ),
                        ),
                        SettingsSection(
                            "Indentation",
                            listOf(
                                ChoiceField("Tab size", listOf(2, 4, 8), { "$it" }, Settings::tabSize, { s, v -> s.copy(tabSize = v) }),
                                ChoiceField("Indent with", listOf(false, true), { if (it) "Tabs" else "Spaces" }, Settings::indentWithTabs, { s, v -> s.copy(indentWithTabs = v) }),
                            ),
                        ),
                        SettingsSection(
                            "Typing",
                            listOf(
                                BooleanField("Close brackets and quotes", Settings::autoCloseBrackets, { s, v -> s.copy(autoCloseBrackets = v) }),
                                BooleanField(
                                    "Complete as you type",
                                    Settings::completionWhileTyping,
                                    { s, v -> s.copy(completionWhileTyping = v) },
                                    hint = "Off: suggestions only open with ${Shortcuts.Completion}.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "Display",
                            listOf(
                                BooleanField("Line numbers", Settings::showLineNumbers, { s, v -> s.copy(showLineNumbers = v) }),
                                BooleanField("Highlight the current line", Settings::highlightCurrentLine, { s, v -> s.copy(highlightCurrentLine = v) }),
                            ),
                        ),
                        SettingsSection(
                            "Reformat SQL",
                            listOf(
                                ChoiceField(
                                    "Keywords",
                                    KeywordCase.entries,
                                    { when (it) { KeywordCase.Upper -> "UPPER CASE"; KeywordCase.Lower -> "lower case"; KeywordCase.Preserve -> "As typed" } },
                                    get = { s -> KeywordCase.of(s.formatKeywordCase) },
                                    set = { s, case -> s.copy(formatKeywordCase = case.name) },
                                    hint = "Indents follow the settings above.",
                                ),
                            ),
                            keywords = listOf("format", "style"),
                        ),
                    ),
            ),
            SettingsPage(
                id = "queries",
                group = "Database",
                title = "Query Execution",
                description = "Limits applied when running statements from a console.",
                sections =
                    listOf(
                        SettingsSection(
                            "Results",
                            listOf(
                                IntField(
                                    "Max result rows",
                                    10..1_000_000,
                                    Settings::maxResultRows,
                                    { s, v -> s.copy(maxResultRows = v) },
                                    unit = "rows",
                                    hint = "Rows beyond this are not fetched; the result says it was cut short.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "Timeouts",
                            listOf(
                                IntField(
                                    "Query timeout",
                                    0..86_400,
                                    Settings::queryTimeoutSeconds,
                                    { s, v -> s.copy(queryTimeoutSeconds = v) },
                                    unit = "seconds",
                                    hint = "0 waits as long as the query takes.",
                                ),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "drivers",
                group = "Database",
                title = "Drivers",
                description = "Where JDBC drivers come from, and the ones on this machine. None ships with Datagrok; each is downloaded the first time it's needed.",
                sections =
                    listOf(
                        SettingsSection(
                            "Downloads",
                            listOf(
                                ChoiceField(
                                    "Downloading drivers",
                                    listOf(false, true),
                                    { if (it) "Automatic for permissive licenses" else "Ask every time" },
                                    Settings::autoDownloadDrivers,
                                    { s, v -> s.copy(autoDownloadDrivers = v) },
                                    hint = "LGPL drivers (MariaDB) always ask. GPL and vendor drivers (MySQL Connector/J, Oracle) are never downloaded: you choose their jars.",
                                ),
                                StringField(
                                    "Maven repository",
                                    Settings::driverRepository,
                                    { s, v -> s.copy(driverRepository = v.trim()) },
                                    placeholder = DriverLibrary.MavenCentral,
                                    hint = "Leave empty for Maven Central, or use your company's mirror. Downloads are checked against Datagrok's checksums either way.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "Installed",
                            emptyList(),
                            panel = { app -> DriversPanel(app, installed = true) },
                            keywords = DriverId.entries.map { it.displayName },
                        ),
                        SettingsSection("Available", emptyList(), panel = { app -> DriversPanel(app, installed = false) }),
                    ),
            ),
            SettingsPage(
                id = "tools",
                group = "Database",
                title = "Tools",
                description =
                    "Command-line clients Datagrok runs for backups and restores. They're never bundled: Datagrok uses the ones on this machine, " +
                        "or downloads official builds when you agree to it.",
                sections =
                    listOf(
                        SettingsSection("PostgreSQL", emptyList(), panel = { app -> ToolsPanel(app, ToolFamily.PostgreSQL) }, keywords = listOf("pg_dump", "pg_restore", "psql", "backup")),
                        SettingsSection("MySQL and MariaDB", emptyList(), panel = { app -> ToolsPanel(app, ToolFamily.MySQL) }, keywords = listOf("mysqldump", "mariadb-dump", "mysql", "backup")),
                    ),
            ),
            SettingsPage(
                id = "backups",
                group = "Database",
                title = "Backups",
                description = "Backups Datagrok makes on a schedule. Nothing runs unless you turn it on.",
                sections = listOf(SettingsSection(null, emptyList(), panel = { app -> BackupsPanel(app) }, keywords = listOf("schedule", "backup", "pg_dump", "mysqldump"))),
            ),
            SettingsPage(
                id = "data-editor",
                group = "Database",
                title = "Data Editor",
                description = "Browsing and editing table data, and how result grids show values.",
                sections =
                    listOf(
                        SettingsSection(
                            "Paging",
                            listOf(
                                IntField(
                                    "Page size",
                                    10..10_000,
                                    Settings::pageSize,
                                    { s, v -> s.copy(pageSize = v) },
                                    unit = "rows",
                                    hint = "Rows loaded per page when opening a table.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "Grid",
                            listOf(
                                ChoiceField("Row density", listOf("Compact", "Comfortable", "Spacious"), { it }, Settings::gridDensity, { s, v -> s.copy(gridDensity = v) }),
                                BooleanField("Stripe alternate rows", Settings::zebraRows, { s, v -> s.copy(zebraRows = v) }),
                            ),
                        ),
                        SettingsSection(
                            "Values",
                            listOf(
                                StringField(
                                    "NULL shows as",
                                    Settings::nullText,
                                    { s, v -> s.copy(nullText = v) },
                                    placeholder = "(empty)",
                                    hint = "Only how cells read; copying and editing still see NULL.",
                                ),
                                StringField(
                                    "Date-time format",
                                    Settings::dateTimeFormat,
                                    { s, v -> s.copy(dateTimeFormat = v.trim()) },
                                    placeholder = "As the database returns it",
                                    hint = "A java.time pattern, e.g. yyyy-MM-dd HH:mm:ss or dd/MM/yyyy HH:mm. Copying keeps the original value.",
                                    error = { if (CellFormat.isValidPattern(it.trim())) null else "Not a date-time pattern" },
                                ),
                                ChoiceField(
                                    "Date-time zone",
                                    listOf("Local", "Utc"),
                                    { if (it == "Utc") "UTC" else "This computer's (${java.time.ZoneId.systemDefault().id})" },
                                    Settings::dateTimeZone,
                                    { s, v -> s.copy(dateTimeZone = v) },
                                    hint = "For values that carry an offset, when a format is set. Values without one are shown as stored.",
                                ),
                                BooleanField("Group thousands", Settings::numberGrouping, { s, v -> s.copy(numberGrouping = v) }, hint = "1234567.5 reads 1,234,567.5."),
                            ),
                        ),
                    ),
            ),
            SettingsPage(
                id = "agent",
                group = "Tools",
                title = "Agent",
                description = "Where the agent chat gets its answers. Changing these sends nothing: the agent is only asked when you send a message.",
                sections =
                    listOf(
                        SettingsSection(
                            "Source",
                            listOf(
                                ChoiceField(
                                    "Agents from",
                                    AgentMode.entries,
                                    AgentMode::label,
                                    get = { s -> AgentMode.of(s.agentMode) },
                                    set = { s, mode -> s.copy(agentMode = mode.name) },
                                    hint = "Command-line agents run the CLIs installed here (Claude Code, Codex, Gemini CLI…) on their own accounts. An API provider is called directly with your key.",
                                ),
                            ),
                        ),
                        SettingsSection(
                            "API provider",
                            listOf(
                                ChoiceField(
                                    "Protocol",
                                    ProviderProtocol.entries,
                                    ProviderProtocol::title,
                                    get = { s -> ProviderProtocol.of(s.agentProvider) },
                                    set = { s, protocol -> s.copy(agentProvider = protocol.name) },
                                    hint = "OpenAI-compatible also covers OpenRouter, LiteLLM, Ollama, LM Studio and vLLM. Used when agents come from an API provider.",
                                ),
                                StringField(
                                    "Base URL",
                                    Settings::agentBaseUrl,
                                    { s, v -> s.copy(agentBaseUrl = v.trim()) },
                                    placeholder = "Protocol default",
                                    hint = "Empty uses ${ProviderProtocol.entries.joinToString(" or ") { it.defaultBaseUrl }}. For a local server, e.g. http://localhost:11434/v1.",
                                ),
                                StringField(
                                    "Model",
                                    Settings::agentModel,
                                    { s, v -> s.copy(agentModel = v.trim()) },
                                    placeholder = "Protocol default",
                                    hint = "Empty uses ${ProviderProtocol.entries.joinToString(" or ") { it.defaultModel }}.",
                                ),
                            ),
                            panel = { app -> ApiKeyPanel(app) },
                            keywords = listOf("API key", "token", "Anthropic", "OpenAI", "Ollama"),
                        ),
                        SettingsSection(
                            "Data shared with agents",
                            listOf(
                                ChoiceField(
                                    "Connections share",
                                    AiAccess.entries,
                                    AiAccess::label,
                                    get = { s -> AiAccess.of(s.aiAccess) },
                                    set = { s, access -> s.copy(aiAccess = access.name) },
                                    hint =
                                        "What a connection may send to the agent, unless its own Options say otherwise. Schema only sends names, types and your SQL, " +
                                            "never values from the data. Production connections never share data unless their own Options allow it.",
                                ),
                            ),
                            keywords = listOf("privacy", "AI", "sample data", "sensitive"),
                        ),
                    ),
            ),
        )

    fun byId(id: String): SettingsPage? = all.firstOrNull { it.id == id }
}

/** Picks the theme used in [dark] or light mode from the built-in and the user's themes; an unknown name shows as Graphite. */
private fun themeField(dark: Boolean): ChoiceField<String> =
    ChoiceField(
        if (dark) "Dark theme" else "Light theme",
        liveList { ThemeRegistry.names(dark) },
        { it },
        get = { s -> ThemeRegistry.resolve(if (dark) s.theme.dark else s.theme.light, dark).name },
        set = { s, name -> s.copy(theme = if (dark) s.theme.copy(dark = name) else s.theme.copy(light = name)) },
        hint = if (dark) "Used in dark mode. Your own themes go in ~/.datagrok/themes." else "Used in light mode.",
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
        "LastUsed" -> "As last time"
        "None" -> "None"
        LeftTool.Explorer.name -> "Connections"
        LeftTool.History.name -> "Query History"
        LeftTool.Queries.name -> "Saved Queries"
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
