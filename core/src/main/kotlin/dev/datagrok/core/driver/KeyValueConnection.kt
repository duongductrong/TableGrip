package dev.datagrok.core.driver

import dev.datagrok.core.connection.ConnectionConfig

/** One of a key-value server's databases: Redis's numbered ones (one only in cluster mode). [keys] is null if unknown. */
data class Keyspace(val index: Int, val keys: Long?)

enum class KeyType(val label: String) {
    Text("string"),
    Hash("hash"),
    List("list"),
    Set("set"),
    SortedSet("zset"),
    Stream("stream"),
    Json("json"),
    Other("other"),
}

/** A key as the browser lists it. [ttlMillis] is null for keys that never expire. */
data class KeyInfo(val name: String, val type: KeyType, val ttlMillis: Long?)

/** A page of a scan; [cursor] continues it, null when the scan is complete. */
data class KeyPage(val keys: List<KeyInfo>, val cursor: String?)

data class StreamEntry(val id: String, val fields: List<Pair<String, String>>)

/**
 * A key's value, read up to a limit: [total] is the full size, so the browser can say how much is shown. Strings over the
 * limit come back cut, with [Text.truncated].
 */
sealed interface KeyValue {
    val key: String
    val ttlMillis: Long?

    data class Text(override val key: String, override val ttlMillis: Long?, val value: String, val truncated: Boolean) : KeyValue

    data class Hash(override val key: String, override val ttlMillis: Long?, val fields: List<Pair<String, String>>, val total: Long) : KeyValue

    data class ListValue(override val key: String, override val ttlMillis: Long?, val items: List<String>, val total: Long) : KeyValue

    data class SetValue(override val key: String, override val ttlMillis: Long?, val members: List<String>, val total: Long) : KeyValue

    data class SortedSet(override val key: String, override val ttlMillis: Long?, val members: List<Pair<String, Double>>, val total: Long) : KeyValue

    data class Stream(override val key: String, override val ttlMillis: Long?, val entries: List<StreamEntry>, val total: Long) : KeyValue

    /** A type the browser doesn't edit (JSON, time series, probabilistic structures…): shown as the server describes it. */
    data class Other(override val key: String, override val ttlMillis: Long?, val type: String, val description: String) : KeyValue
}

/** A change to one key. Edits of a missing key create it with that type. */
sealed interface KeyEdit {
    data class SetText(val value: String) : KeyEdit

    data class SetField(val field: String, val value: String) : KeyEdit

    data class DeleteField(val field: String) : KeyEdit

    data class SetItem(val index: Long, val value: String) : KeyEdit

    data class AppendItem(val value: String) : KeyEdit

    /** Removes the first occurrence of [value]. */
    data class RemoveItem(val value: String) : KeyEdit

    data class AddMember(val member: String) : KeyEdit

    data class RemoveMember(val member: String) : KeyEdit

    data class SetScore(val member: String, val score: Double) : KeyEdit

    data class AddEntry(val fields: List<Pair<String, String>>) : KeyEdit

    data class DeleteEntry(val id: String) : KeyEdit
}

/** Keys and values: what the key browser and the command console work with. */
interface KeyValueConnection : DatabaseConnection {
    /** The server, e.g. "Redis 7.4.2" or "Valkey 8.1.0", and its mode. */
    suspend fun server(): String

    suspend fun keyspaces(): List<Keyspace>

    /** Keys matching the glob [pattern], a page at a time; [count] is a hint, not a limit. */
    suspend fun scan(keyspace: Int, pattern: String, cursor: String?, count: Int = 200): KeyPage

    /** The value of [key] with up to [limit] elements, or null if it doesn't exist. */
    suspend fun read(keyspace: Int, key: String, limit: Int = 1_000): KeyValue?

    suspend fun edit(keyspace: Int, key: String, edit: KeyEdit)

    /** Deletes [keys], returning how many existed. */
    suspend fun delete(keyspace: Int, keys: List<String>): Long

    /** Renames [from] to [to]; fails if [to] exists. */
    suspend fun rename(keyspace: Int, from: String, to: String)

    /** Sets the time to live, or removes it when [ttlMillis] is null. */
    suspend fun expire(keyspace: Int, key: String, ttlMillis: Long?)

    /** Runs one console command (already split into arguments) and formats the reply the way redis-cli does. */
    suspend fun command(keyspace: Int, arguments: List<String>): String
}

/**
 * Implemented by an engine adapter, which Datagrok loads together with the client library the user downloaded (see
 * [dev.datagrok.core.drivers.DriverLibrary.loadClient]). [host] and [port] already point through any SSH tunnel.
 */
interface KeyValueClient {
    fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): KeyValueConnection
}
