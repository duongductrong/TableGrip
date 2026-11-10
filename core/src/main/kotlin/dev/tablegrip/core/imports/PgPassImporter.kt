package dev.tablegrip.core.imports

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * libpq's password file (`host:port:database:user:password`, `*` wildcards, `\:` escapes) and connection service file
 * (`[name]` sections of `host=`, `port=`, `dbname=`...), as documented in the PostgreSQL manual.
 */
internal object PgPassImporter {

    data class Entry(val host: String, val port: String, val database: String, val user: String, val password: String)

    /** `$PGPASSFILE`, else `~/.pgpass` (`%APPDATA%\postgresql\pgpass.conf` on Windows). */
    fun passFile(home: Path, env: Map<String, String>): Path =
        env["PGPASSFILE"]?.let(Path::of)
            ?: env["APPDATA"]?.takeIf { isWindows }?.let { Path.of(it, "postgresql", "pgpass.conf") }
            ?: home.resolve(".pgpass")

    /** `$PGSERVICEFILE` or `~/.pg_service.conf`, then the system-wide file in `$PGSYSCONFDIR`. */
    fun serviceFiles(home: Path, env: Map<String, String>): List<Path> =
        listOfNotNull(
            env["PGSERVICEFILE"]?.let(Path::of) ?: home.resolve(".pg_service.conf"),
            env["PGSYSCONFDIR"]?.let { Path.of(it, "pg_service.conf") },
        ).distinct()

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    fun entries(text: String): List<Entry> =
        text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapNotNull { line ->
                val fields = splitFields(line)
                if (fields.size < 5) null else Entry(fields[0], fields[1], fields[2], fields[3], fields.drop(4).joinToString(":"))
            }
            .toList()

    /** Fields separated by unescaped colons; `\:` and `\\` are a literal colon and backslash. */
    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> current.append(line[++i])
                c == ':' && fields.size < 4 -> fields += current.toString().also { current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    /** The first matching entry's password, as libpq picks it. */
    fun lookup(entries: List<Entry>, host: String, port: Int, database: String, user: String): String? {
        val hosts = if (host == "127.0.0.1" || host == "::1") setOf(host, "localhost") else setOf(host)
        fun matches(pattern: String, value: String) = pattern == "*" || pattern == value
        return entries.firstOrNull { entry ->
            (entry.host == "*" || entry.host in hosts) &&
                matches(entry.port, port.toString()) &&
                matches(entry.database, database) &&
                matches(entry.user, user)
        }?.password
    }

    fun readPassFile(path: Path): ImportScan {
        val candidates = mutableListOf<ImportCandidate>()
        val skipped = mutableListOf<String>()
        for (entry in entries(path.readText())) {
            // A wildcard host or user is a password for many servers, not a server to connect to.
            if (entry.host == "*" || entry.user == "*") {
                skipped += tr(".pgpass: “{0}:{1}:{2}:{3}” uses a wildcard host or user.", entry.host, entry.port, entry.database, entry.user)
                continue
            }
            val port = entry.port.takeIf { it != "*" }?.toIntOrNull()
            val database = entry.database.takeIf { it != "*" }.orEmpty()
            val config =
                ConnectionConfig(
                    id = ConnectionImport.newId(),
                    name = "${entry.user}@${entry.host}" + (if (database.isNotEmpty()) "/$database" else ""),
                    type = DatabaseType.PostgreSQL,
                    host = entry.host.removeSurrounding("[", "]"),
                    port = port,
                    database = database,
                    user = entry.user,
                )
            candidates += ImportCandidate(config, password = entry.password, origin = path.toString())
        }
        return ImportScan(candidates, skipped)
    }

    fun readServiceFile(path: Path): ImportScan {
        val candidates = mutableListOf<ImportCandidate>()
        var name: String? = null
        var params = mutableMapOf<String, String>()
        fun flush() {
            val service = name ?: return
            val properties = buildMap {
                params["sslmode"]?.let { put("sslmode", it) }
                params["application_name"]?.let { put("ApplicationName", it) }
                params["connect_timeout"]?.let { put("connectTimeout", it) }
            }
            val config =
                ConnectionConfig(
                    id = ConnectionImport.newId(),
                    name = service,
                    type = DatabaseType.PostgreSQL,
                    host = params["host"]?.substringBefore(',')?.takeUnless { it.startsWith("/") } ?: "localhost",
                    port = params["port"]?.substringBefore(',')?.toIntOrNull(),
                    database = params["dbname"].orEmpty(),
                    user = params["user"].orEmpty(),
                    properties = properties,
                )
            candidates += ImportCandidate(config, password = params["password"], origin = path.toString())
        }
        for (raw in path.readText().lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    flush()
                    name = line.removeSurrounding("[", "]").trim()
                    params = mutableMapOf()
                }
                '=' in line -> params[line.substringBefore('=').trim().lowercase()] = line.substringAfter('=').trim()
            }
        }
        flush()
        return ImportScan(candidates)
    }
}
