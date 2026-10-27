package dev.datagrok.core.connection

import java.net.URLEncoder
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
import kotlinx.serialization.Serializable

/** The JDBC drivers bundled with Datagrok. */
@Serializable
enum class DriverId(val displayName: String, val className: String, val scheme: String) {
    PostgreSQL("PostgreSQL JDBC Driver", "org.postgresql.Driver", "jdbc:postgresql:"),
    MySQL("MySQL Connector/J", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:"),
    MariaDB("MariaDB Connector/J", "org.mariadb.jdbc.Driver", "jdbc:mariadb:"),
    SQLite("SQLite JDBC (Xerial)", "org.sqlite.JDBC", "jdbc:sqlite:");

    /**
     * The driver instance, loaded by class name. Using it directly (instead of [java.sql.DriverManager]) guarantees
     * the chosen driver handles the URL even when several accept the same scheme.
     */
    fun load(): Driver = instances.getOrPut(this) { Class.forName(className).getDeclaredConstructor().newInstance() as Driver }

    /** e.g. "3.5" for MariaDB Connector/J 3.5.x; null if the driver can't be loaded. */
    val version: String?
        get() = runCatching { load().let { "${it.majorVersion}.${it.minorVersion}" } }.getOrNull()

    companion object {
        private val instances = java.util.concurrent.ConcurrentHashMap<DriverId, Driver>()

        /** The driver whose own scheme a JDBC URL uses. */
        fun forUrl(url: String): DriverId? = entries.firstOrNull { url.startsWith(it.scheme) }
    }
}

/** Builds JDBC URLs and driver properties for a [ConnectionConfig], following each driver's documented syntax. */
object JdbcUrls {

    /**
     * The URL to connect with. [via] replaces the host and port, e.g. with an SSH tunnel's local end. For a profile
     * with a raw [ConnectionConfig.url], only a single `//host[:port]` URL can be redirected.
     */
    fun build(config: ConnectionConfig, via: Pair<String, Int>? = null): String {
        config.url?.let { raw -> return adaptToDriver(if (via == null) raw else redirect(raw, via.first, via.second), config.effectiveDriver) }
        val driver = config.effectiveDriver
        if (driver == DriverId.SQLite) {
            // A private in-memory database would vanish per connection; share it across the pool instead.
            return if (config.database == ":memory:" || config.database.isBlank()) "jdbc:sqlite:file:datagrok-${config.id}?mode=memory&cache=shared"
            else "jdbc:sqlite:${config.database}"
        }
        val hosts =
            if (via != null) formatHost(via.first, via.second)
            else
                config.host
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf("localhost") }
                    .joinToString(",") { entry -> if (hasPort(entry)) entry else formatHost(entry, config.port) }
        return "${driver.scheme}//$hosts/${encodePathSegment(config.database)}"
    }

    /**
     * Driver properties: the user's, plus credentials and sensible defaults the user didn't set. Translates SSL
     * settings between the MySQL and MariaDB vocabularies so a profile keeps working when its driver is switched.
     */
    fun properties(config: ConnectionConfig, password: String?): Properties {
        val driver = config.effectiveDriver
        val props = Properties()
        config.properties.forEach { (k, v) -> props.setProperty(k, v) }
        if (config.user.isNotBlank()) props.setProperty("user", config.user)
        if (!password.isNullOrEmpty()) props.setProperty("password", password)
        when (driver) {
            DriverId.MySQL -> {
                // Connector/J waits forever by default; fail like the other drivers do.
                props.putIfAbsent("connectTimeout", "10000")
                props.getProperty("sslMode")?.let { props.setProperty("sslMode", mariaToMysqlSslMode[it.lowercase()] ?: it) }
            }
            DriverId.MariaDB -> {
                props.putIfAbsent("connectTimeout", "10000")
                props.getProperty("sslMode")?.let { props.setProperty("sslMode", mysqlToMariaSslMode[it.uppercase()] ?: it) }
            }
            DriverId.PostgreSQL,
            DriverId.SQLite -> Unit
        }
        return props
    }

    private val mysqlToMariaSslMode =
        mapOf("DISABLED" to "disable", "PREFERRED" to "trust", "REQUIRED" to "trust", "VERIFY_CA" to "verify-ca", "VERIFY_IDENTITY" to "verify-full")
    private val mariaToMysqlSslMode =
        mapOf("disable" to "DISABLED", "trust" to "REQUIRED", "verify-ca" to "VERIFY_CA", "verify-full" to "VERIFY_IDENTITY", "allow" to "PREFERRED")

    /**
     * Makes a raw URL acceptable to the chosen driver: MariaDB Connector/J 3.x only takes `jdbc:mysql:` URLs that carry
     * `permitMysqlScheme` (it checks the URL itself, as does HikariCP), and Connector/J only takes `jdbc:mysql:`.
     */
    private fun adaptToDriver(url: String, driver: DriverId): String =
        when {
            driver == DriverId.MariaDB && url.startsWith(DriverId.MySQL.scheme) && "permitMysqlScheme" !in url ->
                url + (if ('?' in url) "&" else "?") + "permitMysqlScheme"
            driver == DriverId.MySQL && url.startsWith("${DriverId.MariaDB.scheme}//") -> DriverId.MySQL.scheme + url.removePrefix(DriverId.MariaDB.scheme)
            else -> url
        }

    fun formatHost(host: String, port: Int?): String {
        val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return if (port == null) bracketed else "$bracketed:$port"
    }

    private fun hasPort(entry: String): Boolean =
        if (entry.startsWith("[")) entry.substringAfter(']').startsWith(":") else entry.count { it == ':' } == 1

    private fun encodePathSegment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    /** Points a `scheme//host[:port]/...` URL at another host and port; rejects multi-host and key-value forms. */
    fun redirect(url: String, host: String, port: Int): String {
        val start = url.indexOf("//").takeIf { it >= 0 } ?: throw SQLException("Can't tunnel this URL: no //host part.")
        val authorityStart = start + 2
        val end = url.indexOfAny(charArrayOf('/', '?'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, end)
        val userInfo = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
        val hostPart = authority.substringAfterLast('@')
        if (',' in hostPart || '(' in hostPart || hostPart.startsWith("address=")) {
            throw SQLException("SSH tunnels need a single-host URL; this one lists several hosts.")
        }
        return url.substring(0, authorityStart) + userInfo + formatHost(host, port) + url.substring(end)
    }
}
