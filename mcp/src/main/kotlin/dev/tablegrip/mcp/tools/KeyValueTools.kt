package dev.tablegrip.mcp.tools

import dev.tablegrip.core.agent.AgentCapability
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.driver.KeyEdit
import dev.tablegrip.core.driver.KeyValue
import dev.tablegrip.core.driver.KeyValueConnection
import dev.tablegrip.core.driver.KeyValueStore
import dev.tablegrip.mcp.McpSession
import dev.tablegrip.mcp.ResultEncoder
import dev.tablegrip.mcp.Tool
import dev.tablegrip.mcp.ToolCall
import dev.tablegrip.mcp.ToolException
import dev.tablegrip.mcp.params
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Commands that are never run for an agent: they wipe, reconfigure or stop the server, or run code on it. */
private val RefusedCommands =
    setOf(
        "FLUSHALL", "FLUSHDB", "CONFIG", "SHUTDOWN", "DEBUG", "SCRIPT", "EVAL", "EVALSHA", "EVAL_RO", "EVALSHA_RO", "FCALL", "FCALL_RO",
        "FUNCTION", "MODULE", "ACL", "SAVE", "BGSAVE", "BGREWRITEAOF", "REPLICAOF", "SLAVEOF", "MIGRATE", "MONITOR", "SYNC", "PSYNC",
        "SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "CLIENT", "CLUSTER", "SELECT", "SWAPDB", "FAILOVER", "RESET", "QUIT", "MULTI", "EXEC",
        "WATCH", "DEFRAG",
    )

/** Commands among the reads that still mutate something. */
private val Destructive = setOf("DEL", "UNLINK", "RENAME", "RENAMENX", "EXPIRE", "PEXPIRE", "PERSIST", "HDEL", "LREM", "SREM", "ZREM", "XDEL", "XTRIM", "LTRIM", "GETDEL")

internal fun keyValueTools(): List<Tool> =
    listOf(
        Tool(
            name = "kv_keyspaces",
            title = "List keyspaces",
            description = "Lists a key-value store's keyspaces (Redis's numbered databases) with their key counts.",
            readOnly = true,
            input = params { connection() },
        ) {
            kv(AgentCapability.Schema) { _, store ->
                buildJsonObject {
                    put("server", store.server())
                    put("keyspaces", buildJsonArray { store.keyspaces().forEach { add(buildJsonObject { put("index", it.index); it.keys?.let { keys -> put("keys", keys) } }) } })
                }
            }
        },
        Tool(
            name = "kv_scan",
            title = "Scan keys",
            description = "Lists keys matching a glob pattern (e.g. user:*), with their type and TTL, a page at a time; pass 'cursor' back to continue.",
            readOnly = true,
            input = params {
                connection()
                string("pattern", "Glob pattern; * by default.")
                integer("keyspace", "Keyspace index (default 0).", minimum = 0)
                string("cursor", "The cursor a previous call returned.")
                integer("count", "Keys per page (default 200).", minimum = 1, maximum = 1_000)
            },
        ) {
            kv(AgentCapability.Data) { _, store ->
                val page = store.scan(int("keyspace", 0, 0..10_000), stringOrNull("pattern") ?: "*", stringOrNull("cursor"), int("count", 200, 1..1_000))
                buildJsonObject {
                    put(
                        "keys",
                        buildJsonArray {
                            page.keys.forEach { key ->
                                add(buildJsonObject {
                                    put("key", ResultEncoder.clip(key.name, 500))
                                    put("type", key.type.label)
                                    key.ttlMillis?.let { put("ttlMs", it) }
                                })
                            }
                        },
                    )
                    page.cursor?.let { put("cursor", it) }
                    put("complete", page.cursor == null)
                }
            }
        },
        Tool(
            name = "kv_read",
            title = "Read a key",
            description = "Reads one key's value (string, hash, list, set, sorted set, stream…), up to 'limit' elements.",
            readOnly = true,
            input = params {
                connection()
                string("key", "The key.", required = true)
                integer("keyspace", "Keyspace index (default 0).", minimum = 0)
                integer("limit", "Elements to read at most (default 200).", minimum = 1, maximum = 5_000)
            },
        ) {
            kv(AgentCapability.Data) { _, store ->
                val value = store.read(int("keyspace", 0, 0..10_000), string("key"), int("limit", 200, 1..5_000)) ?: throw ToolException("There is no key '${string("key")}'.")
                encode(value)
            }
        },
        Tool(
            name = "kv_command",
            title = "Run a key-value command",
            description =
                "Runs one command, given as its arguments, e.g. [\"HGETALL\", \"user:1\"] or for etcd [\"get\", \"/config\", \"--prefix\"]. " +
                    "Read commands need data access; commands that change data need the connection to allow writes and may ask " +
                    "the user first. Commands that wipe, reconfigure or stop the server (FLUSHALL, CONFIG, EVAL…) are always refused.",
            readOnly = false,
            destructive = true,
            input = params {
                connection()
                strings("arguments", "The command and its arguments, one string each.", required = true)
                integer("keyspace", "Keyspace index (default 0).", minimum = 0)
            },
        ) {
            val arguments = strings("arguments").takeIf { it.isNotEmpty() } ?: throw ToolException("'arguments' is required.")
            val workspace = session.workspace
            val config = workspace.resolve(string("connection"))
            val verb = arguments.first().uppercase()
            val etcd = config.type.family == EngineFamily.Etcd
            if (!etcd && verb in RefusedCommands) throw ToolException("$verb isn't run for agents.")
            val read = if (etcd) KeyValueStore.isEtcdRead(arguments) else verb in KeyValueStore.ReadCommands
            workspace.require(if (read) AgentCapability.Data else AgentCapability.WriteData, config)
            if (!read && session.needsConfirmation(config, if (verb in Destructive || etcd && arguments.first().lowercase() == "del") "$verb removes data" else null)) {
                session.confirm(config, arguments.joinToString(" "), if (verb in Destructive) "$verb removes data" else null)
            }
            workspace.use(config) { live ->
                val store = live.connection as? KeyValueConnection ?: throw ToolException("'${config.name}' isn't a key-value store.")
                val output = try {
                    store.command(int("keyspace", 0, 0..10_000), arguments)
                } catch (e: Exception) {
                    throw ToolException(McpSession.errorText(e))
                }
                JsonPrimitive(ResultEncoder.clip(output, workspace.resultBudget()))
            }
        },
        Tool(
            name = "kv_write",
            title = "Change a key",
            description =
                "Changes one key: set a string, set or delete a hash field, append or remove a list item, add or remove a set " +
                    "member, set a sorted-set score, set the TTL, rename, or delete the key. Needs a connection that allows writes.",
            readOnly = false,
            destructive = true,
            input = params {
                connection()
                string("key", "The key.", required = true)
                string(
                    "operation",
                    "What to do.",
                    required = true,
                    enum = listOf("set", "hset", "hdel", "rpush", "lrem", "sadd", "srem", "zadd", "expire", "persist", "rename", "delete"),
                )
                string("value", "The value: the string for set, the field's value for hset, the item or member otherwise, the new name for rename.")
                string("field", "The hash field for hset and hdel.")
                string("score", "The score for zadd.")
                integer("ttl_seconds", "Seconds to live for expire.", minimum = 1)
                integer("keyspace", "Keyspace index (default 0).", minimum = 0)
            },
        ) {
            val workspace = session.workspace
            val config = workspace.resolve(string("connection"))
            workspace.require(AgentCapability.WriteData, config)
            val key = string("key")
            val operation = string("operation")
            val keyspace = int("keyspace", 0, 0..10_000)
            val removes = operation in setOf("hdel", "lrem", "srem", "delete", "rename")
            if (session.needsConfirmation(config, if (removes) "$operation removes data" else null)) {
                session.confirm(config, "$operation $key ${stringOrNull("field").orEmpty()} ${stringOrNull("value").orEmpty()}".trim(), if (removes) "$operation removes data" else null)
            }
            workspace.use(config) { live ->
                val store = live.connection as? KeyValueConnection ?: throw ToolException("'${config.name}' isn't a key-value store.")
                try {
                    when (operation) {
                        "set" -> store.edit(keyspace, key, KeyEdit.SetText(string("value")))
                        "hset" -> store.edit(keyspace, key, KeyEdit.SetField(string("field"), string("value")))
                        "hdel" -> store.edit(keyspace, key, KeyEdit.DeleteField(string("field")))
                        "rpush" -> store.edit(keyspace, key, KeyEdit.AppendItem(string("value")))
                        "lrem" -> store.edit(keyspace, key, KeyEdit.RemoveItem(string("value")))
                        "sadd" -> store.edit(keyspace, key, KeyEdit.AddMember(string("value")))
                        "srem" -> store.edit(keyspace, key, KeyEdit.RemoveMember(string("value")))
                        "zadd" -> store.edit(keyspace, key, KeyEdit.SetScore(string("value"), string("score").toDoubleOrNull() ?: throw ToolException("'score' must be a number.")))
                        "expire" -> store.expire(keyspace, key, (int("ttl_seconds", -1, 1..Int.MAX_VALUE).takeIf { it > 0 } ?: throw ToolException("'ttl_seconds' is required for expire.")) * 1_000L)
                        "persist" -> store.expire(keyspace, key, null)
                        "rename" -> store.rename(keyspace, key, string("value"))
                        "delete" -> store.delete(keyspace, listOf(key))
                        else -> throw ToolException("Unknown operation '$operation'.")
                    }
                } catch (e: ToolException) {
                    throw e
                } catch (e: Exception) {
                    throw ToolException(McpSession.errorText(e))
                }
                buildJsonObject { put("done", "$operation $key") }
            }
        },
    )

private suspend fun ToolCall.kv(capability: AgentCapability, block: suspend ToolCall.(ConnectionConfig, KeyValueConnection) -> JsonElement): JsonElement {
    val workspace = session.workspace
    val config = workspace.resolve(string("connection"))
    workspace.require(capability, config)
    return workspace.use(config) { live ->
        val store = live.connection as? KeyValueConnection ?: throw ToolException("'${config.name}' is a ${config.type.displayName} connection, not a key-value store; use ${toolsFor(config)}.")
        try {
            block(config, store)
        } catch (e: ToolException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ToolException(McpSession.errorText(e))
        }
    }
}

private fun encode(value: KeyValue): JsonElement =
    buildJsonObject {
        put("key", value.key)
        value.ttlMillis?.let { put("ttlMs", it) }
        when (value) {
            is KeyValue.Text -> {
                put("type", "string")
                put("value", ResultEncoder.clip(value.value, 20_000))
                if (value.truncated) put("truncated", true)
            }
            is KeyValue.Hash -> {
                put("type", "hash")
                put("total", value.total)
                put("fields", buildJsonObject { value.fields.forEach { (field, v) -> put(field, ResultEncoder.clip(v)) } })
            }
            is KeyValue.ListValue -> {
                put("type", "list")
                put("total", value.total)
                put("items", buildJsonArray { value.items.forEach { add(ResultEncoder.cell(it)) } })
            }
            is KeyValue.SetValue -> {
                put("type", "set")
                put("total", value.total)
                put("members", buildJsonArray { value.members.forEach { add(ResultEncoder.cell(it)) } })
            }
            is KeyValue.SortedSet -> {
                put("type", "zset")
                put("total", value.total)
                put("members", buildJsonArray { value.members.forEach { (member, score) -> add(buildJsonObject { put("member", ResultEncoder.clip(member)); put("score", score) }) } })
            }
            is KeyValue.Stream -> {
                put("type", "stream")
                put("total", value.total)
                put(
                    "entries",
                    buildJsonArray {
                        value.entries.forEach { entry ->
                            add(buildJsonObject { put("id", entry.id); put("fields", buildJsonObject { entry.fields.forEach { (f, v) -> put(f, ResultEncoder.clip(v)) } }) })
                        }
                    },
                )
            }
            is KeyValue.Other -> {
                put("type", value.type)
                put("value", ResultEncoder.clip(value.description, 20_000))
            }
        }
    }
