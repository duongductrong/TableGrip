package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.ClientApi
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.SshTunnel
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.i18n.tr
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Opens [RecordConnection]s: the profile's client, through its SSH tunnel, with read-only enforced here. */
object RecordStore {
    suspend fun connect(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): RecordConnection =
        withContext(Dispatchers.IO) {
            require(config.type.api == ClientApi.Records) { tr("{0} isn't browsed as records", config.type.displayName) }
            val client = drivers.loadClient(config.effectiveDriver) as RecordClient
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

    /** Applies the profile's read-only setting, and closes the tunnel with the connection. */
    private class Guarded(private val inner: RecordConnection, private val readOnly: Boolean, private val onClose: () -> Unit) : RecordConnection by inner {
        private fun refuse(what: String): Nothing = throw ReadOnlyViolationException(what)

        override fun canWrite(container: List<String>): Boolean = !readOnly && inner.canWrite(container)

        override suspend fun put(container: List<String>, key: JsonObject?, document: JsonObject): JsonObject =
            if (readOnly) refuse(if (key == null) tr("adding records") else tr("changing records")) else inner.put(container, key, document)

        override suspend fun delete(container: List<String>, keys: List<JsonObject>): Long =
            if (readOnly) refuse(tr("deleting records")) else inner.delete(container, keys)

        override suspend fun execute(statement: String): ConsoleReply =
            if (readOnly && !inner.isRead(statement)) refuse(tr("this statement")) else inner.execute(statement)

        override fun close() {
            try {
                inner.close()
            } finally {
                onClose()
            }
        }
    }
}
