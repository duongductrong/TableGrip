package dev.datagrok.app.completion

import dev.datagrok.core.dialect.SqlDialect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.connections.DatabaseRegistry
import dev.datagrok.core.completion.ColumnEntry
import dev.datagrok.core.completion.Relation
import dev.datagrok.core.completion.SchemaProvider
import dev.datagrok.core.completion.TableEntry
import dev.datagrok.core.completion.ValueContext
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.metadata.Namespace
import dev.datagrok.core.metadata.TableRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Schema metadata for completion, loaded lazily and cached per connection: namespaces and tables first, then all
 * columns of a namespace and the foreign keys of a table the first time they're needed. [version] bumps whenever
 * something arrives, so UIs can recompute their suggestions. Use from the UI thread only.
 */
class SchemaCache(private val scope: CoroutineScope, private val databases: DatabaseRegistry, private val config: ConnectionConfig) : SchemaProvider {
    var version by mutableIntStateOf(0)
        private set

    private var namespaceList: List<Namespace>? = null
    private val tablesByNamespace = mutableMapOf<String, List<TableEntry>>()
    private val columnsByNamespace = mutableMapOf<String, Map<String, List<ColumnEntry>>>()
    private val relationsByTable = mutableMapOf<Pair<String, String>, List<Relation>>()
    private val loading = mutableSetOf<Any>()

    override val defaultNamespace: String?
        get() {
            val names = namespaceList?.map { it.displayName } ?: return null
            return SqlDialect.of(config.type).defaultNamespace(names, config.database)
        }

    override fun namespaces(): List<String> = namespaceList?.map { it.displayName } ?: emptyList<String>().also { loadNamespaces() }

    override fun tables(namespace: String?): List<TableEntry>? {
        val key = key(namespace) ?: return null.also { loadNamespaces() }
        return tablesByNamespace[key] ?: null.also { loadTables(key) }
    }

    override fun columns(namespace: String?, table: String): List<ColumnEntry>? {
        val key = key(namespace) ?: return null.also { loadNamespaces() }
        val columns = columnsByNamespace[key] ?: return null.also { loadColumns(key) }
        return columns[table] ?: columns.entries.firstOrNull { it.key.equals(table, ignoreCase = true) }?.value ?: emptyList()
    }

    override fun relations(namespace: String?, table: String): List<Relation>? {
        val key = key(namespace) ?: return null.also { loadNamespaces() }
        return relationsByTable[key to table] ?: null.also { loadRelations(key, table) }
    }

    /** Every table and view of every namespace loaded so far, with whether it's a view; starts loading the rest. */
    fun allTables(): List<Pair<TableRef, Boolean>> {
        val loaded = namespaceList ?: return emptyList<Pair<TableRef, Boolean>>().also { loadNamespaces() }
        return loaded.flatMap { namespace -> tables(namespace.displayName).orEmpty().map { TableRef(namespace, it.name) to it.isView } }
    }

    /** Values for a [ValueContext], e.g. distinct statuses starting with "pa". */
    suspend fun values(context: ValueContext): List<String> {
        val namespace = namespaceOf(key(context.namespace) ?: return emptyList()) ?: return emptyList()
        return runCatching { databases.database(config).sampleValues(TableRef(namespace, context.table), context.column, context.prefix) }
            .onFailure { log.debug("No value suggestions for {}.{}", context.table, context.column, it) }
            .getOrDefault(emptyList())
    }

    fun isNumeric(namespace: String?, table: String, column: String): Boolean =
        columns(namespace, table)?.firstOrNull { it.name.equals(column, true) }?.type?.lowercase()?.let { type ->
            NumericTypes.any { type.startsWith(it) }
        } ?: false

    /** Starts loading anything a completion reported as missing. */
    fun request(pending: Set<Pair<String?, String>>) = pending.forEach { (namespace, table) -> columns(namespace, table) }

    /** Forgets everything, e.g. after DDL or an explorer refresh. */
    fun invalidate() {
        namespaceList = null
        tablesByNamespace.clear()
        columnsByNamespace.clear()
        relationsByTable.clear()
        version++
    }

    private fun key(namespace: String?): String? = namespace ?: defaultNamespace

    private fun namespaceOf(key: String) = namespaceList?.firstOrNull { it.displayName.equals(key, ignoreCase = true) }

    private fun load(token: Any, block: suspend () -> Unit) {
        if (!loading.add(token)) return
        scope.launch {
            try {
                block()
                version++
            } catch (e: Exception) {
                log.debug("Schema load failed for {}", token, e)
            } finally {
                loading.remove(token)
            }
        }
    }

    private fun loadNamespaces() = load("namespaces") { namespaceList = databases.database(config).namespaces() }

    private fun loadTables(key: String) =
        load("tables:$key") {
            val namespace = namespaceOf(key) ?: return@load
            tablesByNamespace[key] = databases.database(config).tables(namespace).map { TableEntry(it.ref.name, it.isView) }
        }

    private fun loadColumns(key: String) =
        load("columns:$key") {
            val namespace = namespaceOf(key) ?: return@load
            columnsByNamespace[key] =
                databases.database(config).columnsOf(namespace).mapValues { (_, columns) -> columns.map { ColumnEntry(it.name, it.typeName, it.primaryKey) } }
        }

    private fun loadRelations(key: String, table: String) =
        load("relations:$key.$table") {
            val namespace = namespaceOf(key) ?: return@load
            relationsByTable[key to table] =
                databases.database(config).relations(TableRef(namespace, table)).map {
                    Relation(it.fromTable.name, it.fromColumns, it.toTable.name, it.toColumns)
                }
        }

    private companion object {
        val log = LoggerFactory.getLogger(SchemaCache::class.java)
        val NumericTypes = listOf("int", "integer", "bigint", "smallint", "tinyint", "numeric", "decimal", "real", "double", "float", "serial", "bigserial", "money")
    }
}
