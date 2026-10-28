package dev.datagrok.core.drivers

import dev.datagrok.core.DatagrokPaths
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DriverId
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Driver
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Where a usable driver comes from. */
enum class DriverSource {
    /** On the app's own classpath; only ever the case in tests, since no driver ships with Datagrok. */
    Classpath,
    Downloaded,
    /** Jars the user pointed Datagrok at, for drivers it can't download. */
    UserJars,
}

/** What it takes to use a driver right now. */
sealed interface DriverResolution {
    val driver: DriverId

    /** Usable. [update] is a newer catalog version than the one installed. */
    data class Ready(override val driver: DriverId, val version: String?, val source: DriverSource, val update: LockedDriver? = null) : DriverResolution

    /** In the catalog but not installed: offer to download [locked]. */
    data class Download(override val driver: DriverId, val locked: LockedDriver) : DriverResolution

    /** Not in the catalog (GPL or a vendor's license): the user must supply the jar themselves. */
    data class BringYourOwn(override val driver: DriverId) : DriverResolution
}

class DriverNotInstalledException(val driver: DriverId) :
    SQLException("${driver.displayName} isn't installed. Edit the connection to download it or choose its jar.")

class DriverDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The JDBC drivers on this machine. Catalog drivers ([DriverLock]) download from a Maven repository, straight from the
 * publisher's artifacts and never through a Datagrok server, and must match the pinned checksums. Other drivers come
 * from jars the user chooses. Each driver loads in its own class loader.
 *
 * Layout under [root]: `<catalog id>/<version>/` holds the jars plus `driver.json`, the lock entry they were verified
 * against (written last, so a partial download never counts); `user-jars.json` maps drivers to the user's jars.
 */
class DriverLibrary(
    private val root: Path,
    private val lock: DriverLock = DriverLock.bundled(),
    /** Also use drivers found on the app's classpath. Production builds have none; tests do. */
    private val useClasspath: Boolean = true,
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build(),
) {
    /** Base URL of the Maven repository to download from, e.g. a company mirror; ends with `/`. */
    @Volatile
    var repository: String = MavenCentral
        set(value) {
            field = value.trim().ifEmpty { MavenCentral }.let { if (it.endsWith('/')) it else "$it/" }
        }

    private val loaded = ConcurrentHashMap<DriverId, Driver>()
    private val profileDrivers = ConcurrentHashMap<List<String>, Driver>()
    private val installs = ConcurrentHashMap<String, Mutex>()
    private val _revision = MutableStateFlow(0)

    /** Changes whenever a driver is installed, removed or pointed at other jars, so UIs can re-resolve. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun locked(driver: DriverId): LockedDriver? = driver.catalogId?.let(lock::byId)

    fun resolve(driver: DriverId): DriverResolution {
        if (useClasspath && classpathDriverClass(driver) != null) return DriverResolution.Ready(driver, null, DriverSource.Classpath)
        if (userJars(driver) != null) return DriverResolution.Ready(driver, null, DriverSource.UserJars)
        val locked = locked(driver) ?: return DriverResolution.BringYourOwn(driver)
        val installed = installed(locked.id)
        installed.firstOrNull { it.version == locked.version }?.let { return DriverResolution.Ready(driver, it.version, DriverSource.Downloaded) }
        installed.firstOrNull()?.let { return DriverResolution.Ready(driver, it.version, DriverSource.Downloaded, update = locked) }
        return DriverResolution.Download(driver, locked)
    }

    /** Like [resolve], but a [DriverId.Generic] profile's driver is its own jars. */
    fun resolve(config: ConnectionConfig): DriverResolution {
        if (config.effectiveDriver != DriverId.Generic) return resolve(config.effectiveDriver)
        val jars = config.driverJars.map(Path::of)
        return if (jars.isNotEmpty() && jars.all { it.isRegularFile() }) DriverResolution.Ready(DriverId.Generic, null, DriverSource.UserJars)
        else DriverResolution.BringYourOwn(DriverId.Generic)
    }

    fun isAvailable(driver: DriverId): Boolean = resolve(driver) is DriverResolution.Ready

    /** The profile's driver: its [ConnectionConfig.driverJars] for [DriverId.Generic], the shared one otherwise. */
    fun load(config: ConnectionConfig): Driver {
        val driver = config.effectiveDriver
        if (driver != DriverId.Generic) return load(driver)
        if (config.driverJars.isEmpty()) throw DriverNotInstalledException(driver)
        return profileDrivers.computeIfAbsent(config.driverJars) { jars -> instantiate(driver, jars.map(Path::of), discover = true) }
    }

    /** Checks that [jars] hold a JDBC driver, returning its class name. */
    suspend fun inspectJars(jars: List<Path>): String =
        withContext(Dispatchers.IO) {
            jars.firstOrNull { !it.isRegularFile() }?.let { throw IOException("$it doesn't exist.") }
            instantiate(DriverId.Generic, jars, discover = true).javaClass.name
        }

    /** The driver, loading it on first use (blocking: it may verify jars). */
    fun load(driver: DriverId): Driver = loaded.computeIfAbsent(driver, ::open)

    private fun open(driver: DriverId): Driver {
        if (useClasspath) classpathDriverClass(driver)?.let { return it.getDeclaredConstructor().newInstance() as Driver }
        userJars(driver)?.let { return instantiate(driver, it, discover = true) }
        val locked = locked(driver) ?: throw DriverNotInstalledException(driver)
        val manifest = installed(locked.id).let { all -> all.firstOrNull { it.version == locked.version } ?: all.firstOrNull() } ?: throw DriverNotInstalledException(driver)
        val dir = versionDir(manifest)
        for (artifact in manifest.artifacts) {
            val jar = dir.resolve(artifact.fileName)
            if (!jar.isRegularFile() || sha256(jar) != artifact.sha256) {
                throw SQLException("${driver.displayName} ${manifest.version} is damaged: ${artifact.fileName} doesn't match its checksum. Download it again.")
            }
        }
        return instantiate(driver, manifest.artifacts.map { dir.resolve(it.fileName) }, discover = false, className = manifest.className)
    }

    private fun instantiate(driver: DriverId, jars: List<Path>, discover: Boolean, className: String = driver.className): Driver {
        val loader = DriverClassLoader("datagrok-driver-${driver.name}", jars.map { it.toUri().toURL() }, DriverLibrary::class.java.classLoader)
        val candidates = (if (discover) listOf(className) + loader.registeredDrivers() else listOf(className)).filter { it.isNotBlank() }.distinct()
        for (name in candidates) {
            val type = runCatching { Class.forName(name, true, loader) }.getOrNull() ?: continue
            // The loader falls back to the app's classes; a driver found there isn't from these jars.
            if (type.classLoader === loader && Driver::class.java.isAssignableFrom(type)) return type.getDeclaredConstructor().newInstance() as Driver
        }
        loader.close()
        throw SQLException("No JDBC driver found in ${jars.joinToString { it.fileName.toString() }}" + (if (className.isBlank()) "." else " (looked for $className)."))
    }

    private fun classpathDriverClass(driver: DriverId): Class<*>? =
        runCatching { Class.forName(driver.className, false, DriverLibrary::class.java.classLoader) }.getOrNull()

    // region Downloads

    /**
     * Downloads [driver]'s catalog version from [repository], verifying every jar's size and SHA-256 before it's kept.
     * [onProgress] gets bytes done and the total. Concurrent calls for the same driver share one download.
     */
    suspend fun install(driver: DriverId, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val locked = locked(driver) ?: throw DriverDownloadException("${driver.displayName} isn't in Datagrok's driver catalog; choose its jar instead.")
        installs.computeIfAbsent(locked.id) { Mutex() }.withLock {
            if (installed(locked.id).any { it.version == locked.version }) return
            withContext(Dispatchers.IO) {
                val dir = versionDir(locked).createDirectories()
                var done = 0L
                for (artifact in locked.artifacts) {
                    val target = dir.resolve(artifact.fileName)
                    if (!(target.isRegularFile() && sha256(target) == artifact.sha256)) {
                        download(artifact, target) { onProgress(done + it, locked.size) }
                    }
                    done += artifact.size
                    onProgress(done, locked.size)
                }
                dir.resolve(Manifest).writeText(DriverLock.json.encodeToString(LockedDriver.serializer(), locked))
            }
        }
        loaded.remove(driver)
        _revision.update { it + 1 }
    }

    private suspend fun download(artifact: LockedArtifact, target: Path, onProgress: (Long) -> Unit) {
        val url = repository + artifact.path
        val temp = Files.createTempFile(target.parent, artifact.fileName, ".part")
        try {
            val response =
                try {
                    http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
                } catch (e: IOException) {
                    throw DriverDownloadException("Couldn't reach $repository: ${e.message ?: e.javaClass.simpleName}", e)
                }
            response.body().use { body ->
                if (response.statusCode() != 200) throw DriverDownloadException("$url returned HTTP ${response.statusCode()}.")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = body.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > artifact.size) throw DriverDownloadException("${artifact.fileName} is larger than expected; refusing it.")
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(read)
                    }
                }
                val sha = digest.digest().toHex()
                if (read != artifact.size || sha != artifact.sha256) {
                    throw DriverDownloadException("${artifact.fileName} from $repository doesn't match the expected checksum; refusing it.")
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.deleteIfExists()
        }
    }

    /**
     * Installs catalog drivers from jars already on disk, for machines without access to a Maven repository. Looks in
     * [folder] and its subfolders (so a copied `~/.m2/repository` works); jars that don't match the checksums are
     * ignored. Returns the drivers installed.
     */
    suspend fun importFrom(folder: Path): List<DriverId> =
        withContext(Dispatchers.IO) {
            val byName = Files.walk(folder, 12).use { paths -> paths.filter { it.isRegularFile() && it.toString().endsWith(".jar") }.toList() }.groupBy { it.fileName.toString() }
            val imported =
                DriverId.entries.filter { driver ->
                    val locked = locked(driver) ?: return@filter false
                    if (installed(locked.id).any { it.version == locked.version }) return@filter false
                    val sources = locked.artifacts.map { artifact -> byName[artifact.fileName].orEmpty().firstOrNull { sha256(it) == artifact.sha256 } }
                    if (sources.any { it == null }) return@filter false
                    val dir = versionDir(locked).createDirectories()
                    locked.artifacts.zip(sources).forEach { (artifact, source) -> Files.copy(source!!, dir.resolve(artifact.fileName), StandardCopyOption.REPLACE_EXISTING) }
                    dir.resolve(Manifest).writeText(DriverLock.json.encodeToString(LockedDriver.serializer(), locked))
                    loaded.remove(driver)
                    true
                }
            if (imported.isNotEmpty()) _revision.update { it + 1 }
            imported
        }

    // endregion

    // region User jars

    /**
     * Uses [jars] for [driver] from now on, after checking they contain a JDBC driver. Datagrok keeps the paths, not
     * copies: the jars stay the user's.
     */
    suspend fun useJars(driver: DriverId, jars: List<Path>) =
        withContext(Dispatchers.IO) {
            require(jars.isNotEmpty()) { "Choose at least one jar." }
            jars.firstOrNull { !it.isRegularFile() }?.let { throw IOException("$it doesn't exist.") }
            instantiate(driver, jars, discover = true)
            writeUserJars(readUserJars() + (driver.name to jars.map { it.toAbsolutePath().toString() }))
            loaded.remove(driver)
            _revision.update { it + 1 }
        }

    suspend fun forgetJars(driver: DriverId) =
        withContext(Dispatchers.IO) {
            writeUserJars(readUserJars() - driver.name)
            loaded.remove(driver)
            _revision.update { it + 1 }
        }

    private fun userJars(driver: DriverId): List<Path>? = readUserJars()[driver.name]?.map(Path::of)?.takeIf { jars -> jars.all { it.exists() } }

    @Serializable
    private data class UserJarsFile(val drivers: Map<String, List<String>> = emptyMap())

    private fun readUserJars(): Map<String, List<String>> =
        runCatching { DriverLock.json.decodeFromString(UserJarsFile.serializer(), root.resolve(UserJarsName).readText()).drivers }.getOrDefault(emptyMap())

    private fun writeUserJars(drivers: Map<String, List<String>>) {
        root.createDirectories()
        root.resolve(UserJarsName).writeText(DriverLock.json.encodeToString(UserJarsFile.serializer(), UserJarsFile(drivers)))
    }

    // endregion

    /** Complete installs of a catalog driver, newest first. */
    private fun installed(id: String): List<LockedDriver> {
        val dir = root.resolve(id)
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
            .map { it.resolve(Manifest) }
            .filter { it.isRegularFile() }
            .sortedByDescending { it.getLastModifiedTime() }
            .mapNotNull { runCatching { DriverLock.json.decodeFromString(LockedDriver.serializer(), it.readText()) }.getOrNull() }
    }

    private fun versionDir(driver: LockedDriver): Path = root.resolve(driver.id).resolve(driver.version)

    companion object {
        const val MavenCentral = "https://repo1.maven.org/maven2/"
        private const val Manifest = "driver.json"
        private const val UserJarsName = "user-jars.json"

        /** The app's library, under [DatagrokPaths.drivers]. */
        val shared: DriverLibrary by lazy { DriverLibrary(DatagrokPaths.drivers) }

        internal fun sha256(file: Path): String =
            file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
                digest.digest().toHex()
            }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
