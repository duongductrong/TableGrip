package dev.datagrok.app.keyvalue

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.KeyEdit
import dev.datagrok.core.driver.KeyInfo
import dev.datagrok.core.driver.KeyType
import dev.datagrok.core.driver.KeyValue
import dev.datagrok.core.driver.KeyValueConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Browses one database of a key-value store: keys matching a pattern, a page at a time, and the selected key's value. */
class KeyBrowserTab(private val app: AppState, val config: ConnectionConfig, val keyspace: Int) : WorkspaceTab {
    override val connectionId = config.id
    override val title = "${config.name} · db$keyspace"

    val readOnly: Boolean
        get() = config.readOnly

    /** A glob, as Redis SCAN takes it: `user:*`, `session:??`, `cache:[ab]*`. */
    val pattern = TextFieldState("*")

    var keys by mutableStateOf<List<KeyInfo>>(emptyList())
        private set

    /** Continues the scan; null once every key has been listed. */
    var cursor by mutableStateOf<String?>(null)
        private set

    var scanning by mutableStateOf(false)
        private set

    var selectedKey by mutableStateOf<String?>(null)
        private set

    var value by mutableStateOf<KeyValue?>(null)
        private set

    var loadingValue by mutableStateOf(false)
        private set

    /** The last failure or confirmation, shown under the toolbar. */
    var message by mutableStateOf<Message?>(null)

    data class Message(val text: String, val isError: Boolean)

    private val jobs = mutableListOf<Job>()
    private var scanJob: Job? = null
    private var valueJob: Job? = null

    init {
        reload()
    }

    private suspend fun connection(): KeyValueConnection = app.databases.keyValue(config)

    /** Starts the scan over, e.g. after the pattern changed. */
    fun reload() {
        scanJob?.cancel()
        keys = emptyList()
        cursor = null
        scanJob = launch { scanPage(null) }
    }

    fun loadMore() {
        val from = cursor ?: return
        if (scanning) return
        scanJob = launch { scanPage(from) }
    }

    /**
     * Fetches until a page's worth of keys turned up or the scan ended: with a narrow pattern, SCAN can return many empty
     * batches in a row.
     */
    private suspend fun scanPage(from: String?) {
        scanning = true
        try {
            val redis = connection()
            var next = from
            val found = mutableListOf<KeyInfo>()
            do {
                val page = redis.scan(keyspace, pattern.text.toString().trim().ifEmpty { "*" }, next, count = 500)
                found += page.keys
                next = page.cursor
            } while (next != null && found.size < PageSize)
            keys = (keys + found).distinctBy { it.name }
            cursor = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            message = Message(e.message ?: e.toString(), isError = true)
        } finally {
            scanning = false
        }
    }

    fun select(key: String?) {
        selectedKey = key
        refreshValue()
    }

    fun refreshValue() {
        valueJob?.cancel()
        val key = selectedKey
        if (key == null) {
            value = null
            return
        }
        valueJob = launch {
            loadingValue = true
            try {
                value = connection().read(keyspace, key)
                if (value == null) {
                    // Deleted or expired since it was listed.
                    keys = keys.filterNot { it.name == key }
                    message = Message("$key no longer exists.", isError = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                value = null
                message = Message(e.message ?: e.toString(), isError = true)
            } finally {
                loadingValue = false
            }
        }
    }

    /** Applies [edit] to the selected key (or [key]) and shows the result. */
    fun edit(edit: KeyEdit, key: String? = selectedKey) {
        val target = key ?: return
        act { redis ->
            redis.edit(keyspace, target, edit)
            if (keys.none { it.name == target }) keys = (keys + KeyInfo(target, typeOf(edit), null)).sortedBy { it.name }
            selectedKey = target
            value = redis.read(keyspace, target)
        }
    }

    /** Creates [key] with a first element, as Redis has no empty hashes, lists, sets or streams. */
    fun create(key: String, edit: KeyEdit) {
        if (key.isBlank()) return
        act { redis ->
            if (redis.read(keyspace, key) != null) throw IllegalStateException("$key already exists.")
            redis.edit(keyspace, key, edit)
            keys = (keys + KeyInfo(key, typeOf(edit), null)).sortedBy { it.name }
            selectedKey = key
            value = redis.read(keyspace, key)
            message = Message("Created $key.", isError = false)
        }
    }

    fun delete() {
        val key = selectedKey ?: return
        act { redis ->
            redis.delete(keyspace, listOf(key))
            keys = keys.filterNot { it.name == key }
            selectedKey = null
            value = null
            message = Message("Deleted $key.", isError = false)
        }
    }

    fun rename(to: String) {
        val from = selectedKey ?: return
        if (to.isBlank() || to == from) return
        act { redis ->
            redis.rename(keyspace, from, to)
            keys = keys.map { if (it.name == from) it.copy(name = to) else it }.sortedBy { it.name }
            selectedKey = to
            value = redis.read(keyspace, to)
        }
    }

    /** Sets the time to live in seconds, or removes it when [seconds] is null. */
    fun expire(seconds: Long?) {
        val key = selectedKey ?: return
        act { redis ->
            redis.expire(keyspace, key, seconds?.times(1_000))
            value = redis.read(keyspace, key)
            keys = keys.map { if (it.name == key) it.copy(ttlMillis = value?.ttlMillis) else it }
        }
    }

    private fun act(block: suspend (KeyValueConnection) -> Unit) {
        launch {
            try {
                message = null
                block(connection())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = Message(e.message ?: e.toString(), isError = true)
            }
        }
    }

    private fun launch(block: suspend () -> Unit): Job =
        app.scope.launch { block() }.also { job ->
            jobs += job
            job.invokeOnCompletion { app.scope.launch { jobs -= job } }
        }

    override fun dispose() {
        jobs.toList().forEach { it.cancel() }
    }

    private companion object {
        const val PageSize = 200

        fun typeOf(edit: KeyEdit): KeyType =
            when (edit) {
                is KeyEdit.SetText -> KeyType.Text
                is KeyEdit.SetField, is KeyEdit.DeleteField -> KeyType.Hash
                is KeyEdit.SetItem, is KeyEdit.AppendItem, is KeyEdit.RemoveItem -> KeyType.List
                is KeyEdit.AddMember, is KeyEdit.RemoveMember -> KeyType.Set
                is KeyEdit.SetScore -> KeyType.SortedSet
                is KeyEdit.AddEntry, is KeyEdit.DeleteEntry -> KeyType.Stream
            }
    }
}
