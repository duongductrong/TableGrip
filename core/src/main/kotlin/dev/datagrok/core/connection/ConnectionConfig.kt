package dev.datagrok.core.connection

import kotlinx.serialization.Serializable

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
    /** JDBC driver to use; null (or one that can't serve [type]) means the type's default. */
    val driver: DriverId? = null,
    /** Extra driver properties, e.g. `sslmode=require` or `allowPublicKeyRetrieval=true`. */
    val properties: Map<String, String> = emptyMap(),
    /**
     * A complete JDBC URL used as-is instead of host/port/database ("connect by URL"), for forms Datagrok doesn't
     * model such as replication or load-balancing URLs. Never contains the password; that's in the [SecretStore].
     */
    val url: String? = null,
    /** The driver jars of a [DriverId.Generic] profile: the user's, kept where they are. */
    val driverJars: List<String> = emptyList(),
) {
    val effectiveDriver: DriverId
        get() = driver?.takeIf { it in type.drivers } ?: type.drivers.first()
}
