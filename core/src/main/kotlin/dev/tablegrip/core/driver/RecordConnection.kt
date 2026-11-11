package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig
import kotlinx.serialization.json.JsonObject

/**
 * Where records live, as the explorer lists it: a search index, a keyspace or a table, a graph label. [path] names it
 * from the top, so `[shop, orders]` is the table `orders` of the keyspace `shop`. [browsable] containers open in the
 * record browser; ones with [hasChildren] expand to list theirs. [count] is an estimate where the engine has one;
 * [detail] is a short note shown beside the name (health, primary key…).
 */
data class RecordContainer(
    val path: List<String>,
    val kind: String,
    val count: Long? = null,
    val detail: String? = null,
    val browsable: Boolean = true,
    val hasChildren: Boolean = false,
) {
    val name: String
        get() = path.last()
}

/**
 * One record. [document] is what the browser shows and edits, and what [RecordConnection.put] writes back. [key] says
 * which record a write replaces or deletes, along with any version it was read at, so that a write made from a stale
 * page fails rather than overwrite a newer change; null for records that can't be written back (a graph path, an
 * aggregation row). [meta] is shown before the document's fields but never written: an index's `_id` and score, a node's
 * labels.
 */
data class Record(val key: JsonObject?, val document: JsonObject, val meta: JsonObject = JsonObject(emptyMap()))

/** A page of records; [cursor] continues it, null on the last page. [total] counts every match, when the engine says. */
data class RecordPage(val records: List<Record>, val cursor: String?, val total: Long? = null, val totalIsLowerBound: Boolean = false)

/** How a language is written in the record browser's query field or the console, for its label, placeholder and help. */
data class RecordLanguage(val name: String, val placeholder: String, val help: String)

/** A console statement's reply: rows for the grid, or text (JSON, a status line) shown as it is. */
sealed interface ConsoleReply {
    val elapsedMillis: Long

    data class Table(val rows: QueryResult.Rows, val note: String? = null, override val elapsedMillis: Long = rows.elapsedMillis) : ConsoleReply

    data class Text(val text: String, override val elapsedMillis: Long = 0) : ConsoleReply
}

/**
 * Engines whose data isn't tables, keys or MongoDB documents: search indices, wide-column tables, graphs. Everything the
 * record browser, its console and the agent tools work with is here, so a new engine of any of these kinds needs an
 * adapter and no screen of its own. Documents are JSON objects; each engine maps its own types onto JSON and back.
 */
interface RecordConnection : DatabaseConnection {
    /** The server, e.g. "Elasticsearch 8.17.4" or "Apache Cassandra 5.0.3". */
    suspend fun server(): String

    /** The containers under [parent]; the top level for an empty path. */
    suspend fun containers(parent: List<String> = emptyList()): List<RecordContainer>

    /** The container's definition as the engine states it: an index's mappings and settings, a table's CREATE TABLE. */
    suspend fun definition(container: List<String>): String

    /** How the browser's query field is written: a search box, a WHERE clause… */
    val queryLanguage: RecordLanguage

    /**
     * A page of [container]'s records matching [query] (blank for all of them), up to [limit], continuing from [cursor]
     * (null for the first page).
     */
    suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage

    /** Whether the browser may add, change and delete records of [container] at all (a view or an alias may not). */
    fun canWrite(container: List<String>): Boolean = true

    /** A new record's starting document: the table's columns, the index's fields. */
    suspend fun template(container: List<String>): JsonObject = JsonObject(emptyMap())

    /**
     * Writes [document] into [container]: a new record when [key] is null, else the one [key] names, as long as it is
     * still as it was read. Returns the key of what was written.
     */
    suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject

    /** Deletes the records [keys] name, returning how many were deleted. */
    suspend fun delete(container: List<String>, keys: List<JsonObject>): Long

    /** The console's language: Dev Tools requests, CQL, Cypher… */
    val consoleLanguage: RecordLanguage

    /** A first statement for a console opened on [container]. */
    fun sampleStatement(container: List<String>): String

    /** Runs one console statement. */
    suspend fun execute(statement: String): ConsoleReply

    /**
     * Whether [statement] only reads, which is all a read-only profile may run. Anything not recognised counts as a
     * write: a missing read costs a little convenience, a missing write would cost data.
     */
    fun isRead(statement: String): Boolean
}

/** Implemented by an engine's client; see [KeyValueClient]. [host] and [port] point through any SSH tunnel. */
interface RecordClient {
    fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection
}
