package dev.tablegrip.engines.cassandra

import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata
import com.datastax.oss.driver.api.core.metadata.schema.RelationMetadata
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata
import com.datastax.oss.driver.api.core.type.DataTypes
import com.datastax.oss.driver.internal.core.loadbalancing.DcInferringLoadBalancingPolicy
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
import dev.tablegrip.core.driver.withContextClassLoader
import dev.tablegrip.core.i18n.tr
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.sql.SQLException
import java.sql.Types
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TableGrip's Cassandra adapter over the Apache Cassandra Java driver, for Apache Cassandra and ScyllaDB. Hosts may list
 * several contact points (`node1,node2:9043`); the data center is inferred from them unless the `localDatacenter`
 * property names it; `ssl=true` (and `verifyPeer=false` for self-signed certificates) turns on TLS. The database field
 * picks a keyspace to open first.
 *
 * Rows travel as Cassandra's own JSON (`SELECT JSON`, `INSERT … JSON`), so every CQL type round-trips exactly, collections
 * and user types included. A row's key is its primary key plus a hash of the row as it was read: a change or delete
 * re-reads the row first and refuses when it changed since, so an edit made from a stale page never overwrites a newer
 * one. Cassandra has no transactions, so that check and the write are two steps, not one.
 */
class CassandraClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection =
        withContextClassLoader(CassandraClient::class.java.classLoader) { open(config, host, port, password) }

    private fun open(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection {
        val loader = CassandraClient::class.java.classLoader
        val options =
            DriverConfigLoader.programmaticBuilder(loader)
                .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, Duration.ofSeconds(10))
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(30))
                .withDuration(DefaultDriverOption.METADATA_SCHEMA_REQUEST_TIMEOUT, Duration.ofSeconds(30))
                .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT, Duration.ofSeconds(10))
                // Requests block on reads of the schema, so it's kept current from the start.
                .withBoolean(DefaultDriverOption.METADATA_SCHEMA_ENABLED, true)
                // The native clock needs JNR, which isn't downloaded.
                .withBoolean(DefaultDriverOption.TIMESTAMP_GENERATOR_FORCE_JAVA_CLOCK, true)
                .apply {
                    if (config.properties["localDatacenter"].isNullOrBlank()) {
                        withClass(DefaultDriverOption.LOAD_BALANCING_POLICY_CLASS, DcInferringLoadBalancingPolicy::class.java)
                    }
                }
                .build()
        val builder = CqlSession.builder().withClassLoader(loader).withConfigLoader(options)
        contactPoints(config.host, host, port).forEach { builder.addContactPoint(it) }
        config.properties["localDatacenter"]?.takeIf { it.isNotBlank() }?.let { builder.withLocalDatacenter(it.trim()) }
        val user = config.user.trim()
        if (user.isNotEmpty()) builder.withAuthCredentials(user, password.orEmpty())
        if (config.properties["ssl"]?.toBoolean() == true) {
            builder.withSslContext(if (config.properties["verifyPeer"]?.toBoolean() == false) trustingEverything() else SSLContext.getDefault())
        }
        config.database.trim().takeIf { it.isNotEmpty() }?.let { builder.withKeyspace(CqlIdentifier.fromInternal(it)) }
        val session =
            try {
                builder.build()
            } catch (e: Exception) {
                throw SQLException(tr("Couldn't connect to {0}: {1}", config.type.displayName, e.message ?: e.javaClass.simpleName), e)
            }
        return Connection(config, session)
    }

    internal class Connection(override val config: ConnectionConfig, private val session: CqlSession) : RecordConnection {
        private val showSystem = config.properties["showSystem"]?.toBoolean() == true

        /** Runs [block] off the UI thread, with the driver's classes found through the context class loader. */
        private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { withContextClassLoader(CassandraClient::class.java.classLoader, block) }

        override suspend fun server(): String =
            io {
                // ScyllaDB answers with Cassandra's protocol version in release_version, and its own in system.versions.
                val scylla = runCatching { session.execute("SELECT version FROM system.versions").one()?.getString("version") }.getOrNull()
                if (scylla != null) "ScyllaDB $scylla"
                else "Apache Cassandra " + (session.execute("SELECT release_version FROM system.local").one()?.getString("release_version") ?: "?")
            }

        private fun keyspaces(): Map<CqlIdentifier, KeyspaceMetadata> = session.metadata.keyspaces

        private fun keyspace(name: String): KeyspaceMetadata =
            keyspaces()[CqlIdentifier.fromInternal(name)] ?: throw SQLException(tr("There's no keyspace {0}.", name))

        /** A table or materialized view. */
        private fun relation(container: List<String>): RelationMetadata {
            if (container.size != 2) throw SQLException(tr("Pick a table."))
            val keyspace = keyspace(container[0])
            val name = CqlIdentifier.fromInternal(container[1])
            return keyspace.getTable(name).orElse(null) ?: keyspace.getView(name).orElse(null)
                ?: throw SQLException(tr("There's no table {0}.", container.joinToString(".")))
        }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                when (parent.size) {
                    0 -> keyspaces().values.map { it.name.asInternal() }.filter { showSystem || !isSystem(it) }.sorted().map { name ->
                        val replication = keyspace(name).replication["class"]?.substringAfterLast('.')
                        RecordContainer(listOf(name), "keyspace", detail = replication, browsable = false, hasChildren = true)
                    }
                    1 -> {
                        val keyspace = keyspace(parent[0])
                        val tables = keyspace.tables.values.map { table ->
                            RecordContainer(parent + table.name.asInternal(), "table", detail = primaryKeyText(table))
                        }
                        val views = keyspace.views.values.map { view ->
                            RecordContainer(parent + view.name.asInternal(), "view", detail = primaryKeyText(view))
                        }
                        (tables + views).sortedBy { it.name }
                    }
                    else -> emptyList()
                }
            }

        override suspend fun definition(container: List<String>): String =
            io { if (container.size == 1) keyspace(container[0]).describe(true) else relation(container).describe(true) }

        override val queryLanguage =
            RecordLanguage(
                tr("WHERE"),
                tr("user_id = 42 AND day > '2024-01-01'   (ALLOW FILTERING to scan), or a whole SELECT"),
                tr("A CQL WHERE clause, optionally with ORDER BY, LIMIT or ALLOW FILTERING; or a whole SELECT statement. Restrict the partition key to read one partition."),
            )

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage {
            val relation = io { relation(container) }
            val cql = selectJson(relation, query)
            val statement = SimpleStatement.newInstance(cql).setPageSize(limit).let { s ->
                cursor?.let { s.setPagingState(ByteBuffer.wrap(Base64.getUrlDecoder().decode(it))) } ?: s
            }
            val result: AsyncResultSet = withContext(Dispatchers.IO) { session.executeAsync(statement).toCompletableFuture() }.await()
            val keyColumns = keyNames(relation)
            val records = result.currentPage().map { row -> record(row.getString(0) ?: "{}", keyColumns) }
            val next = if (result.hasMorePages()) result.executionInfo.pagingState?.let { Base64.getUrlEncoder().encodeToString(bytes(it)) } else null
            return RecordPage(records, next)
        }

        private fun record(json: String, keyColumns: List<String>): Record {
            val document = Json.parseToJsonElement(json).jsonObject
            val key =
                if (keyColumns.all { it in document }) JsonObject(keyColumns.associateWith { document.getValue(it) } + (VersionField to JsonPrimitive(version(document))))
                else null
            return Record(key, document)
        }

        override fun canWrite(container: List<String>): Boolean =
            runCatching {
                val relation = withContextClassLoader(CassandraClient::class.java.classLoader) { relation(container) }
                relation is TableMetadata && !isSystem(container[0]) && relation.columns.values.none { it.type == DataTypes.COUNTER }
            }.getOrDefault(false)

        override suspend fun template(container: List<String>): JsonObject =
            io { JsonObject(relation(container).columns.values.associate { it.name.asCql(true) to JsonNull }) }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val table = relation(container) as? TableMetadata ?: throw SQLException(tr("Views are read-only."))
                val keyColumns = keyNames(table)
                val name = qualified(table)
                if (key == null) {
                    keyColumns.firstOrNull { document[it] == null || document[it] is JsonNull }?.let { throw SQLException(tr("A new row needs its {0}.", it)) }
                    // Columns left null aren't written at all, so a new row leaves no tombstones. Each value goes through
                    // fromJson(), not INSERT … JSON, whose IF NOT EXISTS ScyllaDB ignores.
                    val values = document.filterValues { it !is JsonNull }
                    val insert = "INSERT INTO $name (${values.keys.joinToString()}) VALUES (${values.keys.joinToString { "fromJson(?)" }}) IF NOT EXISTS"
                    val applied = session.execute(SimpleStatement.newInstance(insert, *values.values.map { it.toString() }.toTypedArray())).wasApplied()
                    if (!applied) throw SQLException(tr("A row with this key already exists."))
                } else {
                    keyColumns.firstOrNull { document[it] != key[it] }?.let { throw SQLException(tr("A row's {0} can't change; add a new row instead.", it)) }
                    checkUnchanged(table, key)
                    // Columns taken out of the document are set to null, as a replace should.
                    session.execute(SimpleStatement.newInstance("INSERT INTO $name JSON ? DEFAULT NULL", document.toString()))
                }
                val written = session.execute(SimpleStatement.newInstance("SELECT JSON * FROM $name WHERE ${where(table, document)}")).one()?.getString(0)
                written?.let { record(it, keyColumns).key } ?: JsonObject(keyColumns.associateWith { document.getValue(it) })
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                val table = relation(container) as? TableMetadata ?: throw SQLException(tr("Views are read-only."))
                keys.count { key ->
                    val current = current(table, key) ?: return@count false
                    if (key[VersionField] != null && version(current) != key[VersionField]!!.jsonPrimitive.content) throw changed(table, key)
                    session.execute(SimpleStatement.newInstance("DELETE FROM ${qualified(table)} WHERE ${where(table, key)}"))
                    true
                }.toLong()
            }

        /** The row as it is now, or null once deleted. */
        private fun current(table: TableMetadata, key: JsonObject): JsonObject? =
            session.execute(SimpleStatement.newInstance("SELECT JSON * FROM ${qualified(table)} WHERE ${where(table, key)}")).one()?.getString(0)?.let { Json.parseToJsonElement(it).jsonObject }

        private fun checkUnchanged(table: TableMetadata, key: JsonObject) {
            val current = current(table, key) ?: throw SQLException(tr("The row was deleted by someone else since it was read; reload and try again."))
            val read = key[VersionField]?.jsonPrimitive?.content ?: return
            if (version(current) != read) throw changed(table, key)
        }

        private fun changed(table: TableMetadata, key: JsonObject) =
            SQLException(tr("The row ({0}) was changed by someone else since it was read; reload and try again.", keyNames(table).joinToString { "$it = ${key[it]}" }))

        /** `pk1 = … AND ck1 = …` from the primary key's JSON values, each read back through fromJson so its type is the column's. */
        private fun where(table: RelationMetadata, values: JsonObject): String =
            (table.partitionKey + table.clusteringColumns.keys).joinToString(" AND ") { column ->
                val name = column.name.asCql(true)
                val value = values[name] ?: throw SQLException(tr("The key has no {0}.", name))
                "$name = ${literal(column, value)}"
            }

        override val consoleLanguage =
            RecordLanguage(
                tr("CQL"),
                "SELECT * FROM shop.orders WHERE customer_id = 42 LIMIT 20;",
                tr("CQL statements, separated by semicolons; the last one's rows show below. DESCRIBE works on Cassandra 4 and later."),
            )

        override fun sampleStatement(container: List<String>): String =
            when (container.size) {
                0 -> "SELECT keyspace_name, table_name FROM system_schema.tables LIMIT 100;"
                1 -> "SELECT table_name FROM system_schema.tables WHERE keyspace_name = '${container[0].replace("'", "''")}';"
                else -> "SELECT * FROM ${container.joinToString(".") { CqlIdentifier.fromInternal(it).asCql(true) }} LIMIT 100;"
            }

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val statements = splitStatements(statement)
                if (statements.isEmpty()) throw SQLException(tr("Nothing to run"))
                val started = System.currentTimeMillis()
                var last: ResultSet? = null
                statements.forEach { last = session.execute(SimpleStatement.newInstance(it).setPageSize(MaxConsoleRows)) }
                val elapsed = System.currentTimeMillis() - started
                val result = last!!
                val columns = result.columnDefinitions
                if (columns.size() == 0) return@io ConsoleReply.Text(if (statements.size == 1) tr("Done.") else tr("Ran {0} statements.", statements.size), elapsed)
                // The first page only: iterating on would fetch the rest.
                val rows = List(minOf(result.availableWithoutFetching, MaxConsoleRows)) { result.one()!! }.map { row -> (0 until columns.size()).map { cell(row, it) } }
                ConsoleReply.Table(
                    QueryResult.Rows(columnsOf(columns), rows, truncated = result.executionInfo.pagingState != null, elapsedMillis = elapsed),
                    note = if (result.executionInfo.pagingState != null) tr("First {0} rows", rows.size) else null,
                )
            }

        override fun isRead(statement: String): Boolean = isReadStatement(statement)

        override fun close() = session.close()

        private fun cell(row: Row, index: Int): String? {
            if (row.isNull(index)) return null
            val type = row.columnDefinitions[index].type
            val value = row.getObject(index)
            return when (value) {
                is String -> value
                is ByteBuffer -> "0x" + bytes(value).joinToString("") { "%02x".format(it) }
                else -> {
                    val literal = session.context.codecRegistry.codecFor<Any>(type).format(value)
                    // Scalars show as values, not as CQL literals: 2024-01-01 10:00, not '2024-01-01 10:00'.
                    if (literal.length >= 2 && literal.startsWith("'") && literal.endsWith("'")) literal.substring(1, literal.length - 1).replace("''", "'") else literal
                }
            }
        }

        private fun columnsOf(columns: ColumnDefinitions): List<ResultColumn> =
            columns.map { definition ->
                val type = definition.type.asCql(false, true)
                ResultColumn(definition.name.asInternal(), type, if (type in NumericTypes) Types.NUMERIC else Types.VARCHAR)
            }
    }

    companion object {
        /** The key field that holds a hash of the row as read, so a write can tell it changed since. */
        internal const val VersionField = "_version"

        private const val MaxConsoleRows = 1_000

        private val NumericTypes = setOf("int", "bigint", "smallint", "tinyint", "varint", "decimal", "float", "double", "counter")

        private val ReadVerbs = setOf("SELECT", "DESCRIBE", "DESC", "USE", "LIST", "EXPLAIN", "TRACING", "PAGING", "CONSISTENCY")

        internal fun isSystem(keyspace: String) = keyspace == "system" || keyspace.startsWith("system_")

        /** The primary key as `(partition) clustering…`, as a table's tooltip shows it. */
        internal fun primaryKeyText(table: RelationMetadata): String {
            val partition = table.partitionKey.joinToString(", ") { it.name.asCql(true) }
            val clustering = table.clusteringColumns.keys.joinToString(", ") { it.name.asCql(true) }
            return if (clustering.isEmpty()) "key ($partition)" else "key (($partition), $clustering)"
        }

        /** Primary key columns as `SELECT JSON` names them. */
        internal fun keyNames(table: RelationMetadata): List<String> = (table.partitionKey + table.clusteringColumns.keys).map { it.name.asCql(true) }

        internal fun qualified(table: RelationMetadata): String = "${table.keyspace.asCql(true)}.${table.name.asCql(true)}"

        /**
         * The browser's query as a `SELECT JSON`: a WHERE clause (with ORDER BY, LIMIT or ALLOW FILTERING after it) on the
         * table, or a whole SELECT, which gets JSON added.
         */
        internal fun selectJson(table: RelationMetadata, query: String): String {
            val text = query.trim().removeSuffix(";").trim()
            if (text.isEmpty()) return "SELECT JSON * FROM ${qualified(table)}"
            if (Regex("^select\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                return if (Regex("^select\\s+json\\s", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else text.replaceFirst(Regex("^select\\s+", RegexOption.IGNORE_CASE), "SELECT JSON ")
            }
            val clause = if (Regex("^(order\\s+by|limit|allow\\s+filtering|per\\s+partition)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "WHERE $text"
            return "SELECT JSON * FROM ${qualified(table)} $clause"
        }

        /** A hash of the row as `SELECT JSON` returned it: its version, for telling whether it changed. */
        internal fun version(row: JsonObject): String =
            MessageDigest.getInstance("SHA-256").digest(row.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        /** A JSON value as a CQL term for [column]: plain literals for simple types, fromJson() for the rest. */
        internal fun literal(column: ColumnMetadata, value: JsonElement): String {
            if (value is JsonNull) throw SQLException(tr("A key column can't be null."))
            val primitive = value as? JsonPrimitive
            val type = column.type
            return when {
                primitive == null -> "fromJson('${value.toString().replace("'", "''")}')"
                type in setOf(DataTypes.TEXT, DataTypes.ASCII, DataTypes.INET, DataTypes.TIMESTAMP, DataTypes.DATE, DataTypes.TIME, DataTypes.DURATION) ->
                    "'" + primitive.content.replace("'", "''") + "'"
                type in setOf(DataTypes.UUID, DataTypes.TIMEUUID) && Uuid.matches(primitive.content) -> primitive.content
                type == DataTypes.BLOB && Blob.matches(primitive.content) -> primitive.content
                type == DataTypes.BOOLEAN && primitive.booleanOrNull != null -> primitive.content
                !primitive.isString && Number.matches(primitive.content) -> primitive.content
                else -> "fromJson('${value.toString().replace("'", "''")}')"
            }
        }

        private val Uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val Blob = Regex("^0x[0-9a-fA-F]*$")
        private val Number = Regex("^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$")

        /** Splits CQL on semicolons outside quotes and comments. */
        internal fun splitStatements(text: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            var quote: Char? = null
            while (i < text.length) {
                val c = text[i]
                val next = text.getOrNull(i + 1)
                when {
                    quote != null -> {
                        current.append(c)
                        if (c == quote && next == quote) {
                            current.append(next)
                            i++
                        } else if (c == quote) quote = null
                    }
                    c == '\'' || c == '"' -> {
                        quote = c
                        current.append(c)
                    }
                    c == '-' && next == '-' || c == '/' && next == '/' -> {
                        while (i < text.length && text[i] != '\n') i++
                        continue
                    }
                    c == '/' && next == '*' -> {
                        val end = text.indexOf("*/", i + 2)
                        i = if (end < 0) text.length else end + 2
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

        /** Statements that only read: SELECT, DESCRIBE, LIST… Anything else, in any of the statements, is a write. */
        internal fun isReadStatement(text: String): Boolean {
            val statements = runCatching { splitStatements(text) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
            return statements.all { it.split(Regex("\\s+"), limit = 2).first().uppercase() in ReadVerbs }
        }

        /** Each comma-separated host is a contact point; one without a port takes the profile's. */
        internal fun contactPoints(hosts: String, tunnelHost: String, port: Int): List<InetSocketAddress> {
            val listed = hosts.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            // Through an SSH tunnel there's one way in: the tunnel's end.
            if (listed.size <= 1 || tunnelHost == "127.0.0.1" && listed.none { it.startsWith("127.") || it == "localhost" }) {
                return listOf(InetSocketAddress.createUnresolved(tunnelHost, port))
            }
            return listed.map { entry ->
                val bracketed = entry.startsWith("[")
                val portPart = if (bracketed) entry.substringAfter("]", "").removePrefix(":") else entry.substringAfterLast(':', "").takeIf { entry.count { it == ':' } == 1 }.orEmpty()
                val hostPart = if (bracketed) entry.substringAfter("[").substringBefore("]") else if (portPart.isEmpty()) entry else entry.substringBeforeLast(':')
                InetSocketAddress.createUnresolved(hostPart, portPart.toIntOrNull() ?: port)
            }
        }

        private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

        private fun trustingEverything(): SSLContext =
            SSLContext.getInstance("TLS").apply {
                val trustAll =
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
    }
}
