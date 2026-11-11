package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ConnectionConfig

/** A collection as the explorer lists it. [kind] is `collection`, `view` or `timeseries`; [documents] is an estimate. */
data class CollectionInfo(val name: String, val kind: String, val documents: Long?)

/** A page of documents as relaxed Extended JSON, one string each; [hasMore] says whether another page follows. */
data class DocumentPage(val documents: List<String>, val hasMore: Boolean)

/**
 * A console command's reply: [text] as the shell prints it, and, for a command that returns documents (`find`,
 * `findOne`, `aggregate`, `runCommand`…), the [documents] themselves as relaxed Extended JSON (64-bit integers keep
 * their `$numberLong`), so the console can show them in a grid. [collection] is set when they are that collection's
 * documents as stored (`find`, `findOne`), which is what lets the console edit them; [hasMore] when more matched than
 * were returned.
 */
data class ShellReply(
    val text: String,
    val isError: Boolean = false,
    val documents: List<String>? = null,
    val collection: String? = null,
    val hasMore: Boolean = false,
)

/**
 * Databases, collections and documents: what the document browser and the shell console work with. Filters, sorts and
 * documents are Extended JSON, in the shell's relaxed syntax too (`{status: 'A', _id: ObjectId('…')}`).
 */
interface DocumentConnection : DatabaseConnection {
    /** The server, e.g. "MongoDB 8.0.4" and its topology. */
    suspend fun server(): String

    suspend fun databases(): List<String>

    suspend fun collections(database: String): List<CollectionInfo>

    /** [projection] picks the fields returned, as in `find()`'s second argument: `{name: 1}` or `{items: 0}`. */
    suspend fun find(database: String, collection: String, filter: String, sort: String, skip: Long, limit: Int, projection: String = "{}"): DocumentPage

    suspend fun count(database: String, collection: String, filter: String): Long

    /** Inserts [document], returning its `_id` as Extended JSON. */
    suspend fun insert(database: String, collection: String, document: String): String

    /**
     * Replaces the document whose `_id` is [id] (Extended JSON) with [document]. With [original], the document as it was
     * read, only the top-level fields that differ are written, and only while they still hold their original values: a
     * field changed by someone else since is never overwritten, and fields left alone keep their exact BSON types.
     */
    suspend fun replace(database: String, collection: String, id: String, document: String, original: String? = null)

    /**
     * Sets and removes top-level fields of the document whose `_id` is [id]. Values are Extended JSON. The write only
     * happens while every field in [expected] still holds the value it maps to (null: the field was missing), so an edit
     * made from a stale page never overwrites a newer change; returns false when it didn't match.
     */
    suspend fun update(database: String, collection: String, id: String, set: Map<String, String>, unset: Set<String>, expected: Map<String, String?>): Boolean

    /** Up to [size] documents picked at random, e.g. to learn the fields a collection's documents have. */
    suspend fun sample(database: String, collection: String, size: Int): List<String> = find(database, collection, "{}", "{}", 0, size).documents

    /** Deletes the documents with these `_id`s (Extended JSON each), returning how many were deleted. */
    suspend fun delete(database: String, collection: String, ids: List<String>): Long

    /** Index definitions as Extended JSON. */
    suspend fun indexes(database: String, collection: String): List<String>

    /**
     * Runs one shell command in [database]: `db.orders.find({…}).sort({…}).limit(5)`, `db.runCommand({…})`, `show
     * collections`… and returns the reply as the shell would print it. `use` is the console's own business.
     */
    suspend fun command(database: String, line: String): String

    /** [command], with the documents the reply holds, when there are any. */
    suspend fun shell(database: String, line: String): ShellReply = command(database, line).let { ShellReply(it, isError = it.startsWith("Error:")) }
}

/** Implemented by an engine adapter; see [KeyValueClient]. [host] and [port] point through any SSH tunnel. */
interface DocumentClient {
    fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): DocumentConnection
}
