package dev.tablegrip.core.history

import dev.tablegrip.core.TableGripPaths
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HistoryEntry(
    val connectionId: String,
    val sql: String,
    val executedAt: Long,
    val elapsedMillis: Long,
    val error: String? = null,
)

/** Append-only log of executed statements, one JSON object per line. */
class QueryHistory(private val file: Path = TableGripPaths.history) {
    private val json = Json { ignoreUnknownKeys = true }

    fun append(entry: HistoryEntry) {
        file.parent?.createDirectories()
        file.writeText(json.encodeToString(entry) + "\n", options = arrayOf(CREATE, APPEND))
    }

    /** Most recent first. Corrupt lines (e.g. from a crash mid-write) are skipped. */
    fun recent(connectionId: String? = null, limit: Int = 200): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .asReversed()
            .asSequence()
            .mapNotNull { line -> runCatching { json.decodeFromString<HistoryEntry>(line) }.getOrNull() }
            .filter { connectionId == null || it.connectionId == connectionId }
            .take(limit)
            .toList()
    }
}
