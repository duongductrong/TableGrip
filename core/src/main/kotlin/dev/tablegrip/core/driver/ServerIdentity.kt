package dev.tablegrip.core.driver

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.EngineFamily
import java.sql.Connection

/**
 * Which engine a server really is, once connected: servers in a family share a wire protocol, so the profile's type may
 * be a guess (a pasted `postgres://` URL that points at CockroachDB, a `mysql://` one at TiDB). Only the engines it can
 * tell apart are named; hosted variants (Neon, Supabase, PlanetScale, Azure SQL) are never second-guessed.
 */
object ServerIdentity {
    private val identifiable =
        setOf(
            DatabaseType.PostgreSQL, DatabaseType.CockroachDB, DatabaseType.TimescaleDB, DatabaseType.YugabyteDB, DatabaseType.Redshift, DatabaseType.QuestDB,
            DatabaseType.MySQL, DatabaseType.MariaDB, DatabaseType.TiDB, DatabaseType.OceanBase, DatabaseType.Doris, DatabaseType.StarRocks,
            // Kyuubi answers as the engine behind it, so it's taken at its word.
            DatabaseType.Hive, DatabaseType.SparkSql, DatabaseType.Impala,
        )

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
                    // e.g. "PostgreSQL 15.12-YB-2.25.2.0-b0 on aarch64-unknown-linux-gnu".
                    "-YB-" in version -> DatabaseType.YugabyteDB
                    // e.g. "PostgreSQL 8.0.2 on i686-pc-linux-gnu, … Redshift 1.0.77467".
                    "Redshift" in version -> DatabaseType.Redshift
                    "QuestDB" in version -> DatabaseType.QuestDB
                    timescale(connection) -> DatabaseType.TimescaleDB
                    else -> DatabaseType.PostgreSQL
                }
            }
            EngineFamily.MySQL -> {
                val version = connection.metaData.databaseProductVersion.orEmpty()
                // Doris reports a plain MySQL version and names itself only in the comment ("Doris version doris-2.1.7");
                // so does OceanBase through Connector/J, which cuts the version at "5.7.25".
                val comment = scalar(connection, "SELECT @@version_comment").orEmpty()
                when {
                    "TiDB" in version -> DatabaseType.TiDB
                    "OceanBase" in version || "OceanBase" in comment -> DatabaseType.OceanBase
                    "Doris" in comment -> DatabaseType.Doris
                    // StarRocks doesn't name itself anywhere ("8.0.33", comment "4.1.7-b81ff9c"), but only it has this.
                    scalar(connection, "SELECT current_version()") != null -> DatabaseType.StarRocks
                    "MariaDB" in version || connection.metaData.databaseProductName.orEmpty().contains("MariaDB") -> DatabaseType.MariaDB
                    else -> DatabaseType.MySQL
                }
            }
            // What HiveServer2 reports as its DBMS name: "Apache Hive", "Spark SQL", "Impala".
            EngineFamily.Hive -> {
                val product = connection.metaData.databaseProductName.orEmpty()
                when {
                    "Spark" in product -> DatabaseType.SparkSql
                    "Impala" in product -> DatabaseType.Impala
                    "Hive" in product -> DatabaseType.Hive
                    else -> null
                }
            }
            else -> null
        }

    /** The first column of the first row, or null if the server has no such thing. */
    private fun scalar(connection: Connection, sql: String): String? =
        runCatching { connection.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } } }.getOrNull()

    private fun timescale(connection: Connection): Boolean =
        connection.createStatement().use { s -> s.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'timescaledb'").use { it.next() } }
}
