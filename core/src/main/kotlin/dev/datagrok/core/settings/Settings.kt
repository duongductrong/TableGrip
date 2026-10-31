package dev.datagrok.core.settings

import dev.datagrok.core.DatagrokPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    /** One of the app's theme names; kept as a string so core stays UI-free. */
    val theme: String = "System",
    val editorFontSize: Int = 13,
    /** Rows fetched per page in table viewers. */
    val pageSize: Int = 200,
    /** Maximum rows kept from a console query. */
    val maxResultRows: Int = 1_000,
    /** 0 means no timeout. */
    val queryTimeoutSeconds: Int = 0,
    /** How table viewers filter: `Builder` (structured conditions) or `Sql` (a typed WHERE clause). */
    val filterMode: String = "Builder",
    /** Maven repository drivers download from; a company mirror works too. Blank means Maven Central. */
    val driverRepository: String = "",
    /** Download permissively licensed drivers without asking. LGPL drivers always ask; GPL and vendor drivers never download. */
    val autoDownloadDrivers: Boolean = false,
    /** Id of the agent the chat panel last used; blank picks the first one found. */
    val agent: String = "",
    /** The model the chat asks each CLI agent for, by agent id; missing means the agent's own default. */
    val agentModels: Map<String, String> = emptyMap(),
    /** Where agents come from: `Cli` (the CLIs installed here) or `Provider` (the HTTP API below). Missing means `Cli`. */
    val agentMode: String = "Cli",
    /** The provider's protocol: `Anthropic` or `OpenAi` (any OpenAI-compatible endpoint). Its API key is in the keychain. */
    val agentProvider: String = "Anthropic",
    /** Blank means the protocol's own endpoint. */
    val agentBaseUrl: String = "",
    /** Blank means the protocol's default model. */
    val agentModel: String = "",
    /**
     * What connections may send to an AI agent unless they say otherwise: an [dev.datagrok.core.connection.AiAccess]
     * name. Missing or unknown means `SchemaOnly`, so no data leaves without the user choosing it.
     */
    val aiAccess: String = "SchemaOnly",
    /** Client tools the user chose by hand in Settings → Tools, by [dev.datagrok.core.tools.Tool] name. */
    val toolPaths: Map<String, String> = emptyMap(),
    /** Runs [backupSchedules] while Datagrok is open. Off unless the user turns it on. */
    val scheduledBackups: Boolean = false,
    val backupSchedules: List<dev.datagrok.core.backup.BackupSchedule> = emptyList(),

    // Appearance
    /** `#RRGGBB`; blank means the theme's own blue. */
    val accentColor: String = "",
    /** Points for labels and controls; 0 means Jewel's default. */
    val uiFontSize: Int = 0,
    /** Corner radius of the content islands, in dp. */
    val islandCornerRadius: Int = 10,
    /** A syntax scheme's name, e.g. `Darcula`; unknown names fall back to `Datagrok`. */
    val syntaxScheme: String = "Datagrok",
    /** Single token colours over the scheme, keyed `dark.keyword`, `light.string`… as `#RRGGBB`. */
    val syntaxColors: Map<String, String> = emptyMap(),
    /** Environment badge colours by environment name (`Production`…) as `#RRGGBB`; missing means the built-in one. */
    val environmentColors: Map<String, String> = emptyMap(),

    // SQL editor
    /** A font family installed on this machine; blank means the bundled JetBrains Mono. */
    val editorFontFamily: String = "",
    /** Line height as a percentage of the font size. */
    val editorLineHeight: Int = 150,
    val tabSize: Int = 4,
    val indentWithTabs: Boolean = false,
    val autoCloseBrackets: Boolean = true,
    /** Opens completion while typing; off leaves it to ⌃Space. */
    val completionWhileTyping: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val showLineNumbers: Boolean = true,
    /** How Reformat SQL writes keywords: `Upper`, `Lower` or `Preserve`. */
    val formatKeywordCase: String = "Upper",

    // Data grid
    /** `Compact`, `Comfortable` or `Spacious`. */
    val gridDensity: String = "Comfortable",
    /** What an empty (NULL) cell reads. */
    val nullText: String = "<null>",
    /** A `java.time` pattern for date-time cells; blank shows them as the database returns them. */
    val dateTimeFormat: String = "",
    /** The zone date-times with an offset are shown in when [dateTimeFormat] is set: `Utc` or `Local`. */
    val dateTimeZone: String = "Local",
    /** Groups the digits of numeric cells by thousands. */
    val numberGrouping: Boolean = false,
    /** Tints every other row. */
    val zebraRows: Boolean = false,

    // Keymap
    /** A keymap preset's name; unknown names fall back to `Default`. */
    val keymap: String = "Default",
    /** Shortcuts the user changed by hand, by action id, over [keymap]. */
    val keyBindings: Map<String, KeyBinding> = emptyMap(),

    // Behaviour
    /** Reopens the main window at the size and place it was closed. */
    val rememberWindowBounds: Boolean = true,
    /** Keeps the widths of the tool window and the agent chat across restarts. */
    val rememberPanelSizes: Boolean = true,
    val restoreTabs: Boolean = true,
    /** The tool window shown at start: `LastUsed`, `None` or a tool's name. */
    val startupTool: String = "LastUsed",
    /** Whether the agent chat is open at start: `Closed`, `Open` or `LastUsed`. */
    val startupAgent: String = "Closed",
    val statusBreadcrumbs: Boolean = true,
    val statusSelectionStats: Boolean = true,
    /** Tool stripe buttons the user hid, by id. */
    val stripeHidden: List<String> = emptyList(),
    /** Tool stripe buttons in the user's order, by id; ids missing here keep their place after these. */
    val stripeOrder: List<String> = emptyList(),
)

/**
 * A key combination in a UI-free form: [key] is the AWT key code, [label] how it reads (`E`, `⏎`). `primary` is ⌘ on
 * macOS and Ctrl elsewhere.
 */
@Serializable
data class KeyBinding(
    val key: Int,
    val label: String,
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val ctrl: Boolean = false,
)

class SettingsStore(private val file: Path = DatagrokPaths.settings) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Falls back to defaults when the file is missing or unreadable, so a bad edit never blocks startup. */
    fun load(): Settings = runCatching { if (file.exists()) json.decodeFromString<Settings>(file.readText()) else null }.getOrNull() ?: Settings()

    fun save(settings: Settings) {
        file.parent?.createDirectories()
        file.writeText(json.encodeToString(settings))
    }
}
