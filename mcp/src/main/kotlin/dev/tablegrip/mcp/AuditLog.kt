package dev.tablegrip.mcp

import dev.tablegrip.core.TableGripPaths
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readLines
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One tool call, as Settings › MCP lists it. No arguments or results: those may hold data. */
@Serializable
data class AuditEntry(
    val time: Long,
    val client: String,
    val tool: String,
    val connection: String? = null,
    val ok: Boolean,
    val millis: Long,
    val error: String? = null,
)

/** `~/.tablegrip/mcp.log.jsonl`: every tool call any MCP client made, newest last, kept to the last few thousand. */
class AuditLog(private val file: Path = TableGripPaths.home.resolve("mcp.log.jsonl")) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun append(entry: AuditEntry) {
        runCatching {
            file.parent?.createDirectories()
            file.writeText(json.encodeToString(AuditEntry.serializer(), entry) + "\n", options = arrayOf(CREATE, APPEND))
            if (file.fileSize() > MaxBytes) file.writeLines(file.readLines().takeLast(KeepLines))
        }
    }

    /** Newest first. */
    fun recent(limit: Int = 50): List<AuditEntry> {
        if (!file.exists()) return emptyList()
        return runCatching { file.readLines() }.getOrDefault(emptyList())
            .asReversed()
            .asSequence()
            .mapNotNull { runCatching { json.decodeFromString(AuditEntry.serializer(), it) }.getOrNull() }
            .take(limit)
            .toList()
    }

    private companion object {
        const val MaxBytes = 2L * 1024 * 1024
        const val KeepLines = 5_000
    }
}
