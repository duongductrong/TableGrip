package dev.datagrok.core.connection

import kotlinx.serialization.Serializable

/** Database engines supported out of the box. */
@Serializable
enum class DatabaseType(val displayName: String, val defaultPort: Int?) {
    PostgreSQL("PostgreSQL", 5432),
    MySQL("MySQL", 3306),
    SQLite("SQLite", null);

    fun jdbcUrl(config: ConnectionConfig): String =
        when (this) {
            PostgreSQL -> "jdbc:postgresql://${config.host}:${config.port ?: defaultPort}/${config.database}"
            MySQL -> "jdbc:mysql://${config.host}:${config.port ?: defaultPort}/${config.database}"
            SQLite -> "jdbc:sqlite:${config.database}"
        }
}

/** A saved connection profile. The password is not part of it: it lives in a [SecretStore]. */
@Serializable
data class ConnectionConfig(
    val id: String,
    val name: String,
    val type: DatabaseType,
    val host: String = "localhost",
    val port: Int? = null,
    val database: String = "",
    val user: String = "",
)
