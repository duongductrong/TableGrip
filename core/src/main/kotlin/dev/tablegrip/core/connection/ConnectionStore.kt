package dev.tablegrip.core.connection

import dev.tablegrip.core.TableGripPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Persists connection profiles as JSON in [file], and their passwords in [secrets].
 *
 * Not thread-safe: call it from a single thread (the UI one).
 */
class ConnectionStore(
    private val file: Path = TableGripPaths.connections,
    /** Also holds other secrets that stay out of the profile files, such as the agent provider's API key. */
    val secrets: SecretStore = KeyringSecretStore(),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * The profiles this version understands. Others (an engine added by a newer TableGrip) are skipped, not lost:
     * [write] keeps them in the file.
     */
    fun load(): List<ConnectionConfig> = readAll().mapNotNull { it.second }

    private fun readAll(): List<Pair<JsonElement, ConnectionConfig?>> {
        if (!file.exists()) return emptyList()
        return json.parseToJsonElement(file.readText()).jsonArray.map { element ->
            element to runCatching { json.decodeFromJsonElement(ConnectionConfig.serializer(), element) }.getOrNull()
        }
    }

    /**
     * Inserts or replaces [config]. For [password] and [sshSecret] (SSH password or key passphrase), null keeps the
     * stored value and an empty string removes it.
     */
    fun save(config: ConnectionConfig, password: String?, sshSecret: String? = null): List<ConnectionConfig> {
        val current = load()
        val updated =
            if (current.any { it.id == config.id }) current.map { if (it.id == config.id) config else it }
            else current + config
        write(updated)
        storeSecret(config.id, password)
        storeSecret(sshKey(config.id), sshSecret)
        return updated
    }

    private fun storeSecret(key: String, value: String?) {
        when {
            value == null -> Unit
            value.isEmpty() -> secrets.delete(key)
            else -> secrets.set(key, value)
        }
    }

    fun delete(id: String): List<ConnectionConfig> {
        val updated = load().filterNot { it.id == id }
        write(updated)
        secrets.delete(id)
        secrets.delete(sshKey(id))
        return updated
    }

    fun password(id: String): String? = secrets.get(id)

    fun sshSecret(id: String): String? = secrets.get(sshKey(id))

    private fun sshKey(id: String) = "$id:ssh"

    private fun write(configs: List<ConnectionConfig>) {
        file.parent?.createDirectories()
        // Write to a temp file first so a crash never leaves a half-written profile list behind.
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        val unknown = readAll().filter { it.second == null }.map { it.first }
        tmp.writeText(json.encodeToString(JsonArray(configs.map { json.encodeToJsonElement(ConnectionConfig.serializer(), it) } + unknown)))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
