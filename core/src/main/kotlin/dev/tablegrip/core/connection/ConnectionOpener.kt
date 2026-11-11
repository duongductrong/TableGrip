package dev.tablegrip.core.connection

import dev.tablegrip.core.datafile.DataFileStore
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.driver.DatabaseConnection
import dev.tablegrip.core.driver.DocumentStore
import dev.tablegrip.core.driver.KeyValueStore
import dev.tablegrip.core.drivers.DriverLibrary

/**
 * Opens a live connection of the kind [config] needs: a data file's working copy, a key-value or document client, or a
 * pooled JDBC [Database]. The driver must already be installed; callers decide whether to download it first.
 */
object ConnectionOpener {
    suspend fun open(
        config: ConnectionConfig,
        password: suspend () -> String?,
        sshSecret: suspend () -> String?,
        drivers: DriverLibrary = DriverLibrary.shared,
    ): DatabaseConnection {
        val ssh = if (config.ssh != null) sshSecret() else null
        return when {
            config.type.isDataFile -> DataFileStore.connect(config, drivers)
            config.type.paradigm == Paradigm.KeyValue -> KeyValueStore.connect(config, password(), ssh, drivers)
            config.type.paradigm == Paradigm.Document -> DocumentStore.connect(config, password(), ssh, drivers)
            else -> Database.connect(config, password(), ssh, drivers)
        }
    }
}
