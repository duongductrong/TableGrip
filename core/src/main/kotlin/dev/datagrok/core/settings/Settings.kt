package dev.datagrok.core.settings

import dev.datagrok.core.DatagrokPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import dev.datagrok.core.theme.ThemeOverride
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class Settings(
    /** Light or dark (or the OS's choice), and the theme used for each; a bare string from older files is the mode. */
    val theme: @Serializable(with = ThemeSelectionSerializer::class) ThemeSelection = ThemeSelection(),
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
    /** `#RRGGBB`; blank means the theme's own accent. */
    val accentColor: String = "",
    /** Keys laid over a theme, by the theme's name, e.g. `{ "One Dark": { "colors": { "accent": "#E5C07B" } } }`. */
    val themeOverrides: Map<String, ThemeOverride> = emptyMap(),
    /** Points for labels and controls; 0 means Jewel's default. */
    val uiFontSize: Int = 0,
    /** Corner radius of the content islands, in dp. */
    val islandCornerRadius: Int = 10,
    /** A syntax scheme's name, e.g. `Darcula`; `Theme` (and unknown names) use the theme's own token colours. */
    val syntaxScheme: String = "Theme",
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
 * Which themes the app uses, after Zed's `"theme": { "mode", "light", "dark" }`. [mode] is `System`, `Light` or `Dark`;
 * [dark] and [light] are theme names, e.g. `One Dark`. Names that no theme has fall back to Graphite.
 */
@Serializable
data class ThemeSelection(
    val mode: String = "System",
    val dark: String = DefaultDarkTheme,
    val light: String = DefaultLightTheme,
) {
    companion object {
        const val DefaultDarkTheme = "Graphite Dark"
        const val DefaultLightTheme = "Graphite Light"
    }
}

/** Reads `"theme": "Dark"` from files written before themes had names as `{ "mode": "Dark" }`. */
object ThemeSelectionSerializer : JsonTransformingSerializer<ThemeSelection>(ThemeSelection.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive) buildJsonObject { put("mode", element) } else element
}

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

/**
 * settings.json, which people may also edit by hand like VS Code's or Zed's: comments and trailing commas are allowed,
 * and only the settings that differ from the defaults are written, so the file stays short.
 */
class SettingsStore(val file: Path = DatagrokPaths.settings) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        encodeDefaults = false
        allowComments = true
        allowTrailingComma = true
    }

    /**
     * Why the file couldn't be read the last time [load] tried, or null. While it is set, [save] leaves the file alone,
     * so a typo made by hand is never replaced by defaults: the user fixes the file and the app picks it up again.
     */
    @Volatile
    var loadError: String? = null
        private set

    /** Falls back to defaults when the file is missing or unreadable, so a bad edit never blocks startup. */
    fun load(): Settings {
        if (!file.exists()) {
            loadError = null
            return Settings()
        }
        return try {
            json.decodeFromString<Settings>(file.readText()).also { loadError = null }
        } catch (e: SerializationException) {
            loadError = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
            Settings()
        } catch (e: IllegalArgumentException) {
            loadError = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
            Settings()
        }
    }

    /** The text this store last wrote, so a watcher can tell the app's own saves from edits made by hand. */
    @Volatile
    var lastWritten: String? = null
        private set

    /** Writes [settings]; false, leaving the file as it is, while it holds an edit that doesn't parse (see [loadError]). */
    @Synchronized
    fun save(settings: Settings): Boolean {
        if (loadError != null) return false
        file.parent?.createDirectories()
        val text = encode(settings)
        lastWritten = text
        file.writeText(text)
        return true
    }

    /** The file's text, or null when there is none. */
    fun readText(): String? = if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    fun encode(settings: Settings): String = json.encodeToString(settings) + "\n"
}
