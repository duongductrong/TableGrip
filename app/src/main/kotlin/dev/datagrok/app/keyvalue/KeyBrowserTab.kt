package dev.datagrok.app.keyvalue

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.datagrok.app.AppState
import dev.datagrok.app.workspace.WorkspaceTab
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.EngineFamily
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

    /** etcd: one keyspace of plain values, rather than Redis's numbered databases of typed values. */
    val plainValues = config.type.family == EngineFamily.Etcd

    override val title = if (plainValues) "${config.name} · keys" else "${config.name} · db$keyspace"

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

    /** When [value] was read, so its TTL can count down without asking the server again. */
    var valueReadAt by mutableLongStateOf(0L)
        private set

    /** Splits key names into the list's folders: `user:1` sits in `user`, etcd's `/services/api` in `services`. */
    val delimiter = if (plainValues) "/" else ":"

    /** Folders in the key list, rather than one flat list. */
    var treeView by mutableStateOf(true)

    /** Group paths the user folded away; every group starts expanded. */
    var collapsed by mutableStateOf<Set<String>>(emptySet())

    /** Shows only keys of this type; null for all. Applies to the keys scanned so far. */
    var typeFilter by mutableStateOf<KeyType?>(null)

    val visibleKeys: List<KeyInfo>
        get() = typeFilter?.let { type -> keys.filter { it.type == type } } ?: keys

    fun toggleGroup(path: String) {
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
    }

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
        // A confirmation is about the key it was for; errors stay until dismissed.
        if (key != selectedKey && message?.isError == false) message = null
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
                valueReadAt = System.currentTimeMillis()
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
    fun edit(edit: KeyEdit, key: String? = selectedKey) = editAll(listOf(edit), key)

    /** Applies [edits] in order, e.g. removing a set member and adding its replacement. */
    fun editAll(edits: List<KeyEdit>, key: String? = selectedKey, confirmation: String? = null) {
        val target = key ?: return
        if (edits.isEmpty()) return
        act { redis ->
            edits.forEach { redis.edit(keyspace, target, it) }
            if (keys.none { it.name == target }) keys = (keys + KeyInfo(target, typeOf(edits.last()), null)).sortedBy { it.name }
            selectedKey = target
            readSelected(redis, target)
            confirmation?.let { message = Message(it, isError = false) }
        }
    }

    /**
     * Creates [key] with a first element, as Redis has no empty hashes, lists, sets or streams, expiring after
     * [ttlSeconds] if given.
     */
    fun create(key: String, edit: KeyEdit, ttlSeconds: Long? = null, onCreated: () -> Unit = {}) {
        if (key.isBlank()) return
        act { redis ->
            if (redis.read(keyspace, key) != null) throw IllegalStateException("$key already exists.")
            redis.edit(keyspace, key, edit)
            if (ttlSeconds != null) redis.expire(keyspace, key, ttlSeconds * 1_000)
            readSelected(redis, key)
            keys = (keys + KeyInfo(key, typeOf(edit), value?.ttlMillis)).sortedBy { it.name }
            selectedKey = key
            message = Message("Created $key.", isError = false)
            onCreated()
        }
    }

    private suspend fun readSelected(redis: KeyValueConnection, key: String) {
        value = redis.read(keyspace, key)
        valueReadAt = System.currentTimeMillis()
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
            readSelected(redis, to)
            message = Message("Renamed $from to $to.", isError = false)
        }
    }

    /** Sets the time to live in seconds, or removes it when [seconds] is null. */
    fun expire(seconds: Long?) {
        val key = selectedKey ?: return
        act { redis ->
            redis.expire(keyspace, key, seconds?.times(1_000))
            readSelected(redis, key)
            keys = keys.map { if (it.name == key) it.copy(ttlMillis = value?.ttlMillis) else it }
            message = Message(if (seconds == null) "$key no longer expires." else "$key expires in ${formatTtl(seconds * 1_000)}.", isError = false)
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
