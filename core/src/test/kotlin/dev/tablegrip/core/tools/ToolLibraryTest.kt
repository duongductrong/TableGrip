package dev.tablegrip.core.tools

import com.sun.net.httpserver.HttpServer
import dev.tablegrip.core.drivers.DriverDownloadException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ToolLibraryTest {
    @Test
    fun parsesVersions() {
        assertEquals("18.6", ToolLibrary.parseVersion("pg_dump (PostgreSQL) 18.6"))
        assertEquals("16.4", ToolLibrary.parseVersion("pg_restore (PostgreSQL) 16.4 (Homebrew)"))
        assertEquals("8.4.3", ToolLibrary.parseVersion("mysqldump  Ver 8.4.3 for macos14.4 on arm64 (Homebrew)"))
        assertEquals("10.19", ToolLibrary.parseVersion("mysqldump  Ver 10.19 Distrib 10.11.6-MariaDB, for debian-linux-gnu (x86_64)"))
        assertEquals("11.4.2", ToolLibrary.parseVersion("mariadb-dump from 11.4.2-MariaDB, client 10.19 for osx10.19 (arm64)"))
    }

    /** A fake `pg_dump` printing [version], in [dir]. */
    private fun fakeTool(dir: Path, version: String): Path {
        Files.createDirectories(dir)
        val file = dir.resolve(Tool.PgDump.executable)
        file.writeText("#!/bin/sh\necho 'pg_dump (PostgreSQL) $version'\n")
        file.toFile().setExecutable(true)
        return file
    }

    @Test
    fun picksTheNewestThatCanReadTheServerUnlessTheUserChose() {
        if (Platform.isWindows) return
        val home = createTempDirectory("tools")
        val old = fakeTool(home.resolve("old"), "14.2")
        val new = fakeTool(home.resolve("new"), "17.1")
        var custom = emptyMap<String, String>()
        val library = ToolLibrary(home.resolve("tools"), { custom }, { listOf(home.resolve("old"), home.resolve("new")) })
        assertEquals(listOf("14.2", "17.1"), library.find(Tool.PgDump).map { it.version })
        assertEquals(new.toRealPath(), library.best(Tool.PgDump, serverMajor = 16)!!.path)
        assertEquals(null, library.best(Tool.PgDump, serverMajor = 18), "no copy can dump a newer server")
        custom = mapOf(Tool.PgDump.name to old.toString())
        assertEquals(ToolSource.Custom, library.best(Tool.PgDump, serverMajor = 18)!!.source)
    }

    @Test
    fun installsOnlyTheListedFilesAndChecksTheHash() = runBlocking {
        val zip = createTempDirectory("zip").resolve("pkg.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            for ((name, text) in listOf("pgsql/bin/${Tool.PgDump.executable}" to "#!/bin/sh\necho 'pg_dump (PostgreSQL) 18.6'\n", "pgsql/lib/libpq.5.dylib" to "lib", "pgsql/bin/postgres" to "server")) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        val bytes = Files.readAllBytes(zip)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pkg.zip") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val pkg =
                ToolPackage(
                    "pg-test", ToolFamily.PostgreSQL, "18.6", Platform.id, "http://127.0.0.1:${server.address.port}/pkg.zip", sha, bytes.size.toLong(),
                    "test", "test", listOf("bin/${Tool.PgDump.executable}", "lib/libpq.5.dylib"), "pgsql/",
                )
            val root = createTempDirectory("tools")
            val library = ToolLibrary(root, { emptyMap() }, { emptyList() })
            assertFailsWith<DriverDownloadException> { library.install(pkg.copy(sha256 = "0".repeat(64))) }
            assertFalse(root.resolve("pg-test").exists(), "a bad download installs nothing")

            var progress = 0L
            library.install(pkg) { done, _ -> progress = done }
            assertEquals(bytes.size.toLong(), progress)
            assertEquals("lib", root.resolve("pg-test/lib/libpq.5.dylib").readText())
            assertFalse(root.resolve("pg-test/bin/postgres").exists(), "files not listed are left out")
            assertTrue(Files.list(root).use { files -> files.noneMatch { it.fileName.toString().endsWith(".part") } })
            library.uninstall(pkg)
            assertFalse(root.resolve("pg-test").exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun catalogCoversTheDesktopPlatforms() {
        assertTrue(ToolCatalog.packageFor(ToolFamily.PostgreSQL, "macos")!!.tools.containsAll(listOf(Tool.PgDump, Tool.PgRestore, Tool.Psql)))
        assertTrue(ToolCatalog.packageFor(ToolFamily.PostgreSQL, "windows-x64") != null)
        assertEquals(null, ToolCatalog.packageFor(ToolFamily.MySQL, "macos"), "MySQL's clients are GPL: never downloaded")
    }
}
