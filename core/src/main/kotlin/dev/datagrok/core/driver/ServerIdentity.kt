package dev.datagrok.core.driver

import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.EngineFamily
import java.sql.Connection

/**
 * Which engine a server really is, once connected: servers in a family share a wire protocol, so the profile's type may
 * be a guess (a pasted `postgres://` URL that points at CockroachDB, a `mysql://` one at TiDB). Only the engines it can
 * tell apart are named; hosted variants (Neon, Supabase, PlanetScale, Azure SQL) are never second-guessed.
 */
object ServerIdentity {
    private val identifiable =
        setOf(DatabaseType.PostgreSQL, DatabaseType.CockroachDB, DatabaseType.TimescaleDB, DatabaseType.MySQL, DatabaseType.MariaDB, DatabaseType.TiDB)

    /** The engine to suggest instead of [type], or null if [type] is right or can't be judged. */
    fun suggest(connection: Connection, type: DatabaseType): DatabaseType? {
        if (type !in identifiable) return null
        val detected = runCatching { identify(connection, type.family) }.getOrNull() ?: return null
        return detected.takeIf { it != type }
    }

    fun identify(connection: Connection, family: EngineFamily): DatabaseType? =
        when (family) {
            EngineFamily.PostgreSQL -> {
                val version = connection.createStatement().use { s -> s.executeQuery("SELECT version()").use { rs -> rs.next(); rs.getString(1) } }
                when {
                    "CockroachDB" in version -> DatabaseType.CockroachDB
                    timescale(connection) -> DatabaseType.TimescaleDB
                    else -> DatabaseType.PostgreSQL
                }
            }
            EngineFamily.MySQL -> {
                val version = connection.metaData.databaseProductVersion.orEmpty()
                when {
                    "TiDB" in version -> DatabaseType.TiDB
                    "MariaDB" in version || connection.metaData.databaseProductName.orEmpty().contains("MariaDB") -> DatabaseType.MariaDB
                    else -> DatabaseType.MySQL
                }
            }
            else -> null
        }

    private fun timescale(connection: Connection): Boolean =
        connection.createStatement().use { s -> s.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'timescaledb'").use { it.next() } }
}
