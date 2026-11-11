package dev.tablegrip.core.connection

import dev.tablegrip.core.i18n.tr
import java.nio.file.Path

/**
 * What a pasted connection string describes. [host] may list several hosts (`h1:5432,h2:5433`), in which case [port] is
 * null and each entry carries its own. When [url] is set the string uses a form TableGrip can't break into fields
 * (replication, load balancing, key-value hosts...): connect with it as-is. It never contains the password.
 */
data class ParsedConnection(
    val type: DatabaseType,
    val driver: DriverId? = null,
    val host: String? = null,
    val port: Int? = null,
    val database: String? = null,
    val user: String? = null,
    val password: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val url: String? = null,
    val warnings: List<String> = emptyList(),
)

class ConnectionStringException(message: String) : IllegalArgumentException(message)

/**
 * Parses connection strings the way their drivers and tools define them:
 * - PostgreSQL: libpq URIs (`postgresql://`, `postgres://`) and keyword/value strings, with libpq parameter names
 *   translated to pgjdbc properties; pgjdbc URLs (`jdbc:postgresql://host/db`, `jdbc:postgresql:db`).
 * - MySQL/MariaDB: `mysql://`, `mariadb://`, `jdbc:mysql://`, `jdbc:mariadb://`; advanced Connector/J and MariaDB
 *   Connector/J forms are kept as raw URLs.
 * - SQLite: `jdbc:sqlite:`, `sqlite://`, `file:` URIs and plain file paths.
 * - Redis and Valkey: `redis://`, `rediss://` (TLS), `valkey://`.
 */
object ConnectionString {

    fun parse(input: String): ParsedConnection {
        val text = input.trim()
        if (text.isEmpty()) throw ConnectionStringException(tr("The connection string is empty."))
        val lower = text.lowercase()
        return when {
            lower.startsWith("postgresql://") || lower.startsWith("postgres://") -> libpqUri(text)
            lower.startsWith("jdbc:postgresql:") -> pgjdbc(text)
            // MariaDB Connector/J takes jdbc:mysql: URLs too, and unlike Connector/J TableGrip can download it.
            lower.startsWith("jdbc:mysql") -> mysqlFamily(text, DatabaseType.MySQL, driver = null, prefix = "jdbc:mysql")
            lower.startsWith("jdbc:mariadb") -> mysqlFamily(text, DatabaseType.MariaDB, DriverId.MariaDB, prefix = "jdbc:mariadb")
            lower.startsWith("mysqlx") -> throw ConnectionStringException(tr("X Protocol (mysqlx://) isn't supported; use a mysql:// URL."))
            lower.startsWith("mysql://") -> mysqlFamily(text, DatabaseType.MySQL, driver = null, prefix = "mysql")
            lower.startsWith("mariadb://") -> mysqlFamily(text, DatabaseType.MariaDB, driver = null, prefix = "mariadb")
            lower.startsWith("jdbc:sqlite:") -> sqlite(text.substring("jdbc:sqlite:".length))
            lower.startsWith("sqlite:") || lower.startsWith("sqlite3:") -> sqliteUri(text.substringAfter(':'))
            lower.startsWith("file:") -> sqlite(text)
            looksLikeKeywordValue(text) -> libpqKeywordValue(text)
            looksLikeSqlitePath(text) -> sqlite(expandHome(text))
            lower.startsWith("mongodb://") || lower.startsWith("mongodb+srv://") -> {
                val (url, password) = stripSecrets(text)
                ParsedConnection(type = DatabaseType.MongoDB, driver = DriverId.MongoDB, url = url, password = password)
            }
            lower.startsWith("redis://") || lower.startsWith("rediss://") || lower.startsWith("valkey://") || lower.startsWith("valkeys://") -> redisUri(text)
            // IDE data sources write Redis connections as JDBC URLs.
            lower.startsWith("jdbc:redis:") -> redisUri(text.substringAfter(':'))
            lower.startsWith("jdbc:sqlserver:") -> rawJdbc(text, DatabaseType.SqlServer, DriverId.SqlServer)
            lower.startsWith("jdbc:clickhouse:") || lower.startsWith("jdbc:ch:") -> rawJdbc(text, DatabaseType.ClickHouse, DriverId.ClickHouse)
            lower.startsWith("jdbc:duckdb:") -> rawJdbc(text, DatabaseType.DuckDB, DriverId.DuckDB)
            lower.startsWith("jdbc:h2:") -> rawJdbc(text, DatabaseType.H2, DriverId.H2)
            lower.startsWith("jdbc:hsqldb:") -> rawJdbc(text, DatabaseType.HSQLDB, DriverId.HSQLDB)
            lower.startsWith("jdbc:derby:") -> rawJdbc(text, DatabaseType.Derby, DriverId.Derby)
            lower.startsWith("jdbc:oracle:") -> rawJdbc(text, DatabaseType.Oracle, DriverId.Oracle)
            // Jaybird takes jdbc:firebirdsql: too.
            lower.startsWith("jdbc:firebird:") || lower.startsWith("jdbc:firebirdsql:") -> rawJdbc(text, DatabaseType.Firebird, DriverId.Firebird)
            lower.startsWith("jdbc:trino:") -> rawJdbc(text, DatabaseType.Trino, DriverId.Trino)
            lower.startsWith("jdbc:oceanbase:") -> rawJdbc(text, DatabaseType.OceanBase, DriverId.OceanBase)
            lower.startsWith("jdbc:presto:") -> rawJdbc(text, DatabaseType.Presto, DriverId.Presto)
            lower.startsWith("jdbc:snowflake:") -> rawJdbc(text, DatabaseType.Snowflake, DriverId.Snowflake)
            lower.startsWith("jdbc:databricks:") -> rawJdbc(text, DatabaseType.Databricks, DriverId.Databricks)
            lower.startsWith("jdbc:bigquery:") -> rawJdbc(text, DatabaseType.BigQuery, DriverId.BigQuery)
            lower.startsWith("jdbc:cloudspanner:") -> rawJdbc(text, DatabaseType.Spanner, DriverId.Spanner)
            lower.startsWith("jdbc:db2:") -> rawJdbc(text, DatabaseType.Db2, DriverId.Db2)
            lower.startsWith("jdbc:sap:") -> rawJdbc(text, DatabaseType.Hana, DriverId.Hana)
            lower.startsWith("jdbc:vertica:") -> rawJdbc(text, DatabaseType.Vertica, DriverId.Vertica)
            lower.startsWith("jdbc:exa:") -> rawJdbc(text, DatabaseType.Exasol, DriverId.Exasol)
            lower.startsWith("jdbc:informix-sqli:") -> rawJdbc(text, DatabaseType.Informix, DriverId.Informix)
            lower.startsWith("jdbc:iris:") -> rawJdbc(text, DatabaseType.Iris, DriverId.Iris)
            lower.startsWith("jdbc:ucanaccess:") -> rawJdbc(text, DatabaseType.Access, DriverId.UCanAccess)
            // Kyuubi's driver, which TableGrip uses for every HiveServer2 engine, takes both schemes.
            lower.startsWith("jdbc:hive2:") -> rawJdbc(text, DatabaseType.Hive, DriverId.Hive)
            lower.startsWith("jdbc:kyuubi:") -> rawJdbc(text, DatabaseType.Kyuubi, DriverId.Hive)
            // Any other driver works too, from the user's own jar.
            lower.startsWith("jdbc:") -> rawJdbc(text, DatabaseType.OtherJdbc, DriverId.Generic)
            else -> throw ConnectionStringException(tr("Unrecognized connection string. Try postgres://, mysql://, jdbc:… or a file path."))
        }
    }

    /** A JDBC URL kept as it is, minus its password. */
    private fun rawJdbc(text: String, type: DatabaseType, driver: DriverId): ParsedConnection {
        val (url, password) = stripSecrets(text)
        return ParsedConnection(type = type, driver = driver, url = url, password = password)
    }

    /**
     * Removes the password from a URL's user info, its query, and `;password=` attributes (H2, HSQLDB, Derby, SQL
     * Server), returning the cleaned URL and the password.
     */
    fun stripSecrets(url: String): Pair<String, String?> {
        var password: String? = null
        var result = url
        // PWD: Databricks' and Simba's name for it.
        Regex(""";\s*(?:password|pwd)\s*=\s*([^;]*)""", RegexOption.IGNORE_CASE).find(result)?.let { match ->
            password = match.groupValues[1]
            result = result.removeRange(match.range)
        }
        Regex("""(//)([^/@?]*?):([^/@?]*)@""").find(result)?.let { match ->
            password = decode(match.groupValues[3])
            result = result.replaceRange(match.range, "${match.groupValues[1]}${match.groupValues[2]}@")
        }
        val queryStart = result.indexOf('?')
        if (queryStart >= 0) {
            val kept =
                result.substring(queryStart + 1).split('&').filter { part ->
                    val key = part.substringBefore('=')
                    if (key.equals("password", ignoreCase = true)) {
                        password = decode(part.substringAfter('=', ""))
                        false
                    } else true
                }
            result = result.substring(0, queryStart) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }
        return result to password
    }

    /** `redis://[[user]:password@]host[:port][/db]`, `rediss://` for TLS; Valkey's `valkey://` forms are the same. */
    private fun redisUri(text: String): ParsedConnection {
        val scheme = text.substringBefore("://").lowercase()
        val uri = splitUri(text.substringAfter("://"))
        val host = parseHosts(uri.hosts).singleOrNull()
        val warnings = mutableListOf<String>()
        uri.path?.takeIf { it != "0" }?.let { warnings += tr("Database {0}: pick it in the key browser; connections start on database 0.", it) }
        return ParsedConnection(
            type = if (scheme.startsWith("valkey")) DatabaseType.Valkey else DatabaseType.Redis,
            driver = DriverId.Lettuce,
            host = host?.first?.ifEmpty { null } ?: "localhost",
            port = host?.second,
            user = uri.user,
            password = uri.password,
            properties = if (scheme.endsWith("s")) mapOf("ssl" to "true") else emptyMap(),
            warnings = warnings,
        )
    }

    // region PostgreSQL

    /** libpq URI: `postgresql://[user[:password]@][host[:port][,...]][/dbname][?param=value&...]`. */
    private fun libpqUri(text: String): ParsedConnection {
        val uri = splitUri(text.substringAfter("://"))
        val base = PgBuilder()
        uri.user?.let { base.user = it }
        uri.password?.let { base.password = it }
        base.hosts = parseHosts(uri.hosts)
        uri.path?.let { base.database = it }
        uri.query.forEach { (k, v) -> base.libpqParam(k, v) }
        return base.build()
    }

    /** libpq keyword/value: `host=localhost port=5432 dbname='my db'`, with `\'` and `\\` escapes. */
    private fun libpqKeywordValue(text: String): ParsedConnection {
        val base = PgBuilder()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val keyStart = i
            while (i < text.length && text[i] != '=' && !text[i].isWhitespace()) i++
            val key = text.substring(keyStart, i)
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '=') throw ConnectionStringException(tr("Missing \"=\" after \"{0}\".", key))
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            val value = StringBuilder()
            if (i < text.length && text[i] == '\'') {
                i++
                while (i < text.length && text[i] != '\'') {
                    if (text[i] == '\\' && i + 1 < text.length) i++
                    value.append(text[i++])
                }
                if (i >= text.length) throw ConnectionStringException(tr("Unterminated quoted value for \"{0}\".", key))
                i++
            } else {
                while (i < text.length && !text[i].isWhitespace()) {
                    if (text[i] == '\\' && i + 1 < text.length) i++
                    value.append(text[i++])
                }
            }
            base.libpqParam(key, value.toString())
        }
        return base.build()
    }

    /** pgjdbc: `jdbc:postgresql:database`, `jdbc:postgresql:/`, `jdbc:postgresql://host[:port][,...]/database?props`. */
    private fun pgjdbc(text: String): ParsedConnection {
        val rest = text.substring("jdbc:postgresql:".length)
        val base = PgBuilder(jdbc = true)
        if (rest.startsWith("//")) {
            val uri = splitUri(rest.substring(2))
            uri.user?.let { base.user = it }
            uri.password?.let { base.password = it }
            base.hosts = parseHosts(uri.hosts)
            uri.path?.let { base.database = it }
            uri.query.forEach { (k, v) -> base.jdbcParam(k, v) }
        } else {
            base.database = decode(rest.substringBefore('?').removePrefix("/")).ifEmpty { null }
            parseQuery(rest.substringAfter('?', "")).forEach { (k, v) -> base.jdbcParam(k, v) }
        }
        return base.build()
    }

    private class PgBuilder(private val jdbc: Boolean = false) {
        var hosts: List<Pair<String, Int?>> = emptyList()
        var ports: List<Int> = emptyList()
        var database: String? = null
        var user: String? = null
        var password: String? = null
        val properties = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()

        /** pgjdbc URL query: properties already use pgjdbc's names. */
        fun jdbcParam(key: String, value: String) {
            when (key) {
                "user" -> user = value
                "password" -> password = value
                else -> properties[key] = value
            }
        }

        /** libpq parameter names, translated to their pgjdbc equivalents. */
        fun libpqParam(key: String, value: String) {
            when (key) {
                "host" -> hosts = value.split(',').map { it.trim() to null }
                "hostaddr" ->
                    if (hosts.isEmpty()) {
                        hosts = value.split(',').map { it.trim() to null }
                        warnings += tr("hostaddr is used as the host name.")
                    }
                "port" -> ports = value.split(',').mapNotNull { it.trim().toIntOrNull() }
                "dbname" -> database = value
                "user" -> user = value
                "password" -> password = value
                "ssl" -> if (value == "true" || value.isEmpty()) properties["sslmode"] = "require"
                "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword", "options" -> properties[key] = value
                "application_name" -> properties["ApplicationName"] = value
                "connect_timeout" -> properties["connectTimeout"] = value
                "target_session_attrs" -> {
                    val mapped =
                        when (value) {
                            "any" -> "any"
                            "primary" -> "primary"
                            "standby" -> "secondary"
                            "prefer-standby" -> "preferSecondary"
                            "read-write" -> "primary".also { warnings += tr("target_session_attrs=read-write is approximated by targetServerType=primary.") }
                            "read-only" -> "secondary".also { warnings += tr("target_session_attrs=read-only is approximated by targetServerType=secondary.") }
                            else -> null.also { warnings += tr("Unknown target_session_attrs \"{0}\" ignored.", value) }
                        }
                    mapped?.let { properties["targetServerType"] = it }
                }
                else -> {
                    properties[key] = value
                    warnings += tr("\"{0}\" isn't a pgjdbc property; it's passed to the driver as-is.", key)
                }
            }
        }

        fun build(): ParsedConnection {
            // libpq: a single port applies to every host; otherwise ports pair up with hosts.
            var resolved = hosts.mapIndexed { i, (host, port) -> host to (port ?: ports.getOrNull(i) ?: ports.singleOrNull()) }
            if (resolved.any { it.first.startsWith("/") || it.first.startsWith("@") }) {
                warnings += tr("Unix-domain sockets aren't supported by the JDBC driver; connecting to localhost instead.")
                resolved = resolved.map { (host, port) -> (if (host.startsWith("/") || host.startsWith("@")) "localhost" else host) to port }
            }
            if (resolved.isEmpty() && ports.isNotEmpty()) resolved = listOf("localhost" to ports.first())
            val single = resolved.singleOrNull()
            return ParsedConnection(
                type = DatabaseType.PostgreSQL,
                driver = DriverId.PostgreSQL.takeIf { jdbc },
                host =
                    when {
                        resolved.isEmpty() -> null
                        single != null -> single.first.ifEmpty { "localhost" }
                        else -> resolved.joinToString(",") { (h, p) -> JdbcUrls.formatHost(h.ifEmpty { "localhost" }, p) }
                    },
                port = single?.second,
                database = database,
                user = user,
                password = password,
                properties = properties,
                warnings = warnings,
            )
        }
    }

    // endregion

    // region MySQL / MariaDB

    private val AdvancedMysqlModes = listOf(":loadbalance:", ":replication:", ":sequential:", ":load-balance-read:", "+srv:")

    private fun mysqlFamily(text: String, type: DatabaseType, driver: DriverId?, prefix: String): ParsedConnection {
        val afterScheme = text.substring(prefix.length)
        // Replication, load-balancing, SRV and key-value host forms only make sense as a whole URL.
        val advanced = AdvancedMysqlModes.any { afterScheme.startsWith(it) } || !afterScheme.startsWith("://")
        val uriPart = text.substringAfter("//", "")
        val hostPart = uriPart.substringBefore('/').substringBefore('?').substringAfterLast('@')
        if (advanced || '(' in hostPart || hostPart.startsWith("address=") || hostPart.startsWith("[") && ',' in hostPart) {
            if (!text.startsWith("jdbc:")) throw ConnectionStringException(tr("This form needs a jdbc: URL."))
            val (url, password) = stripSecrets(text)
            val uri = splitUri(uriPart)
            return ParsedConnection(
                type = type,
                driver = driver,
                user = uri.user ?: uri.query["user"],
                password = password,
                database = uri.path,
                url = url,
                warnings = listOf(tr("This URL form is kept as-is; edit it under Connect by → JDBC URL.")),
            )
        }

        val uri = splitUri(uriPart)
        val hosts = parseHosts(uri.hosts)
        val properties = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        var user = uri.user
        var password = uri.password
        uri.query.forEach { (key, value) ->
            when (key) {
                "user" -> user = value
                "password" -> password = value
                // mysql client style, common in DATABASE_URL strings.
                "ssl-mode" -> properties["sslMode"] = value.uppercase()
                "ssl" ->
                    if (!text.startsWith("jdbc:") && (value == "true" || value == "1")) {
                        properties["sslMode"] = "REQUIRED"
                        warnings += tr("ssl=true is translated to sslMode=REQUIRED.")
                    } else properties[key] = value
                else -> properties[key] = value
            }
        }
        val single = hosts.singleOrNull()
        return ParsedConnection(
            type = type,
            driver = driver,
            host =
                when {
                    hosts.isEmpty() -> null
                    single != null -> single.first
                    else -> hosts.joinToString(",") { (h, p) -> JdbcUrls.formatHost(h, p) }
                },
            port = single?.second,
            database = uri.path,
            user = user,
            password = password,
            properties = properties,
            warnings = warnings,
        )
    }

    // endregion

    // region SQLite

    /** `sqlite:` URLs: `sqlite://` alone is in-memory, `sqlite:///path` and `sqlite:////path` are absolute paths. */
    private fun sqliteUri(rest: String): ParsedConnection {
        if (!rest.startsWith("//")) return sqlite(rest)
        val path = rest.substring(2)
        return when {
            path.isEmpty() || path == ":memory:" -> sqlite(":memory:")
            path.startsWith("/") -> sqlite("/" + decode(path.trimStart('/')))
            else -> sqlite(decode(path))
        }
    }

    private fun sqlite(path: String): ParsedConnection {
        val database =
            when {
                path.isBlank() -> ":memory:"
                path.startsWith("file:") && '?' !in path -> decode(path.removePrefix("file:").removePrefix("//"))
                else -> path
            }
        val expanded = expandHome(database)
        val warnings = listOfNotNull(fileOrNull(expanded)?.let { DatabaseFiles.mismatch(it, DatabaseType.SQLite) })
        return ParsedConnection(type = DatabaseType.SQLite, driver = DriverId.SQLite, database = expanded, warnings = warnings)
    }

    private fun fileOrNull(path: String): Path? =
        if (path == ":memory:" || path.startsWith("file:")) null else runCatching { Path.of(path) }.getOrNull()

    private fun looksLikeSqlitePath(text: String): Boolean {
        val lower = text.lowercase()
        return listOf(".db", ".sqlite", ".sqlite3", ".db3").any { lower.endsWith(it) } ||
            text.startsWith("/") || text.startsWith("~/") || text.startsWith("./") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(text) ||
            // A relative name with no telling extension, e.g. `chinook`: its content decides.
            fileOrNull(expandHome(text))?.let(DatabaseFiles::detect) == DatabaseType.SQLite
    }

    // endregion

    // region URI helpers

    private class UriParts(
        val user: String?,
        val password: String?,
        val hosts: String,
        val path: String?,
        val query: Map<String, String>,
    )

    /** Splits `[user[:password]@]hosts[/path][?query]` (the part after `//`), decoding user info and path. */
    private fun splitUri(text: String): UriParts {
        val queryStart = text.indexOf('?')
        val beforeQuery = if (queryStart >= 0) text.substring(0, queryStart) else text
        val query = if (queryStart >= 0) parseQuery(text.substring(queryStart + 1)) else emptyMap()
        // The last '@' ends the user info, which tolerates passwords with unencoded '@' or '/' (common in pasted
        // strings even though the specs require encoding them).
        val at = beforeQuery.lastIndexOf('@').takeIf { it >= 0 }
        val userInfo = at?.let { beforeQuery.substring(0, it) }
        val hostAndPath = if (at != null) beforeQuery.substring(at + 1) else beforeQuery
        val slash = hostAndPath.indexOf('/')
        val hosts = if (slash >= 0) hostAndPath.substring(0, slash) else hostAndPath
        val path = if (slash >= 0) decode(hostAndPath.substring(slash + 1)).ifEmpty { null } else null
        return UriParts(
            user = userInfo?.substringBefore(':')?.let(::decode)?.ifEmpty { null },
            password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::decode),
            hosts = hosts,
            path = path,
            query = query,
        )
    }

    /** `name=value&...`; a bare `name` means `name=` (pgjdbc treats `?ssl` as enabling SSL). */
    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { part -> decode(part.substringBefore('=')) to decode(part.substringAfter('=', "")) }

    /** `host[:port][,host[:port]...]`, with IPv6 hosts in brackets and percent-encoded socket paths. */
    private fun parseHosts(spec: String): List<Pair<String, Int?>> =
        spec.split(',').filter { it.isNotBlank() }.map { entry ->
            if (entry.startsWith("[")) {
                val host = entry.substring(1, entry.indexOf(']').takeIf { it > 0 } ?: throw ConnectionStringException(tr("Unclosed [ in host \"{0}\".", entry)))
                host to entry.substringAfter("]:", "").toPortOrNull(entry)
            } else if (entry.count { it == ':' } == 1) {
                decode(entry.substringBefore(':')) to entry.substringAfter(':').toPortOrNull(entry)
            } else {
                decode(entry) to null
            }
        }

    private fun String.toPortOrNull(entry: String): Int? =
        if (isEmpty()) null else toIntOrNull()?.takeIf { it in 1..65535 } ?: throw ConnectionStringException(tr("Invalid port in \"{0}\".", entry))

    /** Percent-decoding without URLDecoder's form semantics: '+' stays a plus. */
    private fun decode(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length && value.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                bytes.write(value.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun looksLikeKeywordValue(text: String): Boolean =
        "://" !in text && Regex("""^\s*(host|hostaddr|port|dbname|user|password|sslmode|application_name|options|connect_timeout)\s*=""").containsMatchIn(text)

    private fun expandHome(path: String) = if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path

    // endregion
}
