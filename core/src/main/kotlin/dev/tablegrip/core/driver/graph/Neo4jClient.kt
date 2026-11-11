package dev.tablegrip.core.driver.graph

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.QueryResult
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.ResultColumn
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.http.RestClient
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.sql.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Neo4j through its HTTP API (port 7474), which TableGrip speaks itself; Cypher in both the browser and the console. The
 * database field picks the database (`neo4j` when blank). Node labels (`:Person`) and relationship types (`[:KNOWS]`) are
 * the containers; each record is a node's or relationship's properties, with its element id, labels or type and ends as
 * meta.
 *
 * A change or delete runs in one transaction that first takes the element's write lock, then checks its properties are
 * still the ones read: a stale edit is refused, never written. Only the properties that changed are set, so the others
 * keep their types (JSON has no dates; a date property shows as text and stays a date unless edited).
 */
class Neo4jClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val rest = RestClient("Neo4j", config, host, port)
        if (config.user.isNotBlank()) rest.authorization = RestClient.basic(config.user.trim(), password)
        return Connection(config, rest).also { it.version() }
    }

    internal class Connection(override val config: ConnectionConfig, private val rest: RestClient) : RecordConnection {
        private val database = config.database.trim().ifEmpty { "neo4j" }
        private val tx = "/db/${URLEncoder.encode(database, Charsets.UTF_8)}/tx"

        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

        /** One statement's columns and rows as the HTTP API returns them: each row's values and each value's meta. */
        internal class Result(val columns: List<String>, val rows: List<JsonArray>, val meta: List<JsonArray>)

        /**
         * Runs [statements] in one transaction ([path] is the auto-commit endpoint, or an open transaction's), failing with
         * Neo4j's own error. A read-only profile's transactions are read-only on the server too.
         */
        private fun run(path: String, vararg statements: Pair<String, JsonObject>): Pair<List<Result>, JsonObject> {
            val body = buildJsonObject {
                put("statements", buildJsonArray {
                    statements.forEach { (cypher, parameters) -> add(buildJsonObject { put("statement", cypher); put("parameters", parameters) }) }
                })
            }
            val headers = if (config.readOnly) mapOf("access-mode" to "READ") else emptyMap()
            val response = rest.send("POST", path, body.toString(), headers = headers)
            if (response.status == 401) throw IOException(tr("{0} refused the credentials: {1}", "Neo4j", response.body.take(200)))
            val json = runCatching { response.json().jsonObject }.getOrNull()
                ?: throw IOException(tr("{0} doesn't look like Neo4j's HTTP API: {1}", rest.base, response.body.take(120)))
            json["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.let { error ->
                throw IOException("Neo4j: " + (error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()) + (error["code"]?.jsonPrimitive?.contentOrNull?.let { " ($it)" } ?: ""))
            }
            if (!response.ok) throw IOException("Neo4j: HTTP ${response.status}")
            val results = json["results"]?.jsonArray.orEmpty().map { it.jsonObject }.map { result ->
                val data = result["data"]?.jsonArray.orEmpty().map { it.jsonObject }
                Result(
                    result["columns"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                    data.map { it["row"]?.jsonArray ?: JsonArray(emptyList()) },
                    data.map { (it["meta"] as? JsonArray) ?: JsonArray(emptyList()) },
                )
            }
            return results to json
        }

        private fun query(cypher: String, parameters: JsonObject = JsonObject(emptyMap())): Result = run("$tx/commit", cypher to parameters).first.single()

        internal fun version(): String {
            val result = query("CALL dbms.components() YIELD name, versions, edition RETURN name, versions[0], edition")
            val row = result.rows.firstOrNull() ?: throw IOException(tr("{0} doesn't look like Neo4j's HTTP API: {1}", rest.base, "dbms.components"))
            return "${row[0].jsonPrimitive.content} ${row[1].jsonPrimitive.content} ${row[2].jsonPrimitive.content} ($database)"
        }

        override suspend fun server(): String = io { version() }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isNotEmpty()) return@io emptyList()
                val labels = query("CALL db.labels() YIELD label RETURN label ORDER BY label").rows.map { it[0].jsonPrimitive.content }
                val types = query("CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType ORDER BY relationshipType").rows.map { it[0].jsonPrimitive.content }
                // Counts by label and type come from Neo4j's count store, so they cost nothing.
                labels.map { label -> RecordContainer(listOf(":$label"), "label", query("MATCH (n:${escape(label)}) RETURN count(n)").rows.single()[0].jsonPrimitive.longOrNull) } +
                    types.map { type -> RecordContainer(listOf("[:$type]"), "relationship", query("MATCH ()-[r:${escape(type)}]->() RETURN count(r)").rows.single()[0].jsonPrimitive.longOrNull) }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val target = Target.of(container)
                val keys = query(
                    if (target.relationship) "MATCH ()-[r:${escape(target.name)}]->() WITH r LIMIT 500 UNWIND keys(r) AS key RETURN DISTINCT key ORDER BY key"
                    else "MATCH (n:${escape(target.name)}) WITH n LIMIT 500 UNWIND keys(n) AS key RETURN DISTINCT key ORDER BY key",
                ).rows.map { it[0] }
                fun schema(kind: String) =
                    runCatching {
                        val result = query("SHOW $kind YIELD * WHERE \$name IN labelsOrTypes RETURN *", buildJsonObject { put("name", target.name) })
                        JsonArray(result.rows.map { row -> JsonObject(result.columns.zip(row).toMap()) })
                    }.getOrDefault(JsonArray(emptyList()))
                JsonTables.pretty(buildJsonObject {
                    put(if (target.relationship) "type" else "label", target.name)
                    put("propertyKeys", JsonArray(keys))
                    put("indexes", schema("INDEXES"))
                    put("constraints", schema("CONSTRAINTS"))
                })
            }

        override val queryLanguage =
            RecordLanguage(
                tr("WHERE"),
                tr("n.name STARTS WITH 'A' AND n.born > 1960   (n is the node, r the relationship)"),
                tr("A Cypher WHERE condition on n (a node of the label) or r (a relationship of the type, between a and b)."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val target = Target.of(container)
                val skip = cursor?.toLongOrNull() ?: 0
                val where = query.trim().removeSuffix(";").trim().let { if (it.isEmpty()) "" else if (it.startsWith("WHERE ", ignoreCase = true)) " $it" else " WHERE $it" }
                val parameters = buildJsonObject { put("skip", skip); put("limit", limit + 1) }
                val records =
                    if (target.relationship) {
                        query(
                            "MATCH (a)-[r:${escape(target.name)}]->(b)$where RETURN elementId(r), properties(r), elementId(a), elementId(b) ORDER BY elementId(r) SKIP \$skip LIMIT \$limit",
                            parameters,
                        ).rows.map { row ->
                            val properties = row[1].jsonObject
                            Record(
                                key(row[0], properties),
                                properties,
                                buildJsonObject { put("elementId", row[0]); put("start", row[2]); put("end", row[3]) },
                            )
                        }
                    } else {
                        query("MATCH (n:${escape(target.name)})$where RETURN elementId(n), properties(n), labels(n) ORDER BY elementId(n) SKIP \$skip LIMIT \$limit", parameters).rows.map { row ->
                            val properties = row[1].jsonObject
                            Record(key(row[0], properties), properties, buildJsonObject { put("elementId", row[0]); put("labels", row[2]) })
                        }
                    }
                val total = if (where.isEmpty()) {
                    query(if (target.relationship) "MATCH ()-[r:${escape(target.name)}]->() RETURN count(r)" else "MATCH (n:${escape(target.name)}) RETURN count(n)").rows.single()[0].jsonPrimitive.longOrNull
                } else null
                RecordPage(records.take(limit), (skip + limit).takeIf { records.size > limit }?.toString(), total)
            }

        private fun key(elementId: JsonElement, properties: JsonObject) = buildJsonObject { put("elementId", elementId); put(VersionField, version(properties)) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val target = Target.of(container)
                if (key == null) {
                    if (target.relationship) throw IOException(tr("Create relationships in the console, between the nodes they join: MATCH (a …), (b …) CREATE (a)-[:{0}]->(b).", target.name))
                    val row = query("CREATE (n:${escape(target.name)}) SET n = \$properties RETURN elementId(n), properties(n)", buildJsonObject { put("properties", document) }).rows.single()
                    return@io key(row[0], row[1].jsonObject)
                }
                locked(target, key) { path, current ->
                    // Only what changed is written, so untouched properties keep their Neo4j types.
                    val set = JsonObject(document.filter { (name, value) -> current[name] != value && value !is JsonNull })
                    val removed = current.keys.filter { it !in document || document[it] is JsonNull }
                    val variable = if (target.relationship) "r" else "n"
                    val match = if (target.relationship) "MATCH ()-[r]->() WHERE elementId(r) = \$id" else "MATCH (n) WHERE elementId(n) = \$id"
                    val removal = removed.joinToString("") { " REMOVE $variable.${escape(it)}" }
                    val result = run(
                        "$path/commit",
                        "$match SET $variable += \$set$removal RETURN properties($variable)" to buildJsonObject { put("id", key["elementId"]!!); put("set", set) },
                    ).first.single()
                    key(key["elementId"]!!, result.rows.single()[0].jsonObject)
                }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val target = Target.of(container)
                keys.count { key ->
                    runCatching {
                        locked(target, key) { path, _ ->
                            val cypher = if (target.relationship) "MATCH ()-[r]->() WHERE elementId(r) = \$id DELETE r" else "MATCH (n) WHERE elementId(n) = \$id DETACH DELETE n"
                            run("$path/commit", cypher to buildJsonObject { put("id", key["elementId"]!!) })
                            true
                        }
                    }.getOrElse { if (it is Gone) false else throw it }
                }.toLong()
            }

        /** The element no longer exists. */
        private class Gone : IOException()

        /**
         * Opens a transaction, takes [key]'s element's write lock (a property set and removed again, which leaves no
         * trace), checks it is as it was read, then hands the open transaction to [write], which commits it.
         */
        private fun <T> locked(target: Target, key: JsonObject, write: (path: String, current: JsonObject) -> T): T {
            val begin = rest.send("POST", tx, """{"statements": []}""")
            if (!begin.ok) throw IOException("Neo4j: HTTP ${begin.status}")
            val commit = begin.json().jsonObject["commit"]?.jsonPrimitive?.contentOrNull ?: throw IOException(tr("Neo4j didn't open a transaction."))
            // The commit URL is absolute; requests go to its path, under the profile's own base (and any path prefix).
            val uriPath = java.net.URI(commit).path.removeSuffix("/commit")
            val prefix = java.net.URI(rest.base).path
            val path = if (prefix.isNotEmpty() && uriPath.startsWith(prefix)) uriPath.removePrefix(prefix) else uriPath
            try {
                val variable = if (target.relationship) "r" else "n"
                val match = if (target.relationship) "MATCH ()-[r]->() WHERE elementId(r) = \$id" else "MATCH (n) WHERE elementId(n) = \$id"
                val rows = run(path, "$match SET $variable.$LockProperty = true REMOVE $variable.$LockProperty RETURN properties($variable)" to buildJsonObject { put("id", key["elementId"]!!) }).first.single().rows
                val current = rows.singleOrNull()?.get(0)?.jsonObject ?: throw Gone()
                if (key[VersionField] != null && version(current) != key[VersionField]!!.jsonPrimitive.content) {
                    throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", key["elementId"]!!.jsonPrimitive.content))
                }
                return write(path, current)
            } catch (e: Throwable) {
                runCatching { rest.send("DELETE", path) }
                throw e
            }
        }

        override val consoleLanguage =
            RecordLanguage(
                tr("Cypher"),
                "MATCH (p:Person)-[:KNOWS]->(friend)\nRETURN p.name, collect(friend.name) AS friends\nLIMIT 25",
                tr("Cypher statements, separated by semicolons, each run in a transaction of its own; the last one's rows show below. Nodes and relationships show as their properties."),
            )

        override fun sampleStatement(container: List<String>): String =
            container.firstOrNull()?.let {
                val target = Target.of(container)
                if (target.relationship) "MATCH (a)-[r:${escape(target.name)}]->(b)\nRETURN a, r, b\nLIMIT 25" else "MATCH (n:${escape(target.name)})\nRETURN n\nLIMIT 25"
            } ?: "MATCH (n)\nRETURN labels(n) AS labels, count(*) AS nodes\nORDER BY nodes DESC"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val statements = splitStatements(statement)
                if (statements.isEmpty()) throw IOException(tr("Nothing to run"))
                val started = System.currentTimeMillis()
                // Each statement in a transaction of its own, as Neo4j Browser runs them: schema changes and data can't share one.
                val (results, json) = statements.map { run("$tx/commit", it to JsonObject(emptyMap())) }.last()
                val elapsed = System.currentTimeMillis() - started
                val last = results.lastOrNull()
                if (last == null || last.columns.isEmpty()) {
                    val stats = json["results"]?.jsonArray?.lastOrNull()?.jsonObject?.get("stats")
                    return@io ConsoleReply.Text(stats?.let(JsonTables::pretty) ?: tr("Done."), elapsed)
                }
                val rows = last.rows.take(MaxConsoleRows).map { row -> row.map(::cell) }
                val types = last.columns.indices.map { i -> JsonTables.typeLabel(last.rows.mapNotNull { it.getOrNull(i) }) }
                ConsoleReply.Table(
                    QueryResult.Rows(last.columns.mapIndexed { i, name -> ResultColumn(name, types[i], if (types[i] == "number") Types.NUMERIC else Types.VARCHAR) }, rows, truncated = last.rows.size > MaxConsoleRows, elapsedMillis = elapsed),
                    note = if (last.rows.size > MaxConsoleRows) tr("First {0} rows", MaxConsoleRows) else null,
                )
            }

        private fun cell(value: JsonElement): String? = JsonTables.cell(value)

        override fun isRead(statement: String): Boolean = isReadCypher(statement)

        override fun close() = Unit
    }

    /** A container's label or relationship type, from its path: `[":Person"]` or `["[:KNOWS]"]`. */
    internal data class Target(val name: String, val relationship: Boolean) {
        companion object {
            fun of(container: List<String>): Target {
                val text = container.singleOrNull() ?: throw IOException(tr("Pick a label or a relationship type."))
                return when {
                    text.startsWith("[:") && text.endsWith("]") -> Target(text.substring(2, text.length - 1), relationship = true)
                    text.startsWith(":") -> Target(text.substring(1), relationship = false)
                    else -> throw IOException(tr("Pick a label or a relationship type."))
                }
            }
        }
    }

    companion object {
        internal const val VersionField = "_version"
        private const val MaxConsoleRows = 5_000

        /** Set and removed again in one statement to take an element's write lock. */
        private const val LockProperty = "`__tablegrip_lock`"

        /** A label, type or property name as a Cypher identifier. */
        internal fun escape(name: String): String = "`" + name.replace("`", "``") + "`"

        internal fun version(properties: JsonObject): String =
            MessageDigest.getInstance("SHA-256").digest(JsonObject(properties.toSortedMap()).toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        private val WriteClauses = Regex("\\b(CREATE|MERGE|SET|DELETE|REMOVE|DROP|DETACH|FOREACH|LOAD\\s+CSV|ALTER|GRANT|DENY|REVOKE|START|STOP|TERMINATE)\\b", RegexOption.IGNORE_CASE)
        private val ReadProcedures = Regex("\\bCALL\\s+(db\\.(labels|relationshipTypes|propertyKeys|indexes|constraints|schema\\.[a-zA-Z]+|info|ping)|dbms\\.(components|procedures|functions|showCurrentUser|info))\\s*\\(", RegexOption.IGNORE_CASE)

        /**
         * Whether Cypher only reads: no clause that writes, and no procedure call other than the catalog's read-only
         * ones. Strings and comments are left out of the check, so a property value mentioning CREATE doesn't count.
         */
        internal fun isReadCypher(statement: String): Boolean {
            val code = withoutLiterals(statement)
            if (code.isBlank() || WriteClauses.containsMatchIn(code)) return false
            val calls = Regex("\\bCALL\\b", RegexOption.IGNORE_CASE).findAll(code).count()
            return calls == ReadProcedures.findAll(code).count()
        }

        /** [text] with string literals, quoted names and comments blanked out. */
        private fun withoutLiterals(text: String): String =
            text.replace(Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"|`[^`]*`"), " ")

        /** Splits Cypher on semicolons outside strings and comments. */
        internal fun splitStatements(text: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            var quote: Char? = null
            while (i < text.length) {
                val c = text[i]
                when {
                    quote != null -> {
                        current.append(c)
                        if (c == '\\' && i + 1 < text.length) current.append(text[++i])
                        else if (c == quote) quote = null
                    }
                    c == '\'' || c == '"' || c == '`' -> {
                        quote = c
                        current.append(c)
                    }
                    c == '/' && text.getOrNull(i + 1) == '/' -> {
                        while (i < text.length && text[i] != '\n') i++
                        continue
                    }
                    c == ';' -> {
                        current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
            return statements
        }
    }
}
