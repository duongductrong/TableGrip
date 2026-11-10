package dev.tablegrip.core.imports

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.ParsedConnection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Connections in projects' `.env` files: any variable holding a connection URL (`DATABASE_URL`, `REDIS_URL`,
 * `MONGO_URI`...), and the split forms frameworks and Docker images use — Laravel's `DB_CONNECTION`/`DB_HOST`/...,
 * and `POSTGRES_*` / `MYSQL_*` / `PG*` variables.
 */
internal object DotEnvImporter {

    /** Looks for `.env` files under [folder], skipping dependency and build folders. */
    fun scan(folder: Path, maxDepth: Int = 4): ImportScan {
        val files =
            Files.walk(folder, maxDepth).use { paths ->
                paths.asSequence()
                    .filter { path -> path.isRegularFile() && isEnvFile(path.name) && folder.relativize(path).none { it.name in SkippedFolders } }
                    .sorted()
                    .take(MaxFiles)
                    .toList()
            }
        return files.fold(ImportScan()) { scan, file -> scan + read(file, folder) }
    }

    private val SkippedFolders = setOf("node_modules", ".git", "vendor", "build", "dist", "target", ".gradle", ".venv", "venv", ".next", ".idea")
    private const val MaxFiles = 200

    /** `.env`, `.env.local`, `.env.production`, `app.env`... but not editor backups. */
    fun isEnvFile(name: String): Boolean =
        (name == ".env" || name.startsWith(".env.") || name.endsWith(".env")) && !name.endsWith("~") && !name.endsWith(".swp")

    /** Templates hold placeholders, not real credentials. */
    private fun isTemplate(name: String) = Regex("""\.(example|sample|template|dist|defaults?)$""").containsMatchIn(name)

    /** Reads one file; [root] names connections by their folder relative to it. */
    fun read(file: Path, root: Path? = null): ImportScan {
        val vars = parse(file.readText())
        val project = (root?.let { r -> file.parent?.let { if (it == r) r.name else r.relativize(it).toString() } } ?: file.parent?.name).orEmpty()
        val template = isTemplate(file.name)
        val label = listOf(project, file.name).filter { it.isNotEmpty() }.joinToString(" · ")
        val candidates = mutableListOf<ImportCandidate>()
        val skipped = mutableListOf<String>()
        val notes = if (template) listOf(tr("From a template file: the values may be placeholders")) else emptyList()

        fun add(parsed: ParsedConnection, variable: String) {
            val config = ConnectionImport.fromParsed(parsed, name = "$project · $variable".removePrefix(" · "), group = project)
            candidates += ImportCandidate(config, password = parsed.password?.takeIf { it.isNotEmpty() }, origin = "$file ($variable)", notes = notes + parsed.warnings)
        }

        for ((key, value) in vars) {
            if (!looksLikeUrl(value)) continue
            val parsed = ConnectionImport.parseOrNull(value)
            if (parsed == null) {
                if (looksLikeDatabaseKey(key)) skipped += tr("{0}: {1} isn't a connection string TableGrip understands.", label, key)
                continue
            }
            add(parsed, key)
        }
        split(vars)?.let { (parsed, variable) -> add(parsed, variable) }
        return ImportScan(candidates, skipped)
    }

    private fun looksLikeUrl(value: String) =
        Regex("""^(postgres(ql)?|mysql|mariadb|mongodb(\+srv)?|rediss?|valkeys?|sqlite3?|jdbc:[a-z0-9]+)(:|://)""", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun looksLikeDatabaseKey(key: String) = Regex("""(DATABASE|DB|POSTGRES|PG|MYSQL|MONGO|REDIS).*(URL|URI|DSN)""", RegexOption.IGNORE_CASE).containsMatchIn(key)

    /** A connection spelled out over several variables, with the variable that names it. */
    private fun split(vars: Map<String, String>): Pair<ParsedConnection, String>? {
        // Laravel and many PHP/Node frameworks.
        vars["DB_HOST"]?.let { host ->
            val type =
                when (vars["DB_CONNECTION"]?.lowercase() ?: vars["DB_DRIVER"]?.lowercase() ?: vars["DB_DIALECT"]?.lowercase()) {
                    "pgsql", "postgres", "postgresql" -> DatabaseType.PostgreSQL
                    "mysql" -> DatabaseType.MySQL
                    "mariadb" -> DatabaseType.MariaDB
                    "sqlsrv", "mssql" -> DatabaseType.SqlServer
                    "sqlite" -> return null
                    null -> portType(vars["DB_PORT"]) ?: return null
                    else -> return null
                }
            return ParsedConnection(
                type = type,
                host = host.ifEmpty { "localhost" },
                port = vars["DB_PORT"]?.toIntOrNull(),
                database = vars["DB_DATABASE"] ?: vars["DB_NAME"],
                user = vars["DB_USERNAME"] ?: vars["DB_USER"],
                password = vars["DB_PASSWORD"] ?: vars["DB_PASS"],
            ) to "DB_HOST"
        }
        // The official Docker images' variables, and libpq's own environment.
        if (vars.keys.any { it.startsWith("POSTGRES_") }) {
            return ParsedConnection(
                type = DatabaseType.PostgreSQL,
                host = vars["POSTGRES_HOST"] ?: "localhost",
                port = vars["POSTGRES_PORT"]?.toIntOrNull(),
                database = vars["POSTGRES_DB"] ?: vars["POSTGRES_DATABASE"] ?: vars["POSTGRES_USER"],
                user = vars["POSTGRES_USER"] ?: "postgres",
                password = vars["POSTGRES_PASSWORD"],
            ) to "POSTGRES_*"
        }
        if (vars.containsKey("PGHOST") || vars.containsKey("PGDATABASE")) {
            return ParsedConnection(
                type = DatabaseType.PostgreSQL,
                host = vars["PGHOST"] ?: "localhost",
                port = vars["PGPORT"]?.toIntOrNull(),
                database = vars["PGDATABASE"],
                user = vars["PGUSER"],
                password = vars["PGPASSWORD"],
            ) to "PG*"
        }
        if (vars.keys.any { it.startsWith("MYSQL_") && it != "MYSQL_ALLOW_EMPTY_PASSWORD" }) {
            val user = vars["MYSQL_USER"] ?: "root"
            return ParsedConnection(
                type = DatabaseType.MySQL,
                host = vars["MYSQL_HOST"] ?: "localhost",
                port = vars["MYSQL_PORT"]?.toIntOrNull() ?: vars["MYSQL_TCP_PORT"]?.toIntOrNull(),
                database = vars["MYSQL_DATABASE"],
                user = user,
                password = if (user == "root") vars["MYSQL_ROOT_PASSWORD"] ?: vars["MYSQL_PASSWORD"] else vars["MYSQL_PASSWORD"],
            ) to "MYSQL_*"
        }
        return null
    }

    private fun portType(port: String?): DatabaseType? =
        when (port?.toIntOrNull()) {
            5432 -> DatabaseType.PostgreSQL
            3306 -> DatabaseType.MySQL
            1433 -> DatabaseType.SqlServer
            else -> null
        }

    /**
     * `KEY=value` lines as dotenv reads them: `export` prefixes, single quotes taken literally, double quotes with
     * escapes, `#` comments after unquoted values, and `${VAR}` / `$VAR` references to earlier variables.
     */
    fun parse(text: String): Map<String, String> {
        val vars = linkedMapOf<String, String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i++].trim().removePrefix("export ").trimStart()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=').takeIf { it > 0 } ?: continue
            val key = line.substring(0, eq).trim()
            if (!Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(key)) continue
            var raw = line.substring(eq + 1).trimStart()
            val value =
                when {
                    raw.startsWith("'") -> raw.drop(1).substringBefore('\'')
                    raw.startsWith("\"") -> {
                        // A double-quoted value may continue on the next lines.
                        while (!closesQuote(raw) && i < lines.size) raw += "\n" + lines[i++]
                        interpolate(unescape(raw.drop(1).let { it.substring(0, closingQuote(it)) }), vars)
                    }
                    else -> interpolate(raw.replace(Regex("""\s+#.*$"""), "").trim(), vars)
                }
            vars[key] = value
        }
        return vars
    }

    private fun closingQuote(body: String): Int {
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return body.length
    }

    private fun closesQuote(raw: String) = closingQuote(raw.drop(1)) < raw.length - 1

    private fun unescape(value: String) =
        value.replace(Regex("""\\(.)""")) { match ->
            when (val c = match.groupValues[1]) {
                "n" -> "\n"
                "t" -> "\t"
                else -> c
            }
        }

    private fun interpolate(value: String, vars: Map<String, String>): String =
        value.replace(Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}|\$([A-Za-z_][A-Za-z0-9_]*)""")) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[4] }
            vars[name]?.takeIf { it.isNotEmpty() } ?: match.groupValues[3]
        }
}
