package dev.tablegrip.app.connections

import dev.tablegrip.core.i18n.tr
import androidx.compose.runtime.mutableStateMapOf
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionOpener
import dev.tablegrip.core.datafile.DataFileConnection
import dev.tablegrip.core.driver.DatabaseConnection
import dev.tablegrip.core.driver.DocumentConnection
import dev.tablegrip.core.driver.KeyValueConnection
import dev.tablegrip.core.driver.RecordConnection
import dev.tablegrip.core.driver.RelationalConnection
import dev.tablegrip.core.drivers.DriverNotInstalledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus

    /** [since] is the attempt's start in epoch millis, so the UI can say how long it has been trying. */
    data class Connecting(val step: Step, val since: Long) : ConnectionStatus {
        enum class Step {
            /** Downloading or loading the driver. */
            Driver,

            /** Opening the SSH tunnel (if any) and signing in to the server. */
            Server,
        }
    }

    data class Connected(val database: DatabaseConnection) : ConnectionStatus

    data class Failed(val message: String) : ConnectionStatus
}

/** Live connections by connection id. Must be used from the UI thread. */
class DatabaseRegistry(
    private val scope: CoroutineScope,
    private val password: suspend (String) -> String?,
    private val sshSecret: suspend (String) -> String?,
    private val drivers: DriverDownloads,
    /** Called when a connection fails because its driver isn't on this machine, so the user can be asked for it. */
    private val onMissingDriver: (ConnectionConfig) -> Unit = {},
    /** Called on the UI thread when a data file was read in again after it changed on disk, so views can refresh. */
    private val onDataFileReloaded: (ConnectionConfig) -> Unit = {},
    /** Called on the UI thread each time a connection opens, e.g. to list it among the recent ones. */
    private val onConnected: (ConnectionConfig) -> Unit = {},
) {
    private val statuses = mutableStateMapOf<String, ConnectionStatus>()
    private val pending = mutableMapOf<String, Deferred<DatabaseConnection>>()
    private val watchers = mutableMapOf<String, Job>()

    fun status(id: String): ConnectionStatus = statuses[id] ?: ConnectionStatus.Disconnected

    /**
     * Returns the live database, connecting first if needed. Concurrent callers share one attempt, and cancelling a
     * caller doesn't abort it.
     */
    /** The live relational connection; fails for profiles of other kinds. */
    suspend fun database(config: ConnectionConfig): RelationalConnection =
        connection(config) as? RelationalConnection ?: throw IllegalStateException(tr("{0} has no tables", config.type.displayName))

    /** The live key-value connection; fails for profiles of other kinds. */
    suspend fun keyValue(config: ConnectionConfig): KeyValueConnection =
        connection(config) as? KeyValueConnection ?: throw IllegalStateException(tr("{0} isn't a key-value store", config.type.displayName))

    /** The live document-store connection; fails for profiles of other kinds. */
    suspend fun documents(config: ConnectionConfig): DocumentConnection =
        connection(config) as? DocumentConnection ?: throw IllegalStateException(tr("{0} isn't a document store", config.type.displayName))

    /** The live record-store connection (search, wide-column, graph…); fails for profiles of other kinds. */
    suspend fun records(config: ConnectionConfig): RecordConnection =
        connection(config) as? RecordConnection ?: throw IllegalStateException(tr("{0} isn't browsed as records", config.type.displayName))

    suspend fun connection(config: ConnectionConfig): DatabaseConnection {
        (statuses[config.id] as? ConnectionStatus.Connected)?.let { return it.database }
        val attempt =
            pending.getOrPut(config.id) {
                scope.async {
                    val since = System.currentTimeMillis()
                    statuses[config.id] = ConnectionStatus.Connecting(ConnectionStatus.Connecting.Step.Driver, since)
                    try {
                        drivers.ensure(config)
                        statuses[config.id] = ConnectionStatus.Connecting(ConnectionStatus.Connecting.Step.Server, since)
                        val connection = ConnectionOpener.open(config, { password(config.id) }, { sshSecret(config.id) }, drivers.library)
                        (connection as? DataFileConnection)?.let(::watch)
                        connection.also {
                            statuses[config.id] = ConnectionStatus.Connected(it)
                            log.info("Connected to {} ({})", config.name, config.type)
                            onConnected(config)
                        }
                    } catch (e: DriverNotInstalledException) {
                        statuses[config.id] = ConnectionStatus.Failed(e.message ?: e.toString())
                        onMissingDriver(config)
                        throw e
                    } catch (e: Throwable) {
                        statuses[config.id] = ConnectionStatus.Failed(e.message ?: e.toString())
                        log.warn("Failed to connect to {}", config.name, e)
                        throw e
                    } finally {
                        pending.remove(config.id)
                    }
                }
            }
        return attempt.await()
    }

    /** The live data file connection of [id], or null when it isn't one or isn't connected. */
    fun dataFile(id: String): DataFileConnection? = (statuses[id] as? ConnectionStatus.Connected)?.database as? DataFileConnection

    /** Tells views when the file was read in again, until the connection is closed. */
    private fun watch(connection: DataFileConnection) {
        val id = connection.config.id
        watchers.remove(id)?.cancel()
        watchers[id] =
            scope.launch {
                connection.sync.revision.drop(1).collect { if (dataFile(id) === connection) onDataFileReloaded(connection.config) }
            }
    }

    fun disconnect(id: String) {
        watchers.remove(id)?.cancel()
        (statuses.remove(id) as? ConnectionStatus.Connected)?.database?.close()
    }

    fun closeAll() = statuses.keys.toList().forEach(::disconnect)

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseRegistry::class.java)
    }
}
