package dev.tablegrip.engines.mongodb

import dev.tablegrip.core.i18n.tr
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCommandException
import com.mongodb.MongoCredential
import com.mongodb.MongoException
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.CollectionInfo
import dev.tablegrip.core.driver.DocumentClient
import dev.tablegrip.core.driver.DocumentConnection
import dev.tablegrip.core.driver.DocumentPage
import dev.tablegrip.core.driver.withContextClassLoader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.BsonArray
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.BsonValue
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings

/**
 * TableGrip's MongoDB adapter over the official Java driver. A profile connects with its connection string
 * (`mongodb://…`, `mongodb+srv://…`) or with host, port and credentials; properties add URI options such as `tls=true`,
 * `authSource=admin`, `replicaSet=rs0`. A single host connects directly unless a replica set is named, so a member that
 * advertises an internal address is still reachable.
 */
class MongoClient : DocumentClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): DocumentConnection =
        withContextClassLoader(MongoClient::class.java.classLoader) { open(config, host, port, password) }

    private fun open(config: ConnectionConfig, host: String, port: Int, password: String?): DocumentConnection {
        val connectionString = ConnectionString(config.url ?: uriFor(config, host, port))
        val builder = MongoClientSettings.builder().applyConnectionString(connectionString)
            .applyToClusterSettings { it.serverSelectionTimeout(10, TimeUnit.SECONDS) }
            .applyToSocketSettings { it.connectTimeout(10, TimeUnit.SECONDS) }
        // The password lives in the keychain, never in the profile's string: put it back into the credential.
        val user = connectionString.username ?: config.user.trim().takeIf { it.isNotEmpty() }
        if (user != null && !password.isNullOrEmpty() && connectionString.credential?.mechanism == null) {
            val source = connectionString.credential?.source ?: config.properties["authSource"] ?: "admin"
            builder.credential(MongoCredential.createCredential(user, source, password.toCharArray()))
        }
        val client = MongoClients.create(builder.build())
        return Connection(config, client, connectionString.database ?: config.database.trim().ifEmpty { null })
    }

    private fun uriFor(config: ConnectionConfig, host: String, port: Int): String {
        val options = config.properties.toMutableMap()
        if ("replicaSet" !in options && ',' !in config.host) options.putIfAbsent("directConnection", "true")
        val query = options.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val hosts = if (',' in config.host && config.ssh == null) config.host.split(',').joinToString(",") { it.trim() } else "$host:$port"
        return "mongodb://$hosts/" + if (query.isEmpty()) "" else "?$query"
    }

    private class Connection(
        override val config: ConnectionConfig,
        private val client: com.mongodb.client.MongoClient,
        private val defaultDatabase: String?,
    ) : DocumentConnection {
        private fun db(name: String): MongoDatabase = client.getDatabase(name)

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { withContextClassLoader(MongoClient::class.java.classLoader, block) }

        override suspend fun server(): String =
            io {
                val admin = db("admin")
                val build = admin.runCommand(org.bson.Document("buildInfo", 1))
                val version = build.getString("version")
                // FerretDB answers buildInfo with the MongoDB version it emulates, plus its own.
                (build["ferretdb"] as? org.bson.Document)?.getString("version")?.let { return@io "FerretDB ${it.removePrefix("v")} (MongoDB $version compatible)" }
                val hello = admin.runCommand(org.bson.Document("hello", 1))
                val topology =
                    when {
                        hello.getString("msg") == "isdbgrid" -> " (sharded)"
                        hello.getString("setName") != null -> " (replica set ${hello.getString("setName")})"
                        else -> ""
                    }
                "MongoDB $version$topology"
            }

        override suspend fun databases(): List<String> =
            io {
                // A user limited to one database may not list them; show the one the profile names.
                runCatching { client.listDatabaseNames().toList() }.getOrElse { if (defaultDatabase != null) listOf(defaultDatabase) else throw it }
                    .sortedWith(compareBy({ it in SystemDatabases }, { it }))
            }

        override suspend fun collections(database: String): List<CollectionInfo> =
            io {
                db(database).listCollections(BsonDocument::class.java).map { info ->
                    val name = info.getString("name").value
                    val kind = info.getString("type", BsonString("collection")).value
                    val count = if (kind == "collection") runCatching { db(database).getCollection(name).estimatedDocumentCount() }.getOrNull() else null
                    CollectionInfo(name, kind, count)
                }.toList().sortedBy { it.name }
            }

        override suspend fun find(database: String, collection: String, filter: String, sort: String, skip: Long, limit: Int, projection: String): DocumentPage =
            io {
                // Ties in the sort would let skip() hand out a document on two pages and none on another: break them by _id.
                val order = Shell.document(sort).apply { if (isNotEmpty() && "_id" !in this && "\$natural" !in this) append("_id", BsonInt32(1)) }
                val documents =
                    db(database).getCollection(collection, BsonDocument::class.java)
                        .find(Shell.document(filter))
                        .projection(Shell.document(projection))
                        .sort(order)
                        .skip(skip.toInt())
                        .limit(limit + 1)
                        .map { it.toJson(Json) }
                        .toList()
                DocumentPage(documents.take(limit), hasMore = documents.size > limit)
            }

        override suspend fun count(database: String, collection: String, filter: String): Long =
            io { db(database).getCollection(collection).countDocuments(Shell.document(filter)) }

        override suspend fun insert(database: String, collection: String, document: String): String =
            io {
                val parsed = Shell.document(document)
                val id = db(database).getCollection(collection, BsonDocument::class.java).insertOne(parsed).insertedId ?: parsed["_id"]
                idJson(id!!)
            }

        override suspend fun replace(database: String, collection: String, id: String, document: String, original: String?) =
            io {
                val key = Shell.value(id)
                val replacement = Shell.document(document)
                replacement["_id"]?.let { if (it != key) throw IllegalArgumentException(tr("A document's _id can't change; insert a new document instead.")) }
                val coll = db(database).getCollection(collection, BsonDocument::class.java)
                if (original == null) {
                    val result = coll.replaceOne(Filters.eq("_id", key), replacement)
                    if (result.matchedCount == 0L) throw IllegalStateException(tr("The document no longer exists."))
                    return@io
                }
                val before = Shell.document(original)
                val set = replacement.filter { (field, value) -> field != "_id" && before[field] != value }
                val unset = before.keys.filter { it != "_id" && it !in replacement }.toSet()
                if (set.isEmpty() && unset.isEmpty()) return@io
                val expected = (set.keys + unset).associateWith { before[it] }
                if (!updateFields(coll, key, set, unset, expected)) {
                    throw IllegalStateException(tr("The document was changed or deleted since it was loaded; nothing was saved. Reload it and try again."))
                }
            }

        override suspend fun update(database: String, collection: String, id: String, set: Map<String, String>, unset: Set<String>, expected: Map<String, String?>): Boolean =
            io {
                val coll = db(database).getCollection(collection, BsonDocument::class.java)
                updateFields(coll, Shell.value(id), set.mapValues { Shell.value(it.value) }, unset, expected.mapValues { (_, value) -> value?.let(Shell::value) })
            }

        override suspend fun sample(database: String, collection: String, size: Int): List<String> =
            io {
                val coll = db(database).getCollection(collection, BsonDocument::class.java)
                runCatching { coll.aggregate(listOf(BsonDocument("\$sample", BsonDocument("size", BsonInt32(size))))).map { it.toJson(Json) }.toList() }
                    .getOrElse { coll.find().limit(size).map { it.toJson(Json) }.toList() }
            }

        override suspend fun delete(database: String, collection: String, ids: List<String>): Long =
            io { db(database).getCollection(collection).deleteMany(Filters.`in`("_id", ids.map(Shell::value))).deletedCount }

        override suspend fun indexes(database: String, collection: String): List<String> =
            io { db(database).getCollection(collection, BsonDocument::class.java).listIndexes(BsonDocument::class.java).map { it.toJson(Json) }.toList() }

        override suspend fun command(database: String, line: String): String =
            io {
                try {
                    Shell.run(client, db(database), line)
                } catch (e: MongoCommandException) {
                    "Error: ${e.errorMessage} (${e.errorCodeName})"
                } catch (e: MongoException) {
                    "Error: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    "Error: ${e.message}"
                }
            }

        override fun close() = client.close()
    }

    internal companion object {
        val SystemDatabases = setOf("admin", "config", "local")

        /**
         * Relaxed Extended JSON, except that 64-bit integers keep their `$numberLong` wrapper: relaxed mode prints them
         * as plain numbers, which read back as 32-bit integers, so a document saved from the browser would lose the type.
         */
        val Json: JsonWriterSettings =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED)
                .int64Converter { value, writer ->
                    writer.writeStartObject()
                    writer.writeString("\$numberLong", value.toString())
                    writer.writeEndObject()
                }
                .build()
        val PrettyJson: JsonWriterSettings = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).indent(true).build()

        /**
         * Applies `$set` and `$unset` to the document with [key], only while each field of [expected] still holds its
         * value (null: missing). Matches by `$eq`, so a value that is itself a document of `$` keys isn't read as
         * operators, and arrays by size too, as `$eq` alone also matches an array holding the value as an element.
         */
        fun updateFields(
            coll: com.mongodb.client.MongoCollection<BsonDocument>,
            key: BsonValue,
            set: Map<String, BsonValue>,
            unset: Set<String>,
            expected: Map<String, BsonValue?>,
        ): Boolean {
            (set.keys + unset).forEach { field ->
                require(field.isNotEmpty() && '.' !in field && !field.startsWith('$')) { tr("Field “{0}” can't be edited here: names with dots or a leading $ need the console.", field) }
                require(field != "_id") { tr("A document's _id can't change; insert a new document instead.") }
            }
            val filter = BsonDocument("_id", key)
            expected.forEach { (field, value) ->
                if (field == "_id") return@forEach
                filter[field] =
                    when {
                        value == null -> BsonDocument("\$exists", BsonBoolean.FALSE)
                        value.isNull -> BsonDocument("\$type", BsonString("null"))
                        value.isArray -> BsonDocument("\$eq", value).append("\$size", BsonInt32(value.asArray().size))
                        else -> BsonDocument("\$eq", value)
                    }
            }
            val update = BsonDocument()
            if (set.isNotEmpty()) update["\$set"] = BsonDocument().apply { set.forEach { (field, value) -> append(field, value) } }
            if (unset.isNotEmpty()) update["\$unset"] = BsonDocument().apply { unset.forEach { append(it, BsonString("")) } }
            if (update.isEmpty()) return coll.countDocuments(filter) > 0
            return coll.updateOne(filter, update).matchedCount > 0
        }

        /** An `_id` as Extended JSON, the form the browser passes back to replace or delete it. */
        fun idJson(id: BsonValue): String = BsonDocument("_id", id).toJson(Json).let { it.substringAfter(':').removeSuffix("}").trim() }
    }
}

/**
 * The subset of the mongo shell a console needs, without JavaScript: `show dbs|collections`, `db.runCommand(…)`,
 * `db.<collection>.<method>(…)` with `.sort/.limit/.skip/.projection` chained after `find`, and `db.getCollection('…')`
 * for names that aren't identifiers. Arguments are shell-style Extended JSON.
 */
internal object Shell {
    /** Documents shown by `find` without a `.limit()`, like the shell's first page. */
    const val DefaultLimit = 50

    fun document(text: String): BsonDocument = text.trim().ifEmpty { "{}" }.let { BsonDocument.parse(it) }

    /** Any shell value: a document, array, string, number or `ObjectId('…')`. */
    fun value(text: String): BsonValue = BsonDocument.parse("{\"v\": ${text.trim()}}")["v"]!!

    private data class Call(val name: String, val arguments: List<String>)

    fun run(client: com.mongodb.client.MongoClient, db: MongoDatabase, line: String): String {
        val text = line.trim().removeSuffix(";").trim()
        Regex("""^show\s+(\w+)$""", RegexOption.IGNORE_CASE).matchEntire(text)?.let { match ->
            return when (match.groupValues[1].lowercase()) {
                "dbs", "databases" -> client.listDatabaseNames().joinToString("\n")
                "collections", "tables" -> db.listCollectionNames().sorted().joinToString("\n")
                else -> throw IllegalArgumentException(tr("Unknown: show {0}", match.groupValues[1]))
            }
        }
        require(text.startsWith("db.") || text.startsWith("db ")) { tr("Commands start with db. or show; this console runs no JavaScript.") }
        var rest = text.removePrefix("db").trimStart().removePrefix(".").trimStart()
        // db.getCollection('name').method(…)
        val collection: String?
        if (rest.startsWith("getCollection")) {
            val (call, after) = parseCall(rest)
            collection = (value(call.arguments.single()) as? BsonString)?.value ?: throw IllegalArgumentException(tr("getCollection takes a name"))
            rest = after.trimStart().removePrefix(".").trimStart()
        } else {
            val name = Regex("""^[A-Za-z_$][\w$]*""").find(rest)?.value ?: throw IllegalArgumentException(tr("Expected a collection or method after db."))
            val after = rest.substring(name.length).trimStart()
            if (after.startsWith("(")) {
                collection = null
            } else {
                collection = name
                rest = after.removePrefix(".").trimStart()
            }
        }
        val calls = mutableListOf<Call>()
        while (rest.isNotBlank()) {
            val (call, after) = parseCall(rest)
            calls += call
            rest = after.trimStart().removePrefix(".").trimStart()
        }
        require(calls.isNotEmpty()) { tr("Expected a method call") }
        return if (collection == null) database(client, db, calls.single()) else collection(db, collection, calls)
    }

    private fun database(client: com.mongodb.client.MongoClient, db: MongoDatabase, call: Call): String {
        val args = call.arguments
        return when (call.name) {
            "runCommand" -> pretty(db.runCommand(document(args.single()), BsonDocument::class.java))
            "adminCommand" -> pretty(client.getDatabase("admin").runCommand(document(args.single()), BsonDocument::class.java))
            "getCollectionNames" -> db.listCollectionNames().sorted().joinToString("\n")
            "stats" -> pretty(db.runCommand(BsonDocument("dbStats", BsonInt32(1)), BsonDocument::class.java))
            "createCollection" -> {
                db.createCollection(string(args.first()))
                "{ ok: 1 }"
            }
            "dropDatabase" -> pretty(db.runCommand(BsonDocument("dropDatabase", BsonInt32(1)), BsonDocument::class.java))
            else -> throw IllegalArgumentException(tr("db.{0}() isn't supported here; db.runCommand({...}) runs any command.", call.name))
        }
    }

    private fun collection(db: MongoDatabase, name: String, calls: List<Call>): String {
        val coll = db.getCollection(name, BsonDocument::class.java)
        val first = calls.first()
        val args = first.arguments
        fun arg(i: Int) = args.getOrNull(i)?.let(::document) ?: BsonDocument()
        return when (first.name) {
            "find" -> {
                var cursor = coll.find(arg(0))
                args.getOrNull(1)?.let { cursor = cursor.projection(document(it)) }
                var limit = DefaultLimit
                var limited = false
                calls.drop(1).forEach { modifier ->
                    val a = modifier.arguments.singleOrNull() ?: throw IllegalArgumentException(tr(".{0}() takes one argument", modifier.name))
                    when (modifier.name) {
                        "sort" -> cursor = cursor.sort(document(a))
                        "projection" -> cursor = cursor.projection(document(a))
                        "skip" -> cursor = cursor.skip(number(a))
                        "limit" -> {
                            limit = number(a)
                            limited = true
                        }
                        else -> throw IllegalArgumentException(tr(".{0}() isn't supported after find()", modifier.name))
                    }
                }
                val documents = cursor.limit(if (limited) limit else limit + 1).toList()
                val shown = documents.take(limit)
                shown.joinToString("\n", transform = ::pretty).ifEmpty { "(no documents)" } +
                    if (!limited && documents.size > limit) "\n… more documents; add .limit(n) or .skip($limit)" else ""
            }
            "findOne" -> coll.find(arg(0)).let { c -> args.getOrNull(1)?.let { c.projection(document(it)) } ?: c }.first()?.let(::pretty) ?: "null"
            "countDocuments", "count" -> coll.countDocuments(arg(0)).toString()
            "estimatedDocumentCount" -> coll.estimatedDocumentCount().toString()
            "distinct" -> coll.distinct(string(args.first()), arg(1), BsonValue::class.java).toList().let { BsonArray(it) }.let(::prettyValue)
            "aggregate" -> {
                val pipeline = (value(args.single()) as? BsonArray ?: throw IllegalArgumentException(tr("aggregate takes an array of stages"))).map { it.asDocument() }
                coll.aggregate(pipeline).toList().joinToString("\n", transform = ::pretty).ifEmpty { "(no documents)" }
            }
            "insertOne" -> "{ acknowledged: true, insertedId: ${coll.insertOne(arg(0)).insertedId?.let(MongoClient::idJson)} }"
            "insertMany" -> {
                val documents = (value(args.single()) as? BsonArray ?: throw IllegalArgumentException(tr("insertMany takes an array"))).map { it.asDocument() }
                "{ acknowledged: true, insertedCount: ${coll.insertMany(documents).insertedIds.size} }"
            }
            "updateOne" -> coll.updateOne(arg(0), arg(1)).let { "{ matchedCount: ${it.matchedCount}, modifiedCount: ${it.modifiedCount} }" }
            "updateMany" -> coll.updateMany(arg(0), arg(1)).let { "{ matchedCount: ${it.matchedCount}, modifiedCount: ${it.modifiedCount} }" }
            "replaceOne" -> coll.replaceOne(arg(0), arg(1)).let { "{ matchedCount: ${it.matchedCount}, modifiedCount: ${it.modifiedCount} }" }
            "deleteOne" -> "{ deletedCount: ${coll.deleteOne(arg(0)).deletedCount} }"
            "deleteMany" -> "{ deletedCount: ${coll.deleteMany(arg(0)).deletedCount} }"
            "getIndexes" -> coll.listIndexes(BsonDocument::class.java).toList().joinToString("\n", transform = ::pretty)
            "createIndex" -> {
                val options = IndexOptions()
                args.getOrNull(1)?.let(::document)?.let { o ->
                    o["name"]?.let { options.name(it.asString().value) }
                    o["unique"]?.let { options.unique(it.asBoolean().value) }
                }
                coll.createIndex(arg(0), options)
            }
            "dropIndex" -> {
                coll.dropIndex(string(args.single()))
                "{ ok: 1 }"
            }
            "drop" -> {
                coll.drop()
                "true"
            }
            "stats" -> pretty(db.runCommand(BsonDocument("collStats", BsonString(name)), BsonDocument::class.java))
            else -> throw IllegalArgumentException(tr("db.{0}.{1}() isn't supported here.", name, first.name))
        }
    }

    private fun string(text: String): String = (value(text) as? BsonString)?.value ?: throw IllegalArgumentException(tr("Expected a string, got {0}", text))

    private fun number(text: String): Int = value(text).let { if (it.isNumber) it.asNumber().intValue() else throw IllegalArgumentException(tr("Expected a number, got {0}", text)) }

    private fun pretty(document: BsonDocument): String = document.toJson(MongoClient.PrettyJson)

    private fun prettyValue(value: BsonValue): String = BsonDocument("v", value).toJson(MongoClient.PrettyJson).let { json ->
        json.substringAfter(':').trimEnd().removeSuffix("}").trim()
    }

    /** `name(arg, arg)rest` → the call and what follows it. Commas and parentheses inside strings and brackets don't count. */
    private fun parseCall(text: String): Pair<Call, String> {
        val name = Regex("""^[A-Za-z_$][\w$]*""").find(text)?.value ?: throw IllegalArgumentException(tr("Expected a method at: {0}", text.take(30)))
        var i = name.length
        while (i < text.length && text[i].isWhitespace()) i++
        require(i < text.length && text[i] == '(') { tr("Expected ( after {0}", name) }
        i++
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                current.append(c)
                if (c == '\\' && i + 1 < text.length) current.append(text[++i])
                else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> {
                    quote = c
                    current.append(c)
                }
                '{', '[', '(' -> {
                    depth++
                    current.append(c)
                }
                '}', ']' -> {
                    depth--
                    current.append(c)
                }
                ')' ->
                    if (depth == 0) {
                        if (current.isNotBlank()) arguments += current.toString().trim()
                        return Call(name, arguments) to text.substring(i + 1)
                    } else {
                        depth--
                        current.append(c)
                    }
                ',' ->
                    if (depth == 0) {
                        arguments += current.toString().trim()
                        current.clear()
                    } else current.append(c)
                else -> current.append(c)
            }
            i++
        }
        throw IllegalArgumentException(tr("Unbalanced parentheses in {0}(…)", name))
    }
}
