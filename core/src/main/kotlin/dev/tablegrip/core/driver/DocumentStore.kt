package dev.tablegrip.core.driver

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.connection.SshTunnel
import dev.tablegrip.core.drivers.DriverLibrary
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens [DocumentConnection]s: the profile's client adapter, through its SSH tunnel, with read-only enforced here. */
object DocumentStore {
    suspend fun connect(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): DocumentConnection =
        withContext(Dispatchers.IO) {
            require(config.type.paradigm == Paradigm.Document) { tr("{0} isn't a document store", config.type.displayName) }
            val client = drivers.loadClient(config.effectiveDriver) as DocumentClient
            if (config.ssh != null && config.url != null) throw SQLException(tr("SSH tunnels need host and port, not a connection string."))
            val port = config.port ?: config.type.defaultPort ?: throw SQLException(tr("{0} needs a port.", config.type.displayName))
            val tunnel = config.ssh?.let { ssh -> SshTunnel.open(ssh, sshSecret, config.host.trim(), port) }
            try {
                val host = if (tunnel != null) "127.0.0.1" else config.host.trim().ifEmpty { "localhost" }
                Guarded(client.connect(config, host, tunnel?.localPort ?: port, password), config.readOnly) { tunnel?.close() }
            } catch (e: Throwable) {
                tunnel?.close()
                throw e
            }
        }

    /** Connects and disconnects, e.g. for "Test Connection"; returns the server's description. */
    suspend fun test(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): String =
        connect(config, password, sshSecret, drivers).use { it.server() }

    private val CollectionCall = Regex("""^db\s*\.\s*(?:getCollection\s*\(\s*(['"]).*?\1\s*\)|[A-Za-z_$][\w$]*)\s*\.\s*([A-Za-z]+)\s*\(""")
    private val DatabaseCall = Regex("""^db\s*\.\s*([A-Za-z]+)\s*\(""")
    private val FirstKey = Regex("""^\{\s*['"]?([A-Za-z]+)""")

    private val ReadMethods =
        setOf("find", "findOne", "countDocuments", "estimatedDocumentCount", "count", "distinct", "aggregate", "getIndexes", "stats", "explain")
    private val ReadDatabaseMethods = setOf("getCollectionNames", "getCollectionInfos", "stats", "getName", "version", "serverStatus", "hello")
    private val ReadCommands =
        setOf(
            "find", "aggregate", "count", "distinct", "listCollections", "listDatabases", "listIndexes", "dbStats", "collStats",
            "serverStatus", "buildInfo", "hello", "isMaster", "ping", "explain", "connectionStatus", "hostInfo", "getParameter",
        )

    /**
     * Whether a console [line] only reads, which is what a read-only profile may run. Anything it doesn't recognise
     * counts as a write: a missing read costs a little convenience, a missing write would cost data. Pipelines that
     * write (`$out`, `$merge`) are writes.
     */
    fun isRead(line: String): Boolean {
        val text = line.trim().removeSuffix(";").trim()
        if (Regex("""^(show\s+\w+|use\s+\S+|it|help)$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if ("\$out" in text || "\$merge" in text) return false
        CollectionCall.find(text)?.let { return it.groupValues[2] in ReadMethods }
        val method = DatabaseCall.find(text)?.groupValues?.get(1) ?: return false
        if (method in ReadDatabaseMethods) return true
        if (method == "runCommand" || method == "adminCommand") {
            val argument = text.substringAfter('(').trim()
            return FirstKey.find(argument)?.groupValues?.get(1) in ReadCommands
        }
        return false
    }

    /** Applies the profile's read-only setting, and closes the tunnel with the connection. */
    private class Guarded(private val inner: DocumentConnection, private val readOnly: Boolean, private val onClose: () -> Unit) :
        DocumentConnection by inner {
        private fun refuse(what: String): Nothing = throw ReadOnlyViolationException(what)

        override suspend fun insert(database: String, collection: String, document: String): String =
            if (readOnly) refuse(tr("inserting documents")) else inner.insert(database, collection, document)

        override suspend fun replace(database: String, collection: String, id: String, document: String, original: String?) =
            if (readOnly) refuse(tr("changing documents")) else inner.replace(database, collection, id, document, original)

        override suspend fun update(database: String, collection: String, id: String, set: Map<String, String>, unset: Set<String>, expected: Map<String, String?>): Boolean =
            if (readOnly) refuse(tr("changing documents")) else inner.update(database, collection, id, set, unset, expected)

        override suspend fun delete(database: String, collection: String, ids: List<String>): Long =
            if (readOnly) refuse(tr("deleting documents")) else inner.delete(database, collection, ids)

        override suspend fun command(database: String, line: String): String =
            if (readOnly && !isRead(line)) refuse(tr("this command")) else inner.command(database, line)

        override suspend fun shell(database: String, line: String): ShellReply =
            if (readOnly && !isRead(line)) refuse(tr("this command")) else inner.shell(database, line)

        override fun close() {
            try {
                inner.close()
            } finally {
                onClose()
            }
        }
    }
}
