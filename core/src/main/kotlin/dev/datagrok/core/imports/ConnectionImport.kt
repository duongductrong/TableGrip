package dev.datagrok.core.imports

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionString
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.EngineFamily
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.connection.ParsedConnection
import dev.datagrok.core.connection.SshConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** The tools and files Datagrok reads connections from. */
enum class ImportFormat(val label: String, val shortLabel: String = label) {
    TablePlus("TablePlus"),
    DBeaver("DBeaver"),
    JetBrains("DataGrip / JetBrains IDEs", "DataGrip"),
    PgPass(".pgpass"),
    PgService("pg_service.conf"),
    DotEnv(".env files", ".env"),
}

/** A place connections can be read from: one file, or a folder for [ImportFormat.DotEnv]. */
data class ImportSource(val format: ImportFormat, val path: Path) {
    val label: String
        get() = format.label
}

/**
 * A connection found in another tool, ready to save. [config] has a fresh id; [password] and [sshSecret] are what the
 * tool had stored, if Datagrok could read them. [origin] says where it came from, for the user.
 */
data class ImportCandidate(
    val config: ConnectionConfig,
    val password: String? = null,
    val sshSecret: String? = null,
    val origin: String,
    val notes: List<String> = emptyList(),
) {
    /** What identifies the database regardless of name: two candidates with the same key point at the same place. */
    val key: String
        get() = ConnectionImport.keyOf(config)
}

/** What reading a source produced: connections, plus those it had to leave out and why. */
data class ImportScan(val candidates: List<ImportCandidate> = emptyList(), val skipped: List<String> = emptyList()) {
    operator fun plus(other: ImportScan) = ImportScan(candidates + other.candidates, skipped + other.skipped)
}

/**
 * Finds connections saved by TablePlus, DBeaver, DataGrip and the other JetBrains IDEs, libpq's `.pgpass` and
 * `pg_service.conf`, and `.env` files, so moving to Datagrok doesn't mean typing them in again. Nothing is written: the
 * caller saves the candidates the user picks.
 */
object ConnectionImport {

    /** Sources present on this machine, in the order they're listed. */
    fun discover(home: Path = Path.of(System.getProperty("user.home")), env: Map<String, String> = System.getenv()): List<ImportSource> =
        buildList {
            TablePlusImporter.locations(home).filter { it.isRegularFile() }.forEach { add(ImportSource(ImportFormat.TablePlus, it)) }
            DBeaverImporter.locations(home, env).forEach { add(ImportSource(ImportFormat.DBeaver, it)) }
            JetBrainsImporter.locations(home).forEach { add(ImportSource(ImportFormat.JetBrains, it)) }
            PgPassImporter.passFile(home, env).takeIf { it.isRegularFile() }?.let { add(ImportSource(ImportFormat.PgPass, it)) }
            PgPassImporter.serviceFiles(home, env).filter { it.isRegularFile() }.forEach { add(ImportSource(ImportFormat.PgService, it)) }
        }

    /** Reads one source; a file that can't be read becomes a skipped entry rather than an exception. */
    fun read(source: ImportSource, home: Path = Path.of(System.getProperty("user.home")), env: Map<String, String> = System.getenv()): ImportScan =
        runCatching {
            when (source.format) {
                ImportFormat.TablePlus -> TablePlusImporter.read(source.path)
                ImportFormat.DBeaver -> DBeaverImporter.read(source.path)
                ImportFormat.JetBrains -> JetBrainsImporter.read(source.path, home)
                ImportFormat.PgPass -> PgPassImporter.readPassFile(source.path)
                ImportFormat.PgService -> PgPassImporter.readServiceFile(source.path)
                ImportFormat.DotEnv -> if (source.path.isDirectory()) DotEnvImporter.scan(source.path) else DotEnvImporter.read(source.path)
            }
        }.getOrElse { ImportScan(skipped = listOf("${source.path}: ${it.message ?: it}")) }

    /** Works out what a file the user picked is, from its name and then its content; null if it's none of them. */
    fun detect(path: Path): ImportSource? {
        if (path.isDirectory()) {
            // A JetBrains project, or a folder to look for .env files in.
            val idea = path.resolve(".idea").resolve("dataSources.xml")
            return if (idea.exists()) ImportSource(ImportFormat.JetBrains, idea) else ImportSource(ImportFormat.DotEnv, path)
        }
        val name = path.name.lowercase()
        val format =
            when {
                name.endsWith(".plist") -> ImportFormat.TablePlus
                name.startsWith("data-sources") && name.endsWith(".json") -> ImportFormat.DBeaver
                name.startsWith("datasources") && name.endsWith(".xml") -> ImportFormat.JetBrains
                name == ".pgpass" || name == "pgpass.conf" -> ImportFormat.PgPass
                name.endsWith("pg_service.conf") -> ImportFormat.PgService
                name == ".env" || name.startsWith(".env.") || name.endsWith(".env") -> ImportFormat.DotEnv
                else -> sniff(path)
            } ?: return null
        return ImportSource(format, path)
    }

    private fun sniff(path: Path): ImportFormat? {
        if (Files.size(path) > MaxSniffBytes) return null
        val text = runCatching { path.readText() }.getOrNull() ?: return null
        return when {
            text.contains("<data-source") -> ImportFormat.JetBrains
            text.contains("\"connections\"") && text.contains("\"provider\"") -> ImportFormat.DBeaver
            text.contains("ConnectionName") -> ImportFormat.TablePlus
            Regex("""^\s*\[[^\]]+]\s*$""", RegexOption.MULTILINE).containsMatchIn(text) && text.contains("dbname") -> ImportFormat.PgService
            Regex("""^[^#\s][^:\n]*:[^:\n]*:[^:\n]*:[^:\n]*:.*$""", RegexOption.MULTILINE).containsMatchIn(text) -> ImportFormat.PgPass
            Regex("""^\s*(export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=""", RegexOption.MULTILINE).containsMatchIn(text) -> ImportFormat.DotEnv
            else -> null
        }
    }

    private const val MaxSniffBytes = 4L * 1024 * 1024

    /**
     * Engine, address, database and user, normalised so that `localhost:5432/app` written with or without its port is
     * the same database. Profiles connecting by URL compare by URL.
     */
    fun keyOf(config: ConnectionConfig): String {
        val family = config.type.family
        config.url?.let { return "$family|url|${it.trim().lowercase()}|${config.user.trim()}" }
        if (config.type.isFile) return "$family|file|${config.database.trim()}"
        val host = config.host.trim().lowercase().let { if (it == "127.0.0.1" || it == "::1" || it == "[::1]") "localhost" else it }
        val port = config.port ?: config.type.defaultPort
        return "$family|$host|$port|${config.database.trim()}|${config.user.trim()}"
    }

    /** libpq's password file on this machine, which may hold passwords for connections other tools import without. */
    fun pgPassFile(home: Path = Path.of(System.getProperty("user.home")), env: Map<String, String> = System.getenv()): Path = PgPassImporter.passFile(home, env)

    /**
     * Fills in the passwords of PostgreSQL connections that came without one from [passFile] entries, the way libpq
     * itself would look them up.
     */
    fun fillPasswordsFromPgPass(candidates: List<ImportCandidate>, passFile: Path): List<ImportCandidate> {
        if (!passFile.isRegularFile()) return candidates
        val entries = runCatching { PgPassImporter.entries(passFile.readText()) }.getOrDefault(emptyList())
        if (entries.isEmpty()) return candidates
        return candidates.map { candidate ->
            val config = candidate.config
            if (candidate.password != null || config.type.family != EngineFamily.PostgreSQL || config.url != null) return@map candidate
            val password = PgPassImporter.lookup(entries, config.host, config.port ?: 5432, config.database.ifEmpty { config.user }, config.user)
            if (password == null) candidate else candidate.copy(password = password, notes = candidate.notes + "Password from ~/.pgpass")
        }
    }

    // region Helpers shared by the importers

    internal fun newId(): String = UUID.randomUUID().toString()

    /** Turns a parsed connection string into a profile, the way the connection dialog does when one is pasted. */
    internal fun fromParsed(
        parsed: ParsedConnection,
        name: String,
        group: String = "",
        environment: Environment = Environment.None,
        readOnly: Boolean = false,
        ssh: SshConfig? = null,
        type: DatabaseType = parsed.type,
    ): ConnectionConfig {
        val driver = parsed.driver?.takeIf { it in type.drivers }
        return ConnectionConfig(
            id = newId(),
            name = name.trim().ifEmpty { fallbackName(parsed) },
            type = type,
            host = if (!type.isServer) "" else parsed.host ?: if (parsed.url == null) "localhost" else "",
            port = parsed.port.takeIf { type.isServer },
            database = parsed.database.orEmpty(),
            user = parsed.user.orEmpty(),
            driver = driver,
            properties = parsed.properties,
            url = parsed.url,
            group = group.trim(),
            environment = environment,
            readOnly = readOnly,
            confirmWrites = environment == Environment.Production,
            ssh = ssh?.takeIf { type.isServer },
        )
    }

    /** Parses [text] as a connection string, also accepting `redis://` and the `jdbc:` forms of URLs DataGrip writes. */
    internal fun parseOrNull(text: String): ParsedConnection? = runCatching { ConnectionString.parse(text) }.getOrNull()

    private fun fallbackName(parsed: ParsedConnection): String =
        when {
            parsed.url != null -> parsed.url.substringAfter("//").substringBefore('?').substringAfterLast('@').ifEmpty { parsed.type.displayName }
            parsed.type.isServer -> listOfNotNull(parsed.host ?: "localhost", parsed.database?.takeIf { it.isNotEmpty() }).joinToString("/")
            else -> parsed.database.orEmpty().substringAfterLast('/').ifEmpty { parsed.type.displayName }
        }

    /** Maps the environment names other tools use (TablePlus' tags, DBeaver's connection types) to Datagrok's. */
    internal fun environmentOf(name: String?): Environment =
        when (name?.trim()?.lowercase()) {
            null, "" -> Environment.None
            "local" -> Environment.Local
            "dev", "development" -> Environment.Development
            "test", "testing", "qa", "staging", "stage", "uat" -> Environment.Staging
            "prod", "production", "live" -> Environment.Production
            else -> Environment.None
        }

    internal fun expandHome(path: String, home: Path): String =
        if (path == "~" || path.startsWith("~/")) home.toString() + path.drop(1) else path

    // endregion
}
