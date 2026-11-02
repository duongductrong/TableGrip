package dev.tablegrip.core.drivers

import com.sun.net.httpserver.HttpServer
import dev.tablegrip.core.connection.DriverId
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DriverLibraryTest {
    /** The PostgreSQL driver jar from the test classpath, served as if it were on Maven Central. */
    private val pgJar = jarOf("org.postgresql.Driver")
    private val pgPath = "org/postgresql/postgresql/42.0.0/${pgJar.fileName}"
    private val served = mutableMapOf(pgPath to pgJar.readBytes())
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = served[exchange.requestURI.path.removePrefix("/")]
                if (body == null) exchange.sendResponseHeaders(404, -1)
                else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                exchange.close()
            }
            start()
        }
    private val root = Files.createTempDirectory("drivers")

    private fun lock(version: String = "42.0.0", sha256: String = DriverLibrary.sha256(pgJar)) =
        DriverLock(
            listOf(
                LockedDriver(
                    id = "postgresql",
                    name = "PostgreSQL JDBC Driver",
                    version = version,
                    className = "org.postgresql.Driver",
                    license = "BSD-2-Clause",
                    tier = LicenseTier.Permissive,
                    licenseUrl = "https://example.invalid/license",
                    sourceUrl = "https://example.invalid/source",
                    artifacts = listOf(LockedArtifact("org.postgresql:postgresql:$version", pgPath, sha256, pgJar.fileSize(), "BSD-2-Clause")),
                )
            )
        )

    private fun library(lock: DriverLock = lock()) =
        DriverLibrary(root, lock, useClasspath = false).apply { repository = "http://127.0.0.1:${server.address.port}" }

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `a catalog driver downloads, verifies and loads in its own class loader`() = runBlocking<Unit> {
        val library = library()
        assertIs<DriverResolution.Download>(library.resolve(DriverId.PostgreSQL))
        assertFailsWith<DriverNotInstalledException> { library.load(DriverId.PostgreSQL) }

        var progress = 0L
        library.install(DriverId.PostgreSQL) { done, _ -> progress = done }

        assertEquals(pgJar.fileSize(), progress)
        assertEquals(DriverResolution.Ready(DriverId.PostgreSQL, "42.0.0", DriverSource.Downloaded), library.resolve(DriverId.PostgreSQL))
        val driver = library.load(DriverId.PostgreSQL)
        assertIs<DriverClassLoader>(driver.javaClass.classLoader, "the driver must not come from the app's classpath")
        assertTrue(driver.acceptsURL("jdbc:postgresql://localhost/db"))
        assertEquals(1, library.revision.value)
    }

    @Test
    fun `a download that doesn't match the checksum is refused`() = runBlocking<Unit> {
        val library = library(lock(sha256 = "0".repeat(64)))
        val error = assertFailsWith<DriverDownloadException> { library.install(DriverId.PostgreSQL) }
        assertTrue("checksum" in error.message!!, error.message)
        assertIs<DriverResolution.Download>(library.resolve(DriverId.PostgreSQL))
    }

    @Test
    fun `a missing artifact reports the HTTP status`() = runBlocking<Unit> {
        served.clear()
        val error = assertFailsWith<DriverDownloadException> { library().install(DriverId.PostgreSQL) }
        assertTrue("404" in error.message!!, error.message)
    }

    @Test
    fun `a jar changed after download is caught when loading`() = runBlocking<Unit> {
        val library = library()
        library.install(DriverId.PostgreSQL)
        root.resolve("postgresql/42.0.0/${pgJar.fileName}").writeBytes(byteArrayOf(1, 2, 3))
        assertFailsWith<SQLException> { library.load(DriverId.PostgreSQL) }
        Unit
    }

    @Test
    fun `an older install stays usable and offers the catalog version`() = runBlocking<Unit> {
        library(lock(version = "41.0.0")).install(DriverId.PostgreSQL)
        val resolution = library(lock(version = "42.0.0")).resolve(DriverId.PostgreSQL)
        assertIs<DriverResolution.Ready>(resolution)
        assertEquals("41.0.0", resolution.version)
        assertEquals("42.0.0", resolution.update?.version)
    }

    @Test
    fun `old versions and whole drivers can be removed`() = runBlocking<Unit> {
        library(lock(version = "41.0.0")).install(DriverId.PostgreSQL)
        val library = library(lock(version = "42.0.0"))
        library.install(DriverId.PostgreSQL)
        assertEquals(setOf("41.0.0", "42.0.0"), library.installedVersions(DriverId.PostgreSQL).toSet())
        // Two copies of the jar, plus each version's driver.json.
        assertTrue(library.downloadedSize(DriverId.PostgreSQL) > 2 * pgJar.fileSize())

        assertEquals(listOf("41.0.0"), library.removeOldVersions(DriverId.PostgreSQL))
        assertEquals(listOf("42.0.0"), library.installedVersions(DriverId.PostgreSQL))
        assertEquals(DriverResolution.Ready(DriverId.PostgreSQL, "42.0.0", DriverSource.Downloaded), library.resolve(DriverId.PostgreSQL))

        library.uninstall(DriverId.PostgreSQL)
        assertEquals(emptyList(), library.installedVersions(DriverId.PostgreSQL))
        assertEquals(0, library.downloadedSize(DriverId.PostgreSQL))
        assertIs<DriverResolution.Download>(library.resolve(DriverId.PostgreSQL))
        assertFailsWith<DriverNotInstalledException> { library.load(DriverId.PostgreSQL) }
        Unit
    }

    @Test
    fun `drivers import from a folder without network access`() = runBlocking<Unit> {
        val folder = Files.createTempDirectory("m2").resolve("org/postgresql").createDirectories()
        pgJar.copyTo(folder.resolve(pgJar.fileName))
        server.stop(0)
        val library = library()
        assertEquals(listOf(DriverId.PostgreSQL), library.importFrom(folder.parent.parent))
        assertIs<DriverResolution.Ready>(library.resolve(DriverId.PostgreSQL))
        library.load(DriverId.PostgreSQL)
        Unit
    }

    @Test
    fun `drivers outside the catalog are only used from the user's jars`() = runBlocking<Unit> {
        val library = library()
        assertIs<DriverResolution.BringYourOwn>(library.resolve(DriverId.MySQL))
        assertFailsWith<DriverDownloadException> { library.install(DriverId.MySQL) }

        library.useJars(DriverId.MySQL, listOf(jarOf("com.mysql.cj.jdbc.Driver")))
        assertEquals(DriverResolution.Ready(DriverId.MySQL, null, DriverSource.UserJars), library.resolve(DriverId.MySQL))
        assertTrue(library.load(DriverId.MySQL).acceptsURL("jdbc:mysql://localhost/db"))
        // Remembered across restarts.
        assertIs<DriverResolution.Ready>(library().resolve(DriverId.MySQL))

        library.forgetJars(DriverId.MySQL)
        assertIs<DriverResolution.BringYourOwn>(library.resolve(DriverId.MySQL))
    }

    @Test
    fun `jars without a JDBC driver are rejected`() = runBlocking<Unit> {
        val library = library()
        assertFailsWith<SQLException> { library.useJars(DriverId.MySQL, listOf(jarOf("kotlin.Unit"))) }
        assertIs<DriverResolution.BringYourOwn>(library.resolve(DriverId.MySQL))
    }

    @Test
    fun `the bundled lockfile covers every downloadable driver`() {
        val lock = DriverLock.bundled()
        DriverId.entries.mapNotNull { it.catalogId }.forEach { id ->
            val driver = lock.byId(id) ?: error("$id is missing from drivers.lock.json")
            assertTrue(driver.artifacts.isNotEmpty())
            assertTrue(driver.artifacts.all { it.sha256.length == 64 })
        }
        assertEquals(LicenseTier.WeakCopyleft, lock.byId("mariadb")!!.tier)
        assertEquals(null, DriverId.MySQL.catalogId, "Connector/J is GPL: it must never be downloaded")
    }

    /** Opt-in (`-Pit.network=true`): the real catalog, downloaded from Maven Central, connects to a database. */
    @Test
    fun `catalog drivers download from Maven Central and connect`() = runBlocking<Unit> {
        if (System.getProperty("it.network") != "true") return@runBlocking
        val library = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)
        library.install(DriverId.SQLite)
        library.install(DriverId.PostgreSQL)
        library.install(DriverId.MariaDB)
        val sqlite = library.load(DriverId.SQLite)
        assertIs<DriverClassLoader>(sqlite.javaClass.classLoader)
        sqlite.connect("jdbc:sqlite::memory:", java.util.Properties())!!.use { connection ->
            connection.createStatement().use { assertTrue(it.executeQuery("SELECT sqlite_version()").next()) }
        }
        assertTrue(library.load(DriverId.PostgreSQL).acceptsURL("jdbc:postgresql://localhost/db"))
        assertTrue(library.load(DriverId.MariaDB).acceptsURL("jdbc:mariadb://localhost/db"))
    }

    private fun jarOf(className: String): Path = Path.of(Class.forName(className).protectionDomain.codeSource.location.toURI())
}
