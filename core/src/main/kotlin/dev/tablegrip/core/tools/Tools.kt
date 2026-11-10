@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package dev.tablegrip.core.tools

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.TableGripPaths
import dev.tablegrip.core.drivers.DriverDownloadException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Which client tools a family of engines dumps and restores with. */
enum class ToolFamily(val displayName: String) {
    PostgreSQL("PostgreSQL"),
    MySQL("MySQL / MariaDB"),
}

/** A command-line client TableGrip runs for backups and restores; never bundled, found on the machine or downloaded. */
enum class Tool(val command: String, val family: ToolFamily, val purpose: String) {
    PgDump("pg_dump", ToolFamily.PostgreSQL, tr("backs up a PostgreSQL database")),
    PgRestore("pg_restore", ToolFamily.PostgreSQL, tr("restores custom and directory backups")),
    Psql("psql", ToolFamily.PostgreSQL, tr("restores plain SQL backups")),
    MySqlDump("mysqldump", ToolFamily.MySQL, tr("backs up a MySQL or MariaDB database")),
    MySql("mysql", ToolFamily.MySQL, tr("restores SQL backups")),
    MariaDbDump("mariadb-dump", ToolFamily.MySQL, tr("MariaDB's name for mysqldump")),
    MariaDb("mariadb", ToolFamily.MySQL, tr("MariaDB's name for the mysql client"));

    /** The file name on this OS. */
    val executable: String
        get() = if (Platform.isWindows) "$command.exe" else command
}

enum class ToolSource(val label: String) {
    /** Chosen by the user in Settings → Tools. */
    Custom(tr("Chosen by you")),
    /** Downloaded by TableGrip into `~/.tablegrip/tools`. */
    Downloaded(tr("Downloaded")),
    /** Already installed: on PATH or in a well-known install folder. */
    System(tr("Installed on this machine")),
}

/** A tool found on this machine. [version] is what `--version` printed, e.g. "18.6"; null if it couldn't run. */
data class InstalledTool(val tool: Tool, val path: Path, val version: String?, val source: ToolSource) {
    val major: Int?
        get() = version?.substringBefore('.')?.toIntOrNull()
}

/**
 * An official build TableGrip can download after the user agrees: the archive is checked against [sha256] and only
 * [files] are kept, under `tools/<id>/`, preserving their folders (the binaries find their libraries relatively).
 */
data class ToolPackage(
    val id: String,
    val family: ToolFamily,
    val version: String,
    val os: String,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val publisher: String,
    val license: String,
    /** Archive entries to keep; a trailing `*` keeps every entry with that prefix. */
    val files: List<String>,
    /** Prefix to strip from entries, so tools land in `bin/` and `lib/`. */
    val stripPrefix: String,
) {
    val tools: List<Tool>
        get() = Tool.entries.filter { it.family == family && files.any { f -> f.endsWith("bin/${it.executable}") } }
}

object Platform {
    private val osName = System.getProperty("os.name").lowercase()
    val isWindows = osName.startsWith("windows")
    val isMac = osName.startsWith("mac")
    val isLinux = !isWindows && !isMac

    /** The id packages are published for: `macos`, `windows-x64`, or `linux` (none yet). */
    val id: String
        get() = when {
            isMac -> "macos"
            isWindows -> "windows-x64"
            else -> "linux"
        }
}

/**
 * Official builds TableGrip can download. pg_dump reads servers back to 9.2 but not newer than itself, so one build of
 * the newest major serves every server. MySQL's clients are GPL, so TableGrip doesn't download them: the user installs
 * them (see [installHint]). Linux distributions package PostgreSQL's clients, which is how Linux users get them.
 */
object ToolCatalog {
    private val EdbPublisher = tr("EnterpriseDB, the PostgreSQL project's official binaries for macOS and Windows")
    private val PgLicense = tr("PostgreSQL License (permissive); bundled libraries under their own licenses")

    val packages: List<ToolPackage> =
        listOf(
            ToolPackage(
                id = "postgresql-18.6-2-macos",
                family = ToolFamily.PostgreSQL,
                version = "18.6",
                os = "macos",
                url = "https://get.enterprisedb.com/postgresql/postgresql-18.6-2-osx-binaries.zip",
                sha256 = "21c0432b3cb45ab763aab2b4efe9edfa91f658c3be09cddf272c7b52eb2aa3fc",
                downloadBytes = 445_683_554,
                publisher = EdbPublisher,
                license = PgLicense,
                // pg_dump, pg_restore and psql with the libraries they load, not the whole server (~23 MB of ~1 GB).
                files =
                    listOf("bin/pg_dump", "bin/pg_restore", "bin/psql") +
                        listOf(
                            "libpq.5.dylib", "libzstd.1.dylib", "libzstd.1.5.7.dylib", "liblz4.1.10.0.dylib", "libcrypto.3.dylib", "libz.1.3.2.dylib",
                            "libssl.3.dylib", "libgssapi_krb5.2.2.dylib", "libkrb5.3.3.dylib", "libk5crypto.3.1.dylib", "libcom_err.3.0.dylib",
                            "libkrb5support.1.1.dylib", "libedit.0.dylib",
                        ).map { "lib/$it" },
                stripPrefix = "pgsql/",
            ),
            ToolPackage(
                id = "postgresql-18.6-2-windows-x64",
                family = ToolFamily.PostgreSQL,
                version = "18.6",
                os = "windows-x64",
                url = "https://get.enterprisedb.com/postgresql/postgresql-18.6-2-windows-x64-binaries.zip",
                sha256 = "41bb1496f60666d3745ae8a855753b877d14fa9e34667f266867635b790fc323",
                downloadBytes = 343_811_176,
                publisher = EdbPublisher,
                license = PgLicense,
                // Windows finds DLLs next to the exe; pgAdmin's wxWidgets and ICU aren't needed by the clients.
                files =
                    listOf("bin/pg_dump.exe", "bin/pg_restore.exe", "bin/psql.exe") +
                        listOf(
                            "libpq.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll", "libintl-9.dll", "libiconv-2.dll", "libwinpthread-1.dll",
                            "liblz4.dll", "libzstd.dll", "zlib1.dll", "libcurl.dll",
                        ).map { "bin/$it" },
                stripPrefix = "pgsql/",
            ),
        )

    fun packageFor(family: ToolFamily, os: String = Platform.id): ToolPackage? = packages.firstOrNull { it.family == family && it.os == os }

    /** How to get a family's tools when TableGrip can't download them. */
    fun installHint(family: ToolFamily): String =
        when (family) {
            ToolFamily.PostgreSQL ->
                when {
                    Platform.isMac -> "Or install them yourself: brew install libpq (or Postgres.app)."
                    Platform.isWindows -> "Or install them yourself with the PostgreSQL installer from postgresql.org."
                    else -> tr("Install your distribution's PostgreSQL client package, e.g. sudo apt install postgresql-client (apt.postgresql.org has the newest).")
                }
            ToolFamily.MySQL ->
                when {
                    Platform.isMac -> tr("Install them with brew install mysql-client (or mariadb), then choose them here if they aren't found.")
                    Platform.isWindows -> tr("Install MySQL Shell & Utilities or MariaDB from their installers, then choose mysqldump here if it isn't found.")
                    else -> tr("Install your distribution's mysql-client or mariadb-client package.")
                }
        }
}

/**
 * Finds client tools ([Tool]) on this machine and installs official builds the user agreed to download. Lookup order:
 * the path the user chose, then downloads, then PATH and the usual install folders.
 */
class ToolLibrary(
    private val root: Path = TableGripPaths.home.resolve("tools"),
    /** Paths chosen in Settings → Tools, by [Tool.name]. */
    private val customPaths: () -> Map<String, String> = { emptyMap() },
    private val searchDirs: () -> List<Path> = ::defaultSearchDirs,
) {
    private val versions = java.util.concurrent.ConcurrentHashMap<Pair<Path, Long>, String>()

    val folder: Path
        get() = root

    /** Every copy of [tool] found, best source first. Blocking: runs `--version` (cached per file). */
    fun find(tool: Tool): List<InstalledTool> {
        val found = mutableListOf<InstalledTool>()
        customPaths()[tool.name]?.let(Path::of)?.takeIf { it.isRegularFile() }?.let { found += installed(tool, it, ToolSource.Custom) }
        installedPackages().forEach { pkg -> root.resolve(pkg.id).resolve("bin").resolve(tool.executable).takeIf { it.isRegularFile() }?.let { found += installed(tool, it, ToolSource.Downloaded) } }
        searchDirs().map { it.resolve(tool.executable) }.filter { it.isRegularFile() && (Platform.isWindows || it.isExecutable()) }
            .map { it.toRealPath() }.distinct()
            .forEach { path -> if (found.none { it.path == path }) found += installed(tool, path, ToolSource.System) }
        return found
    }

    /**
     * The copy to use for a server of [serverMajor]: the user's choice if any, else the newest that can read the
     * server (PostgreSQL's dump tools refuse servers newer than themselves).
     */
    fun best(tool: Tool, serverMajor: Int? = null): InstalledTool? {
        val all = find(tool)
        all.firstOrNull { it.source == ToolSource.Custom }?.let { return it }
        val usable = all.filter { it.version != null }
        val fitting = if (serverMajor != null && tool.family == ToolFamily.PostgreSQL) usable.filter { (it.major ?: 0) >= serverMajor } else usable
        return fitting.maxWithOrNull(compareBy<InstalledTool>({ it.major ?: 0 }, { it.source == ToolSource.Downloaded }))
    }

    private fun installed(tool: Tool, path: Path, source: ToolSource) = InstalledTool(tool, path, version(path), source)

    /** "18.6" from `pg_dump (PostgreSQL) 18.6`, `mysqldump  Ver 8.4.3 for macos…`, `mariadb-dump from 11.4.2-MariaDB`. */
    fun version(path: Path): String? {
        val key = path to runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        versions[key]?.let { return it }
        val output =
            runCatching {
                // `--version` prints one line, far less than a pipe holds, so waiting first can't deadlock; reading
                // first could hang forever on a binary that never exits.
                val process = ProcessBuilder(path.absolutePathString(), "--version").redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (Platform.isWindows) "NUL" else "/dev/null"))).start()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                process.inputStream.bufferedReader().readText()
            }.getOrNull() ?: return null
        return parseVersion(output)?.also { versions[key] = it }
    }

    /** Packages downloaded into [root]. */
    fun installedPackages(): List<ToolPackage> = ToolCatalog.packages.filter { root.resolve(it.id).resolve("bin").isDirectory() }

    fun packageSize(pkg: ToolPackage): Long = sizeOf(root.resolve(pkg.id))

    /**
     * Downloads [pkg], checks its SHA-256 and keeps only its [ToolPackage.files]. [onProgress] gets bytes downloaded and
     * the total. Cancel the coroutine to stop; nothing half-installed is left behind.
     */
    suspend fun install(pkg: ToolPackage, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            Files.createDirectories(root)
            val archive = Files.createTempFile(root, "${pkg.id}-", ".zip.part")
            val staging = root.resolve(".${pkg.id}.staging")
            try {
                download(pkg, archive, onProgress)
                staging.deleteRecursively()
                extract(pkg, archive, staging)
                val target = root.resolve(pkg.id)
                target.deleteRecursively()
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(archive)
                runCatching { staging.deleteRecursively() }
            }
        }

    private suspend fun download(pkg: ToolPackage, target: Path, onProgress: (Long, Long) -> Unit) {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
        val response = client.send(HttpRequest.newBuilder(URI(pkg.url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw DriverDownloadException(tr("Download of {0} failed: HTTP {1}", pkg.url, response.statusCode()))
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        response.body().use { input ->
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    onProgress(done, pkg.downloadBytes)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(pkg.sha256, ignoreCase = true)) {
            throw DriverDownloadException(tr("{0} doesn't match its pinned SHA-256 (got {1}). Nothing was installed.", pkg.url, actual))
        }
    }

    private fun extract(pkg: ToolPackage, archive: Path, target: Path) {
        ZipFile(archive.toFile()).use { zip ->
            val wanted = pkg.files.map { pkg.stripPrefix + it }.toSet()
            var kept = 0
            for (entry in zip.entries()) {
                if (entry.isDirectory || entry.name !in wanted) continue
                val relative = entry.name.removePrefix(pkg.stripPrefix)
                val out = target.resolve(relative).normalize()
                if (!out.startsWith(target)) throw IOException(tr("Archive entry escapes the install folder: {0}", entry.name))
                Files.createDirectories(out.parent)
                zip.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                if (relative.startsWith("bin/")) out.toFile().setExecutable(true)
                kept++
            }
            val missing = wanted.size - kept
            if (missing > 0) throw IOException(tr("{0} doesn't contain {1} of the expected files. Nothing was installed.", pkg.url, missing))
        }
    }

    suspend fun uninstall(pkg: ToolPackage) = withContext(Dispatchers.IO) { root.resolve(pkg.id).deleteRecursively() }

    companion object {
        private val VersionPattern = Regex("""(?:\)|Ver|Distrib|from)\s+(\d+\.\d+(?:\.\d+)?)""")

        internal fun parseVersion(output: String): String? =
            VersionPattern.find(output)?.groupValues?.get(1) ?: Regex("""\b(\d+\.\d+(?:\.\d+)?)\b""").find(output)?.groupValues?.get(1)

        /** PATH, then where Homebrew, Postgres.app, the EDB and MySQL installers and distributions put the clients. */
        fun defaultSearchDirs(): List<Path> {
            val path = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
            val extra =
                when {
                    Platform.isMac ->
                        listOf("/opt/homebrew/opt/libpq/bin", "/usr/local/opt/libpq/bin", "/opt/homebrew/bin", "/usr/local/bin", "/opt/homebrew/opt/mysql-client/bin", "/usr/local/opt/mysql-client/bin", "/usr/local/mysql/bin").map(Path::of) +
                            versionDirs(Path.of("/opt/homebrew/opt"), "postgresql@") + versionDirs(Path.of("/opt/homebrew/opt"), "mysql") +
                            versionDirs(Path.of("/Applications/Postgres.app/Contents/Versions"), "")
                    Platform.isWindows ->
                        versionDirs(Path.of("C:\\Program Files\\PostgreSQL"), "") + versionDirs(Path.of("C:\\Program Files\\MySQL"), "") +
                            versionDirs(Path.of("C:\\Program Files"), "MariaDB")
                    else -> versionDirs(Path.of("/usr/lib/postgresql"), "") + listOf(Path.of("/usr/bin"), Path.of("/usr/local/bin"))
                }
            return (path + extra).distinct()
        }

        /** The `bin` folder of each folder in [parent] whose name starts with [prefix], newest-looking names first. */
        private fun versionDirs(parent: Path, prefix: String): List<Path> =
            runCatching { parent.listDirectoryEntries().filter { it.name.startsWith(prefix) && it.isDirectory() }.sortedByDescending { it.name }.map { it.resolve("bin") } }
                .getOrDefault(emptyList())

        private fun sizeOf(dir: Path): Long =
            if (!dir.exists()) 0 else runCatching { dir.walk().filter { it.isRegularFile() }.sumOf { Files.size(it) } }.getOrDefault(0)
    }
}
