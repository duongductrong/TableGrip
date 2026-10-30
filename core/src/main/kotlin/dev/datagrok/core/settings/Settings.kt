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
