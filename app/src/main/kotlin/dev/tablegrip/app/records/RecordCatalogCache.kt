package dev.tablegrip.app.records

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.core.completion.ConsoleCatalog
import dev.tablegrip.core.completion.GraphNames
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.RecordConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a record console completes from, loaded from the store the first time it's asked for: container names level by
 * level, a container's fields, a graph's labels, types and property keys. Each answers null until it has loaded;
 * [version] bumps when something arrives, so an open list fills in.
 */
internal class RecordCatalogCache(private val scope: CoroutineScope, private val family: EngineFamily, private val store: suspend () -> RecordConnection) : ConsoleCatalog {
    var version by mutableIntStateOf(0)
        private set

    private val containers = HashMap<List<String>, List<String>>()
    private val fields = HashMap<List<String>, List<String>>()
    private val graph = HashMap<GraphNames, List<String>>()
    private val requested = HashSet<Any>()
    private val jobs = ArrayList<Job>()

    override fun containers(parent: List<String>): List<String>? =
        containers[parent] ?: null.also {
            // A graph's containers are its labels and types, counted one by one; the console asks for those by name instead.
            if (family == EngineFamily.Neo4j) return emptyList()
            load("containers" to parent) { containers[parent] = store().containers(parent).map { it.name } }
        }

    override fun fields(container: List<String>): List<String>? =
        fields[container] ?: null.also { load("fields" to container) { fields[container] = loadFields(container) } }

    override fun graph(kind: GraphNames): List<String>? =
        graph[kind] ?: null.also {
            if (family != EngineFamily.Neo4j) return emptyList()
            load(kind) {
                val procedure =
                    when (kind) {
                        GraphNames.Labels -> "CALL db.labels() YIELD label RETURN label ORDER BY label"
                        GraphNames.RelationshipTypes -> "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType ORDER BY relationshipType"
                        GraphNames.PropertyKeys -> "CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey ORDER BY propertyKey"
                    }
                graph[kind] = (store().execute(procedure) as? ConsoleReply.Table)?.rows?.rows?.mapNotNull { it.firstOrNull() }.orEmpty()
            }
        }

    private suspend fun loadFields(container: List<String>): List<String> {
        val store = store()
        val names = LinkedHashSet<String>()
        when (family) {
            // A metric's labels and a measurement's tags and fields come with their definitions.
            EngineFamily.Prometheus, EngineFamily.InfluxDB -> {
                val definition = runCatching { Json.parseToJsonElement(store.definition(container)) }.getOrNull() as? JsonObject
                (definition?.get("labels") as? JsonObject)?.keys?.let { names += it }
                listOf("tags", "fields").forEach { key -> (definition?.get(key) as? JsonArray)?.forEach { (it as? JsonPrimitive)?.content?.let(names::add) } }
            }
            else -> {
                names += store.template(container).keys.map(::unquote)
                // Where the definition says little (a document store's key, a payload), a page of records says more.
                if (family in Sampled) store.page(container, "", null, SampleSize).records.forEach { names += it.document.keys }
            }
        }
        return names.toList()
    }

    /** CQL quotes the names it must (`"Status"`); completion wants them as they are. */
    private fun unquote(name: String): String = if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) name.substring(1, name.length - 1).replace("\"\"", "\"") else name

    private fun load(key: Any, block: suspend () -> Unit) {
        if (!requested.add(key)) return
        jobs.removeAll { !it.isActive }
        jobs +=
            scope.launch {
                try {
                    block()
                    version++
                } catch (e: CancellationException) {
                    requested.remove(key)
                    throw e
                } catch (_: Throwable) {
                    // Completion goes without; the console reports connection errors when a statement runs. Not retried
                    // until the next statement, rather than on every key.
                }
            }
    }

    /** After a statement: it may have made or dropped keyspaces, tables, labels or columns. */
    fun statementRan() {
        containers.clear()
        fields.clear()
        graph.clear()
        requested.clear()
    }

    fun dispose() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private companion object {
        const val SampleSize = 20
        val Sampled = setOf(EngineFamily.CouchDB, EngineFamily.Solr, EngineFamily.Qdrant, EngineFamily.Chroma)
    }
}
