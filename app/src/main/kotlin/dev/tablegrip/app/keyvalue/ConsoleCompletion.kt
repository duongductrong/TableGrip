package dev.tablegrip.app.keyvalue

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.documents.DocumentSchema
import dev.tablegrip.core.completion.DocumentField
import dev.tablegrip.core.completion.MongoCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Key names for a console's completion, scanned by prefix as they're asked for: what's known is returned at once, and
 * a scan for the prefix starts in the background (after typing settles), bumping [version] when it lands. A prefix
 * whose scan saw every matching key answers its longer prefixes too.
 */
internal class KeyNameCache(private val scope: CoroutineScope, private val scan: suspend (prefix: String, limit: Int) -> Pair<List<String>, Boolean>) {
    var version by mutableIntStateOf(0)
        private set

    private class Entry(val keys: List<String>, val complete: Boolean)

    private val entries = HashMap<String, Entry>()
    private var job: Job? = null
    private var loading: String? = null

    fun keys(prefix: String): List<String> {
        val hit = entries.entries.filter { prefix.startsWith(it.key) && (it.value.complete || it.key == prefix) }.maxByOrNull { it.key.length }
        if (hit == null) request(prefix)
        val known = hit ?: entries.entries.filter { prefix.startsWith(it.key) }.maxByOrNull { it.key.length }
        return known?.value?.keys?.filter { it.startsWith(prefix) }.orEmpty()
    }

    private fun request(prefix: String) {
        if (loading == prefix) return
        job?.cancel()
        loading = prefix
        job =
            scope.launch {
                try {
                    delay(Debounce)
                    val (keys, complete) = scan(prefix, Limit)
                    entries[prefix] = Entry(keys.sorted(), complete)
                    version++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Completion goes without keys; the console reports connection errors when a command runs.
                    entries[prefix] = Entry(emptyList(), complete = false)
                } finally {
                    if (loading == prefix) loading = null
                }
            }
    }

    /** Forgets what's known, after a command that may have changed the keys or a switch of database. */
    fun clear() {
        job?.cancel()
        loading = null
        entries.clear()
    }

    companion object {
        const val Limit = 300
        private const val Debounce = 150L

        /** A glob matching keys that start with [prefix], its own glob characters escaped. */
        fun prefixPattern(prefix: String): String = prefix.replace(Regex("""[\\*?\[\]]""")) { "\\" + it.value } + "*"
    }
}

/**
 * What the Mongo shell console completes from: the server's databases, the current database's collections and the
 * fields a sample of each collection's documents has. Each is loaded the first time it's asked for; [version] bumps
 * when something arrives.
 */
internal class MongoCatalogCache(
    private val scope: CoroutineScope,
    private val database: () -> String,
    private val listDatabases: suspend () -> List<String>,
    private val listCollections: suspend (database: String) -> List<String>,
    private val sample: suspend (database: String, collection: String) -> List<String>,
) : MongoCatalog {
    var version by mutableIntStateOf(0)
        private set

    private var databases: List<String>? = null
    private val collections = HashMap<String, List<String>>()
    private val schemas = HashMap<Pair<String, String>, DocumentSchema>()
    private val requested = HashSet<String>()
    private val failed = HashSet<String>()

    override fun databases(): List<String>? = databases ?: null.also { load("databases") { databases = listDatabases().sorted() } }

    override fun collections(): List<String>? {
        val db = database()
        return collections[db] ?: null.also { load("collections:$db") { collections[db] = listCollections(db).sorted() } }
    }

    override fun fields(collection: String): List<DocumentField>? {
        val key = database() to collection
        schemas[key]?.let { return it.fields() }
        load("fields:${key.first}:${key.second}") {
            val schema = DocumentSchema()
            schema.add(sample(key.first, key.second).mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() })
            schemas[key] = schema
        }
        return null
    }

    private fun load(what: String, block: suspend () -> Unit) {
        if (!requested.add(what)) return
        scope.launch {
            try {
                block()
                version++
            } catch (e: CancellationException) {
                requested.remove(what)
                throw e
            } catch (_: Throwable) {
                // Left unloaded until the next command, rather than retried on every key.
                failed += what
            }
        }
    }

    /** After a command: collections may have come or gone, and documents changed. Field samples are kept. */
    fun commandRan() {
        collections.clear()
        databases = null
        requested.removeAll { it.startsWith("collections:") || it == "databases" || it in failed }
        failed.clear()
    }

    companion object {
        const val SampleSize = 100
    }
}
