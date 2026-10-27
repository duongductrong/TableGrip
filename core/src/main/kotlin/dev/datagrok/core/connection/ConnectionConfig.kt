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
            // A private in-memory database would vanish per connection; share it across the pool instead.
            SQLite ->
                if (config.database == ":memory:") "jdbc:sqlite:file:datagrok-${config.id}?mode=memory&cache=shared"
                else "jdbc:sqlite:${config.database}"
        }
}

/** Where a database lives; drives the connection's colour and how cautious the UI is. */
@Serializable
enum class Environment(val label: String) {
    None("None"),
    Local("Local"),
    Development("Development"),
    Staging("Staging"),
    Production("Production"),
}

@Serializable
enum class SshAuth { Password, PrivateKey }

/**
 * An SSH tunnel to reach the database through a bastion host. Its password or key passphrase lives in the
 * [SecretStore], like the database password.
 */
@Serializable
data class SshConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: SshAuth = SshAuth.PrivateKey,
    /** Null uses the default keys in `~/.ssh`. */
    val privateKeyPath: String? = null,
)

/** A saved connection profile. Passwords are not part of it: they live in a [SecretStore]. */
@Serializable
data class ConnectionConfig(
    val id: String,
    val name: String,
    val type: DatabaseType,
    val host: String = "localhost",
    val port: Int? = null,
    val database: String = "",
    val user: String = "",
    val environment: Environment = Environment.None,
    /** Rejects writes and DDL, client-side and (where the driver supports it) on the connection itself. */
    val readOnly: Boolean = false,
    /** Asks before running statements that change data or schema. */
    val confirmWrites: Boolean = false,
    val ssh: SshConfig? = null,
)
