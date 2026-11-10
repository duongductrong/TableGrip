@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package dev.tablegrip.core.backup

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionString
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.connection.SshTunnel
import dev.tablegrip.core.dialect.SqlDialect
import dev.tablegrip.core.tools.Tool
import dev.tablegrip.core.tools.ToolFamily
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** How an engine is backed up, if at all. */
sealed interface BackupSupport {
    /** With the family's command-line tools. */
    data class WithTools(val family: ToolFamily) : BackupSupport

    /** By the database itself, into a file or folder: SQLite's `VACUUM INTO`, DuckDB's `EXPORT DATABASE`. */
    data class Native(val format: BackupFormat) : BackupSupport

    data class NotYet(val reason: String) : BackupSupport

    /** Key-value and document stores: no Backup item at all. */
    data object NotApplicable : BackupSupport

    companion object {
        fun of(type: DatabaseType): BackupSupport =
            when {
                type.paradigm != Paradigm.Relational -> NotApplicable
                // The file is the data; its working copy is scratch.
                type.isDataFile -> NotApplicable
                type == DatabaseType.SQLite -> Native(BackupFormat.SqliteCopy)
                type == DatabaseType.DuckDB -> Native(BackupFormat.DuckDbExport)
                // pg_dump can't read CockroachDB's catalog; TiDB and PlanetScale have their own dump tools.
                type.family == EngineFamily.PostgreSQL && type.dialect !is SqlDialect.CockroachDBDialect -> WithTools(ToolFamily.PostgreSQL)
                type == DatabaseType.MySQL || type == DatabaseType.MariaDB -> WithTools(ToolFamily.MySQL)
                else -> NotYet(tr("Backups don't support {0} yet. They support PostgreSQL, MySQL, MariaDB, SQLite and DuckDB.", type.displayName))
            }

        /** Restoring needs the tools; a SQLite or DuckDB backup is opened as a database of its own instead. */
        fun restoreOf(type: DatabaseType): BackupSupport =
            when (val backup = of(type)) {
                is Native -> NotYet(tr("A {0} backup is a database of its own: add it as a new connection to open it.", type.displayName))
                else -> backup
            }
    }
}

enum class BackupFormat(val label: String, val extension: String?, val isFolder: Boolean = false) {
    PgCustom(tr("Custom (compressed, restore selectively)"), "dump"),
    PgPlain(tr("Plain SQL"), "sql"),
    PgDirectory(tr("Directory (parallel)"), null, isFolder = true),
    MySqlSql("SQL", "sql"),
    SqliteCopy(tr("Database file"), "db"),
    DuckDbExport(tr("Export folder (schema + Parquet)"), null, isFolder = true);

    companion object {
        fun forFamily(family: ToolFamily): List<BackupFormat> =
            when (family) {
                ToolFamily.PostgreSQL -> listOf(PgCustom, PgPlain, PgDirectory)
                ToolFamily.MySQL -> listOf(MySqlSql)
            }

        /** What a backup file was made as, from its name: folders are directory dumps, `.sql` is plain. */
        fun ofFile(path: Path): BackupFormat =
            when {
                path.isDirectory() -> PgDirectory
                path.fileName.toString().endsWith(".sql", ignoreCase = true) -> PgPlain
                else -> PgCustom
            }
    }
}

enum class BackupContent(val label: String) {
    All(tr("Schema and data")),
    SchemaOnly(tr("Schema only")),
    DataOnly(tr("Data only")),
}

data class BackupOptions(
    val format: BackupFormat,
    /** The file, or the folder for [BackupFormat.isFolder] formats. */
    val target: Path,
    val database: String,
    val content: BackupContent = BackupContent.All,
    /** Limits the backup to these schemas (PostgreSQL); empty means all. */
    val schemas: List<String> = emptyList(),
    /** Limits the backup to these tables, `schema.table` on PostgreSQL; empty means all. */
    val tables: List<String> = emptyList(),
    val noOwner: Boolean = false,
    val noPrivileges: Boolean = false,
    /** Plain SQL only: DROP each object before creating it. */
    val clean: Boolean = false,
    /** Parallel jobs for [BackupFormat.PgDirectory]. */
    val jobs: Int = 4,
    /** MySQL: also routines, triggers and events. */
    val routines: Boolean = true,
)

data class RestoreOptions(
    val source: Path,
    val format: BackupFormat,
    /** The database restored into. */
    val database: String,
    val noOwner: Boolean = false,
    val noPrivileges: Boolean = false,
    /** Drop objects before recreating them (custom and directory backups). */
    val clean: Boolean = false,
    val jobs: Int = 1,
    /** `pg_restore --list` entries to restore; null restores everything. */
    val entries: List<RestoreEntry>? = null,
)

/** One line of `pg_restore --list`, e.g. `215; 1259 16386 TABLE public orders postgres`. */
data class RestoreEntry(val line: String, val kind: String, val schema: String?, val name: String) {
    val label: String
        get() = "$kind ${listOfNotNull(schema, name).joinToString(".")}"

    companion object {
        private val Pattern = Regex("""^\d+;\s*\d+\s+\d+\s+(.+)$""")
        private val Kinds =
            listOf(
                "TABLE DATA", "SEQUENCE SET", "SEQUENCE OWNED BY", "FK CONSTRAINT", "MATERIALIZED VIEW DATA", "MATERIALIZED VIEW", "DEFAULT ACL", "SCHEMA",
                "TABLE", "VIEW", "SEQUENCE", "INDEX", "CONSTRAINT", "TRIGGER", "FUNCTION", "PROCEDURE", "TYPE", "DOMAIN", "EXTENSION", "COMMENT", "ACL", "DEFAULT",
                "POLICY", "RULE", "AGGREGATE", "PUBLICATION", "ENCODING", "STDSTRINGS", "SEARCHPATH", "DATABASE",
            )

        /** The restorable entries in `pg_restore --list` output; comments and blank lines are skipped. */
        fun parse(listing: String): List<RestoreEntry> =
            listing.lineSequence().mapNotNull { line ->
                val rest = Pattern.find(line.trim())?.groupValues?.get(1) ?: return@mapNotNull null
                val kind = Kinds.firstOrNull { rest.startsWith("$it ") } ?: rest.substringBefore(' ')
                val words = rest.removePrefix(kind).trim().split(' ')
                // "<schema> <name> <owner>"; schema is "-" for global objects.
                val schema = words.getOrNull(0)?.takeIf { it != "-" }
                val name = words.drop(1).dropLast(1).joinToString(" ").ifBlank { words.getOrNull(1) ?: words.firstOrNull().orEmpty() }
                RestoreEntry(line.trim(), kind, schema, name)
            }.toList()
    }
}

/** Host, port, user and password the tools connect with: through an SSH tunnel's local end when there is one. */
data class ServerEndpoint(val host: String, val port: Int, val user: String, val password: String?, val properties: Map<String, String>) {
    companion object {
        /** The server [config] points at, by host fields or by its URL. Doesn't open the tunnel. */
        fun of(config: ConnectionConfig, password: String?): ServerEndpoint {
            val defaultPort = config.type.defaultPort ?: throw IOException(tr("{0} doesn't use a network connection.", config.type.displayName))
            val url = config.url?.let(ConnectionString::parse)
            val host = (url?.host ?: config.host).takeIf { ',' !in it } ?: throw IOException(tr("Backups need a single host, not a list."))
            return ServerEndpoint(host.trim('[', ']'), url?.port ?: config.port ?: defaultPort, config.user, password, config.properties)
        }
    }
}

/**
 * A tool run: arguments, environment and files only it may read. Secrets go in [environment] or [secretFiles], never in
 * [arguments], which other users of the machine can see in the process list; [display] is safe to show and copy.
 */
data class ToolCommand(
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val secretFiles: Map<Path, String> = emptyMap(),
    /** Fed to the tool's standard input, e.g. a SQL file for `mysql`. */
    val input: Path? = null,
) {
    /** The command as a person would type it, with the password left to an environment variable or prompt. */
    val display: String
        get() =
            buildString {
                environment.keys.filter { it != "PGPASSWORD" && it != "PGAPPNAME" }.forEach { append("$it=${quote(environment.getValue(it))} ") }
                append(arguments.joinToString(" ") { quote(it) })
                input?.let { append(" < ${quote(it.absolutePathString())}") }
            }

    private fun quote(text: String): String = if (text.isNotEmpty() && text.all { it.isLetterOrDigit() || it in "-_./=:@,+" }) text else "'" + text.replace("'", "'\\''") + "'"
}

/** Builds the tool command lines. Pure, so what is run can be tested and shown before running it. */
object BackupCommands {
    fun pgDump(tool: Path, server: ServerEndpoint, options: BackupOptions): ToolCommand {
        val args =
            buildList {
                add(tool.absolutePathString())
                addAll(pgConnection(server, options.database))
                add(
                    "--format=" +
                        when (options.format) {
                            BackupFormat.PgCustom -> "custom"
                            BackupFormat.PgDirectory -> "directory"
                            else -> "plain"
                        }
                )
                if (options.format == BackupFormat.PgDirectory && options.jobs > 1) add("--jobs=${options.jobs}")
                when (options.content) {
                    BackupContent.SchemaOnly -> add("--schema-only")
                    BackupContent.DataOnly -> add("--data-only")
                    BackupContent.All -> Unit
                }
                if (options.noOwner) add("--no-owner")
                if (options.noPrivileges) add("--no-privileges")
                if (options.clean && options.format == BackupFormat.PgPlain) addAll(listOf("--clean", "--if-exists"))
                options.schemas.forEach { add("--schema=$it") }
                options.tables.forEach { add("--table=$it") }
                add("--verbose")
                add("--file=${options.target.absolutePathString()}")
            }
        return ToolCommand(args, pgEnvironment(server))
    }

    fun pgRestoreList(tool: Path, source: Path): ToolCommand = ToolCommand(listOf(tool.absolutePathString(), "--list", source.absolutePathString()))

    /** `pg_restore` for custom and directory backups; [listFile] holds the chosen entries, when some were left out. */
    fun pgRestore(tool: Path, server: ServerEndpoint, options: RestoreOptions, listFile: Path?): ToolCommand {
        val args =
            buildList {
                add(tool.absolutePathString())
                addAll(pgConnection(server, options.database))
                if (options.noOwner) add("--no-owner")
                if (options.noPrivileges) add("--no-privileges")
                if (options.clean) addAll(listOf("--clean", "--if-exists"))
                if (options.jobs > 1) add("--jobs=${options.jobs}")
                listFile?.let { add("--use-list=${it.absolutePathString()}") }
                add("--exit-on-error")
                add("--verbose")
                add(options.source.absolutePathString())
            }
        return ToolCommand(args, pgEnvironment(server))
    }

    /** `psql` for plain SQL backups, stopping at the first error. */
    fun psqlRestore(tool: Path, server: ServerEndpoint, options: RestoreOptions): ToolCommand {
        val args =
            listOf(tool.absolutePathString()) + pgConnection(server, options.database) +
                listOf("--no-psqlrc", "--set=ON_ERROR_STOP=1", "--echo-errors", "--file=${options.source.absolutePathString()}")
        return ToolCommand(args, pgEnvironment(server))
    }

    private fun pgConnection(server: ServerEndpoint, database: String) =
        listOf("--host=${server.host}", "--port=${server.port}") + listOfNotNull(server.user.takeIf { it.isNotBlank() }?.let { "--username=$it" }) +
            listOf("--dbname=$database", "--no-password")

    /** libpq reads the password and TLS mode from the environment; nothing secret is on the command line. */
    private fun pgEnvironment(server: ServerEndpoint): Map<String, String> =
        buildMap {
            server.password?.takeIf { it.isNotEmpty() }?.let { put("PGPASSWORD", it) }
            put("PGAPPNAME", "TableGrip")
            put("PGCONNECT_TIMEOUT", "10")
            server.properties["sslmode"]?.let { put("PGSSLMODE", it) }
            server.properties["sslrootcert"]?.let { put("PGSSLROOTCERT", it) }
        }

    /**
     * `mysqldump` (or `mariadb-dump`) of [BackupOptions.database], or of its [BackupOptions.tables]. The dump has no
     * `CREATE DATABASE`, so it restores into whichever database is chosen.
     */
    fun mysqlDump(tool: Path, server: ServerEndpoint, options: BackupOptions, secrets: Path): ToolCommand {
        val args =
            buildList {
                add(tool.absolutePathString())
                // Must come first: the tools only read option files named before every other option.
                add("--defaults-extra-file=${secrets.absolutePathString()}")
                addAll(listOf("--host=${server.host}", "--port=${server.port}", "--protocol=TCP"))
                add("--single-transaction")
                if (options.routines) addAll(listOf("--routines", "--triggers", "--events"))
                when (options.content) {
                    BackupContent.SchemaOnly -> add("--no-data")
                    BackupContent.DataOnly -> add("--no-create-info")
                    BackupContent.All -> Unit
                }
                add("--verbose")
                add("--result-file=${options.target.absolutePathString()}")
                add(options.database)
                addAll(options.tables)
            }
        return ToolCommand(args, secretFiles = mapOf(secrets to mysqlOptionFile(tool, server)))
    }

    fun mysqlRestore(tool: Path, server: ServerEndpoint, options: RestoreOptions, secrets: Path): ToolCommand {
        val args =
            listOf(
                tool.absolutePathString(),
                "--defaults-extra-file=${secrets.absolutePathString()}",
                "--host=${server.host}",
                "--port=${server.port}",
                "--protocol=TCP",
                "--verbose",
                options.database,
            )
        return ToolCommand(args, secretFiles = mapOf(secrets to mysqlOptionFile(tool, server)), input = options.source)
    }

    /** The `[client]` option file with the user and password, written readable by the owner only. */
    private fun mysqlOptionFile(tool: Path, server: ServerEndpoint): String =
        buildString {
            appendLine("[client]")
            if (server.user.isNotBlank()) appendLine("user=${optionValue(server.user)}")
            server.password?.takeIf { it.isNotEmpty() }?.let { appendLine("password=${optionValue(it)}") }
            // Oracle's tools take ssl-mode; MariaDB's take ssl, so only MySQL's get it.
            val mysqlTools = !tool.fileName.toString().startsWith("mariadb")
            val sslMode = server.properties["sslMode"]?.uppercase()
            if (mysqlTools && sslMode != null && sslMode !in setOf("DISABLED", "DISABLE")) appendLine("ssl-mode=REQUIRED")
        }

    /** Quoted with backslash escapes, as MySQL option files read them. */
    private fun optionValue(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** A finished run's outcome. [log] is what the tool printed, kept in the log file too. */
data class ToolRun(val exitCode: Int, val log: String)

class ToolFailedException(message: String, val log: String) : IOException(message)

/**
 * Runs a [ToolCommand]: writes its secret files (owner-only), feeds [onLine] each line the tool prints, and on
 * cancellation kills it. Secret files are always deleted afterwards.
 */
object ToolRunner {
    suspend fun run(command: ToolCommand, logFile: Path? = null, onLine: (String) -> Unit = {}): ToolRun =
        withContext(Dispatchers.IO) {
            try {
                command.secretFiles.forEach { (path, text) -> writeOwnerOnly(path, text) }
                val builder = ProcessBuilder(command.arguments).redirectErrorStream(true)
                builder.environment().putAll(command.environment)
                command.input?.let { builder.redirectInput(it.toFile()) }
                logFile?.let { Files.createDirectories(it.parent) }
                val log = StringBuilder()
                val process = builder.start()
                try {
                    runInterruptible {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                log.appendLine(line)
                                onLine(line)
                            }
                        }
                        process.waitFor()
                    }
                } finally {
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroy() }
                        process.destroy()
                        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                    logFile?.let { file -> runCatching { file.writeText("$ ${command.display}\n\n$log") } }
                }
                ToolRun(process.exitValue(), log.toString())
            } finally {
                withContext(NonCancellable) { command.secretFiles.keys.forEach { runCatching { it.deleteIfExists() } } }
            }
        }

    /** Runs [command] and throws [ToolFailedException] with the tool's last words unless it exits with 0. */
    suspend fun runChecked(command: ToolCommand, what: String, logFile: Path? = null, onLine: (String) -> Unit = {}): ToolRun {
        val run = run(command, logFile, onLine)
        if (run.exitCode != 0) {
            val tail = run.log.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
            throw ToolFailedException(tr("{0} failed (exit code {1}).\n{2}", what, run.exitCode, tail), run.log)
        }
        return run
    }

    private fun writeOwnerOnly(path: Path, text: String) {
        Files.deleteIfExists(path)
        Files.createFile(path)
        runCatching { Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        path.writeText(text)
    }

    /** Removes what a failed or cancelled backup left. */
    fun discard(target: Path) {
        runCatching { if (target.isDirectory()) target.deleteRecursively() else target.deleteIfExists() }
    }
}

/** Opens an SSH tunnel for [config] when it has one, so tools reach the server on a local port. */
fun openTunnelFor(config: ConnectionConfig, server: ServerEndpoint, sshSecret: String?): SshTunnel? =
    config.ssh?.let { SshTunnel.open(it, sshSecret, server.host, server.port) }

/** Which tool backs up or restores, preferring MariaDB's own names for MariaDB servers. */
fun toolsFor(type: DatabaseType, restore: Boolean, format: BackupFormat): List<Tool> =
    when {
        type.family == EngineFamily.MySQL ->
            if (restore) (if (type == DatabaseType.MariaDB) listOf(Tool.MariaDb, Tool.MySql) else listOf(Tool.MySql, Tool.MariaDb))
            else (if (type == DatabaseType.MariaDB) listOf(Tool.MariaDbDump, Tool.MySqlDump) else listOf(Tool.MySqlDump, Tool.MariaDbDump))
        !restore -> listOf(Tool.PgDump)
        format == BackupFormat.PgPlain -> listOf(Tool.Psql)
        else -> listOf(Tool.PgRestore)
    }
