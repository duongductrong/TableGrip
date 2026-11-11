package dev.tablegrip.engines.zookeeper

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.driver.ConsoleReply
import dev.tablegrip.core.driver.Record
import dev.tablegrip.core.driver.RecordClient
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RecordContainer
import dev.tablegrip.core.driver.RecordLanguage
import dev.tablegrip.core.driver.RecordPage
import dev.tablegrip.core.driver.http.JsonTables
import dev.tablegrip.core.driver.withContextClassLoader
import dev.tablegrip.core.i18n.tr
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.Watcher
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooKeeper
import org.apache.zookeeper.data.Stat

/**
 * TableGrip's ZooKeeper adapter over the Apache ZooKeeper client. The host field takes the connect string
 * (`zk1:2181,zk2:2181`, with a chroot after a slash if wanted); a user and password are added as digest credentials.
 * The znode tree is the containers: a znode with children opens on them, one record per child with its data as text.
 * Writes carry the znode's version, so ZooKeeper refuses one made from a stale page.
 */
class ZooKeeperClient : RecordClient {
    override fun connect(config: ConnectionConfig, host: String, port: Int, password: String?): RecordConnection =
        withContextClassLoader(ZooKeeperClient::class.java.classLoader) {
            val connect = if (',' in config.host) config.host.split(',').map { it.trim() }.joinToString(",") { if (':' in it.substringBefore('/')) it else "$it:$port" } else "$host:$port"
            val connected = CountDownLatch(1)
            val zk = ZooKeeper(connect, 15_000, Watcher { if (it.state == Watcher.Event.KeeperState.SyncConnected) connected.countDown() })
            if (!connected.await(15, TimeUnit.SECONDS)) {
                zk.close()
                throw IOException(tr("Couldn't reach {0} at {1}: {2}", "ZooKeeper", connect, "timeout"))
            }
            if (config.user.isNotBlank()) zk.addAuthInfo("digest", "${config.user.trim()}:${password.orEmpty()}".toByteArray())
            Connection(config, zk)
        }

    internal class Connection(override val config: ConnectionConfig, private val zk: ZooKeeper) : RecordConnection {
        private val authenticated = config.user.isNotBlank()

        private suspend fun <T> io(block: () -> T): T =
            withContext(Dispatchers.IO) {
                withContextClassLoader(ZooKeeperClient::class.java.classLoader) {
                    try {
                        block()
                    } catch (e: KeeperException) {
                        throw IOException("ZooKeeper: ${e.message}", e)
                    }
                }
            }

        override suspend fun server(): String =
            io {
                "Apache ZooKeeper (" + tr("session {0}", "0x" + zk.sessionId.toString(16)) + ")"
            }

        override suspend fun containers(parent: List<String>): List<RecordContainer> =
            io {
                if (parent.isEmpty()) return@io listOf(RecordContainer(listOf("/"), "znode", count = zk.exists("/", false)?.numChildren?.toLong(), hasChildren = true))
                val path = znode(parent)
                zk.getChildren(path, false).sorted().mapNotNull { name ->
                    val stat = zk.exists(child(path, name), false) ?: return@mapNotNull null
                    if (stat.numChildren == 0) null else RecordContainer(parent + name, "znode", stat.numChildren.toLong(), hasChildren = true)
                }
            }

        override suspend fun definition(container: List<String>): String =
            io {
                val path = znode(container)
                val stat = Stat()
                val data = zk.getData(path, false, stat)
                JsonTables.pretty(buildJsonObject {
                    put("path", path)
                    put("data", data?.let { text(it) } ?: JsonNull)
                    put("stat", stat(stat))
                    put("acl", kotlinx.serialization.json.JsonArray(zk.getACL(path, Stat()).map { JsonPrimitive("${it.id.scheme}:${it.id.id}:${perms(it.perms)}") }))
                })
            }

        override val queryLanguage =
            RecordLanguage(tr("Name"), tr("Part of a child's name"), tr("Keeps the children of this znode whose name contains the text."))

        override suspend fun page(container: List<String>, query: String, cursor: String?, limit: Int): RecordPage =
            io {
                val path = znode(container)
                val names = zk.getChildren(path, false).filter { it.contains(query.trim(), ignoreCase = true) }.sorted()
                val offset = cursor?.toIntOrNull() ?: 0
                val records = names.drop(offset).take(limit).mapNotNull { name ->
                    val stat = Stat()
                    val data = try {
                        zk.getData(child(path, name), false, stat)
                    } catch (_: KeeperException.NoNodeException) {
                        return@mapNotNull null
                    }
                    Record(
                        buildJsonObject { put("path", child(path, name)); put("version", stat.version) },
                        buildJsonObject { put("name", name); put("data", data?.let { text(it) } ?: JsonNull) },
                        buildJsonObject { put("version", stat.version); put("children", stat.numChildren); put("ephemeral", stat.ephemeralOwner != 0L); put("modified", Instant.ofEpochMilli(stat.mtime).toString()) },
                    )
                }
                RecordPage(records, (offset + limit).takeIf { it < names.size }?.toString(), names.size.toLong())
            }

        override suspend fun template(container: List<String>): JsonObject = buildJsonObject { put("name", ""); put("data", "") }

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            io {
                val parent = znode(container)
                val name = document["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && '/' !in it } ?: throw IOException(tr("A znode needs a name without slashes."))
                val path = child(parent, name)
                val data = document["data"].let { if (it is JsonPrimitive && it.isString) it.content else it?.takeIf { v -> v !is JsonNull }?.toString() }?.toByteArray()
                if (key == null) {
                    try {
                        zk.create(path, data, if (authenticated) ZooDefs.Ids.CREATOR_ALL_ACL else ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT)
                    } catch (_: KeeperException.NodeExistsException) {
                        throw IOException(tr("{0} already exists.", path))
                    }
                    return@io buildJsonObject { put("path", path); put("version", 0) }
                }
                if (key["path"]?.jsonPrimitive?.content != path) throw IOException(tr("A znode can't be renamed here; add a new one instead."))
                val stat = try {
                    zk.setData(path, data, key["version"]!!.jsonPrimitive.content.toInt())
                } catch (_: KeeperException.BadVersionException) {
                    throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", path))
                } catch (_: KeeperException.NoNodeException) {
                    throw IOException(tr("{0} was deleted by someone else since it was read.", path))
                }
                buildJsonObject { put("path", path); put("version", stat.version) }
            }

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            io {
                keys.count { key ->
                    val path = key["path"]!!.jsonPrimitive.content
                    try {
                        zk.delete(path, key["version"]?.jsonPrimitive?.content?.toInt() ?: -1)
                        true
                    } catch (_: KeeperException.NoNodeException) {
                        false
                    } catch (_: KeeperException.BadVersionException) {
                        throw IOException(tr("{0} was changed by someone else since it was read; reload and try again.", path))
                    } catch (_: KeeperException.NotEmptyException) {
                        throw IOException(tr("{0} has children; delete them first.", path))
                    }
                }.toLong()
            }

        override val consoleLanguage =
            RecordLanguage(tr("Commands"), "ls /app", tr("zkCli-style commands: ls <path>, get <path>, stat <path>, create <path> [data], set <path> <data>, delete <path>."))

        override fun sampleStatement(container: List<String>): String = "ls ${if (container.isEmpty()) "/" else znode(container)}"

        override suspend fun execute(statement: String): ConsoleReply =
            io {
                val started = System.currentTimeMillis()
                val text = statement.trim()
                val command = text.substringBefore(' ').lowercase()
                val rest = text.substringAfter(' ', "").trim()
                val path = rest.substringBefore(' ')
                val data = rest.substringAfter(' ', "").takeIf { ' ' in rest }
                if (command !in setOf("ls", "get", "stat", "create", "set", "delete") || !path.startsWith("/")) throw IOException(consoleLanguage.help)
                fun done(reply: String) = ConsoleReply.Text(reply, System.currentTimeMillis() - started)
                when (command) {
                    "ls" -> {
                        val rows = zk.getChildren(path, false).sorted().map { name ->
                            val stat = zk.exists(child(path, name), false)
                            buildJsonObject { put("name", name); put("children", stat?.numChildren); put("bytes", stat?.dataLength); put("version", stat?.version) }
                        }
                        if (rows.isEmpty()) done(tr("No children.")) else ConsoleReply.Table(JsonTables.rows(rows, elapsedMillis = System.currentTimeMillis() - started))
                    }
                    "get" -> done(zk.getData(path, false, null)?.let { String(it) } ?: "null")
                    "stat" -> done(JsonTables.pretty(stat(zk.exists(path, false) ?: throw IOException(tr("{0} doesn't exist.", path)))))
                    "create" -> done(zk.create(path, data?.toByteArray(), if (authenticated) ZooDefs.Ids.CREATOR_ALL_ACL else ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT))
                    "set" -> done(JsonTables.pretty(stat(zk.setData(path, data?.toByteArray(), -1))))
                    else -> {
                        zk.delete(path, -1)
                        done(tr("Done."))
                    }
                }
            }

        override fun isRead(statement: String): Boolean = isReadCommand(statement)

        override fun close() = withContextClassLoader(ZooKeeperClient::class.java.classLoader) { zk.close() }
    }

    companion object {
        internal fun isReadCommand(statement: String): Boolean = statement.trim().substringBefore(' ').lowercase() in setOf("ls", "get", "stat")

        /** A container's znode: `[/]` is the root, `[/, app, config]` is `/app/config`. */
        internal fun znode(container: List<String>): String = "/" + container.drop(1).joinToString("/")

        internal fun child(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

        private fun text(bytes: ByteArray): JsonPrimitive =
            runCatching { JsonPrimitive(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()) }
                .getOrElse { JsonPrimitive("base64:" + java.util.Base64.getEncoder().encodeToString(bytes)) }

        private fun perms(bits: Int) = buildString {
            if (bits and ZooDefs.Perms.CREATE != 0) append('c')
            if (bits and ZooDefs.Perms.DELETE != 0) append('d')
            if (bits and ZooDefs.Perms.READ != 0) append('r')
            if (bits and ZooDefs.Perms.WRITE != 0) append('w')
            if (bits and ZooDefs.Perms.ADMIN != 0) append('a')
        }

        private fun stat(stat: Stat) = buildJsonObject {
            put("version", stat.version)
            put("children", stat.numChildren)
            put("dataLength", stat.dataLength)
            put("ephemeralOwner", "0x" + stat.ephemeralOwner.toString(16))
            put("created", Instant.ofEpochMilli(stat.ctime).toString())
            put("modified", Instant.ofEpochMilli(stat.mtime).toString())
        }
    }
}
