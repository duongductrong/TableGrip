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
