package dev.datagrok.app.connections

import androidx.compose.runtime.mutableStateMapOf
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.driver.Database
import dev.datagrok.core.driver.RelationalConnection
import dev.datagrok.core.drivers.DriverNotInstalledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory

sealed interface ConnectionStatus {
    data object Disconnected : ConnectionStatus

    data object Connecting : ConnectionStatus

    data class Connected(val database: RelationalConnection) : ConnectionStatus

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
) {
    private val statuses = mutableStateMapOf<String, ConnectionStatus>()
    private val pending = mutableMapOf<String, Deferred<RelationalConnection>>()

    fun status(id: String): ConnectionStatus = statuses[id] ?: ConnectionStatus.Disconnected

    /**
     * Returns the live database, connecting first if needed. Concurrent callers share one attempt, and cancelling a
     * caller doesn't abort it.
     */
    suspend fun database(config: ConnectionConfig): RelationalConnection {
        (statuses[config.id] as? ConnectionStatus.Connected)?.let { return it.database }
        val attempt =
            pending.getOrPut(config.id) {
                scope.async {
                    statuses[config.id] = ConnectionStatus.Connecting
                    try {
                        drivers.ensure(config)
                        val secret = if (config.ssh != null) sshSecret(config.id) else null
                        Database.connect(config, password(config.id), secret, drivers.library).also {
                            statuses[config.id] = ConnectionStatus.Connected(it)
                            log.info("Connected to {} ({})", config.name, config.type)
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

    fun disconnect(id: String) {
        (statuses.remove(id) as? ConnectionStatus.Connected)?.database?.close()
    }

    fun closeAll() = statuses.keys.toList().forEach(::disconnect)

    companion object {
        private val log = LoggerFactory.getLogger(DatabaseRegistry::class.java)
    }
}
