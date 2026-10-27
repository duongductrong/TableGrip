package dev.datagrok.core.connection

import dev.datagrok.core.DatagrokPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

/**
 * Persists connection profiles as JSON in [file], and their passwords in [secrets].
 *
 * Not thread-safe: call it from a single thread (the UI one).
 */
class ConnectionStore(
    private val file: Path = DatagrokPaths.connections,
    private val secrets: SecretStore = KeyringSecretStore(),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): List<ConnectionConfig> = if (file.exists()) json.decodeFromString(file.readText()) else emptyList()

    /** Inserts or replaces [config]. A null [password] keeps the stored one; an empty one removes it. */
    fun save(config: ConnectionConfig, password: String?): List<ConnectionConfig> {
        val current = load()
        val updated =
            if (current.any { it.id == config.id }) current.map { if (it.id == config.id) config else it }
            else current + config
        write(updated)
        when {
            password == null -> Unit
            password.isEmpty() -> secrets.delete(config.id)
            else -> secrets.set(config.id, password)
        }
        return updated
    }

    fun delete(id: String): List<ConnectionConfig> {
        val updated = load().filterNot { it.id == id }
        write(updated)
        secrets.delete(id)
        return updated
    }

    fun password(id: String): String? = secrets.get(id)

    private fun write(configs: List<ConnectionConfig>) {
        file.parent?.createDirectories()
        // Write to a temp file first so a crash never leaves a half-written profile list behind.
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        tmp.writeText(json.encodeToString(configs))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
