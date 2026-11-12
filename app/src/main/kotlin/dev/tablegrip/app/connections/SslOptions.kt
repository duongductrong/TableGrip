package dev.tablegrip.app.connections

import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.i18n.tr

/** How strictly the connection uses TLS, in words common to every driver; [SslSpec] maps it to the driver's own. */
internal enum class SslMode(val label: () -> String) {
    Off({ tr("Off") }),
    Prefer({ tr("Prefer: TLS when the server offers it") }),
    Require({ tr("Require: encrypt, don't verify the server") }),
    VerifyCa({ tr("Verify CA: the server's certificate is trusted") }),
    VerifyFull({ tr("Verify full: trusted, and names this host") }),
}

/**
 * A driver's TLS properties: the property naming the [mode][SslMode] and its value for each mode it supports, and the
 * properties taking PEM files: the CA to trust, and a client certificate and key. Null keys aren't offered.
 */
internal class SslSpec(
    val modeKey: String,
    val modes: Map<SslMode, String>,
    val caKey: String? = null,
    val certKey: String? = null,
    val keyKey: String? = null,
) {
    val keys: List<String> = listOfNotNull(modeKey, caKey, certKey, keyKey)

    fun modeOf(value: String): SslMode? = modes.entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }?.key
}

/** Each driver's documented names: pgjdbc's libpq-style ones, MariaDB Connector/J 3, and MySQL Connector/J 8. */
internal val SslSpecs: Map<DriverId, SslSpec> =
    mapOf(
        DriverId.PostgreSQL to
            SslSpec(
                "sslmode",
                mapOf(SslMode.Off to "disable", SslMode.Prefer to "prefer", SslMode.Require to "require", SslMode.VerifyCa to "verify-ca", SslMode.VerifyFull to "verify-full"),
                caKey = "sslrootcert",
                certKey = "sslcert",
                keyKey = "sslkey",
            ),
        DriverId.MariaDB to
            SslSpec(
                "sslMode",
                mapOf(SslMode.Off to "disable", SslMode.Require to "trust", SslMode.VerifyCa to "verify-ca", SslMode.VerifyFull to "verify-full"),
                caKey = "serverSslCert",
            ),
        // Connector/J reads certificates only from Java key stores, so it gets the mode alone.
        DriverId.MySQL to
            SslSpec(
                "sslMode",
                mapOf(SslMode.Off to "DISABLED", SslMode.Prefer to "PREFERRED", SslMode.Require to "REQUIRED", SslMode.VerifyCa to "VERIFY_CA", SslMode.VerifyFull to "VERIFY_IDENTITY"),
            ),
    )

/** What the SSL tab holds: null mode leaves TLS to the driver (and the engine's defaults), blank paths are unset. */
internal data class SslSettings(val mode: SslMode? = null, val ca: String = "", val cert: String = "", val key: String = "") {
    val isSet: Boolean
        get() = mode != null || ca.isNotBlank() || cert.isNotBlank() || key.isNotBlank()

    /** The driver properties these settings stand for, keyed by [spec]'s names. */
    fun toProperties(spec: SslSpec): Map<String, String> =
        buildMap {
            mode?.let { spec.modes[it] }?.let { put(spec.modeKey, it) }
            spec.caKey?.let { k -> ca.trim().takeIf { it.isNotEmpty() }?.let { put(k, it) } }
            spec.certKey?.let { k -> cert.trim().takeIf { it.isNotEmpty() }?.let { put(k, it) } }
            spec.keyKey?.let { k -> key.trim().takeIf { it.isNotEmpty() }?.let { put(k, it) } }
        }

    companion object {
        /**
         * Splits [properties] into the TLS settings [spec] understands and everything else. A mode value the spec doesn't
         * know (e.g. MariaDB's `sslMode=trust` spelled differently) stays among the other properties, untouched.
         */
        fun extract(spec: SslSpec, properties: Map<String, String>): Pair<SslSettings, Map<String, String>> {
            val mode = properties[spec.modeKey]?.let(spec::modeOf)
            val taken = setOfNotNull(spec.modeKey.takeIf { mode != null }, spec.caKey, spec.certKey, spec.keyKey)
            val settings =
                SslSettings(
                    mode = mode,
                    ca = spec.caKey?.let(properties::get).orEmpty(),
                    cert = spec.certKey?.let(properties::get).orEmpty(),
                    key = spec.keyKey?.let(properties::get).orEmpty(),
                )
            return settings to properties.filterKeys { it !in taken }
        }
    }
}
