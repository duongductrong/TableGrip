package dev.datagrok.core.connection

import java.net.URLEncoder
import java.sql.SQLException
import java.util.Properties
import kotlinx.serialization.Serializable

/**
 * The JDBC drivers Datagrok knows. None ships with the app: [catalogId] names the entry in the driver catalog it can
 * download (see [dev.datagrok.core.drivers.DriverLibrary]); drivers without one (GPL or a vendor's license) are only
 * used from jars the user supplies, which they get from [vendorUrl] under [license].
 */
@Serializable
enum class DriverId(
    val displayName: String,
    val className: String,
    val scheme: String,
    val catalogId: String? = null,
    val vendorUrl: String? = null,
    val license: String? = null,
    /** For non-JDBC clients: the name of Datagrok's adapter jar that drives this client library. */
    val adapter: String? = null,
) {
    PostgreSQL("PostgreSQL JDBC Driver", "org.postgresql.Driver", "jdbc:postgresql:", catalogId = "postgresql"),
    MySQL(
        "MySQL Connector/J",
        "com.mysql.cj.jdbc.Driver",
        "jdbc:mysql:",
        vendorUrl = "https://dev.mysql.com/downloads/connector/j/",
        license = "GPL-2.0 with Universal FOSS Exception",
    ),
    MariaDB("MariaDB Connector/J", "org.mariadb.jdbc.Driver", "jdbc:mariadb:", catalogId = "mariadb"),
    SQLite("SQLite JDBC (Xerial)", "org.sqlite.JDBC", "jdbc:sqlite:", catalogId = "sqlite"),
    H2("H2 Database Engine", "org.h2.Driver", "jdbc:h2:", catalogId = "h2"),
    HSQLDB("HyperSQL (HSQLDB)", "org.hsqldb.jdbc.JDBCDriver", "jdbc:hsqldb:", catalogId = "hsqldb"),
    Derby("Apache Derby (embedded)", "org.apache.derby.iapi.jdbc.AutoloadedDriver", "jdbc:derby:", catalogId = "derby"),
    SqlServer("Microsoft JDBC Driver for SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", catalogId = "sqlserver"),
    ClickHouse("ClickHouse JDBC", "com.clickhouse.jdbc.Driver", "jdbc:clickhouse:", catalogId = "clickhouse"),
    DuckDB("DuckDB JDBC", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", catalogId = "duckdb"),

    /**
     * Not JDBC: the Lettuce Redis client, driven by Datagrok's own adapter ([adapter], bundled with the app) loaded
     * together with it. [className] is the adapter's entry point.
     */
    Lettuce("Lettuce (Redis client)", "dev.datagrok.engines.redis.RedisClient", "redis:", catalogId = "lettuce", adapter = "redis"),

    /** Whatever driver the profile's own jars contain; its class is found in them. */
    Generic("JDBC driver from your jars", "", "jdbc:");

    companion object {
        /** The driver whose own scheme a JDBC URL uses. */
        fun forUrl(url: String): DriverId? = entries.firstOrNull { it != Generic && url.startsWith(it.scheme) }
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
        if (config.type.connection == ConnectionKind.Url) throw SQLException("${config.type.displayName} connects by JDBC URL only.")
        if (config.type.isFile) return fileUrl(config, driver)
        val hosts =
            if (via != null) formatHost(via.first, via.second)
            else
                config.host
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf("localhost") }
                    .joinToString(",") { entry -> if (hasPort(entry)) entry else formatHost(entry, config.port) }
        return when (driver) {
            // Attributes follow `;`; the database is one of them.
            DriverId.SqlServer -> "${driver.scheme}//$hosts" + (config.database.takeIf { it.isNotBlank() }?.let { ";databaseName=${it.replace(";", "{;}")}" } ?: "")
            else -> "${driver.scheme}//$hosts/${encodePathSegment(config.database)}"
        }
    }

    /**
     * Driver properties: the user's, plus credentials and sensible defaults the user didn't set. Translates SSL
     * settings between the MySQL and MariaDB vocabularies so a profile keeps working when its driver is switched.
     */
    fun properties(config: ConnectionConfig, password: String?): Properties {
        val driver = config.effectiveDriver
        val props = Properties()
        config.properties.forEach { (k, v) -> props.setProperty(k, v) }
        config.type.defaultProperties[driver]?.forEach { (k, v) -> props.putIfAbsent(k, v) }
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
            else -> Unit
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

    /**
     * Embedded databases. In-memory ones would vanish with each pooled connection, so they're kept alive for the
     * profile's lifetime: SQLite through a shared cache, H2 by not closing on the last connection.
     */
    private fun fileUrl(config: ConnectionConfig, driver: DriverId): String {
        val path = config.database.trim().let { if (it.startsWith("~/")) System.getProperty("user.home") + it.substring(1) else it }
        val memory = path.isEmpty() || path == ":memory:" || path.startsWith("mem:")
        val memoryName = path.removePrefix("mem:").takeIf { it.isNotEmpty() && it != ":memory:" } ?: "datagrok-${config.id}"
        return when (driver) {
            DriverId.SQLite -> if (memory) "jdbc:sqlite:file:datagrok-${config.id}?mode=memory&cache=shared" else "jdbc:sqlite:$path"
            DriverId.H2 -> if (memory) "jdbc:h2:mem:$memoryName;DB_CLOSE_DELAY=-1" else "jdbc:h2:file:$path"
            DriverId.HSQLDB -> if (memory) "jdbc:hsqldb:mem:$memoryName" else "jdbc:hsqldb:file:$path"
            DriverId.Derby -> if (memory) "jdbc:derby:memory:$memoryName;create=true" else "jdbc:derby:$path"
            // A named in-memory database is shared by every connection of the process, so the pool sees one database.
            DriverId.DuckDB -> if (memory) "jdbc:duckdb::memory:${memoryName.replace(Regex("[^A-Za-z0-9_]"), "_")}" else "jdbc:duckdb:$path"
            else -> throw SQLException("${driver.displayName} has no file URL form; connect by JDBC URL.")
        }
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
        val end = url.indexOfAny(charArrayOf('/', '?', ';'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, end)
        val userInfo = authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }
        val hostPart = authority.substringAfterLast('@')
        if (',' in hostPart || '(' in hostPart || hostPart.startsWith("address=")) {
            throw SQLException("SSH tunnels need a single-host URL; this one lists several hosts.")
        }
        return url.substring(0, authorityStart) + userInfo + formatHost(host, port) + url.substring(end)
    }
}
