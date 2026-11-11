package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.drivers.DriverLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The generic family, against real in-process engines. */
class EmbeddedEnginesTest {
    private fun config(type: DatabaseType, database: String = "mem:${UUID.randomUUID()}") =
        ConnectionConfig(id = UUID.randomUUID().toString(), name = type.id, type = type, database = database)

    private fun contract(config: ConnectionConfig, drivers: DriverLibrary = DriverLibrary(Files.createTempDirectory("drivers"))) = runBlocking<Unit> {
        Database.connect(config, password = null, drivers = drivers).use { RelationalContract(it).run() }
    }

    @Test fun h2() = contract(config(DatabaseType.H2))

    @Test fun hsqldb() = contract(config(DatabaseType.HSQLDB))

    @Test fun derby() = contract(config(DatabaseType.Derby))

    @Test fun duckdb() = contract(config(DatabaseType.DuckDB))

    @Test
    fun `duckdb files are recognised and reopened`() {
        val file = Files.createTempDirectory("duck").resolve("shop.duckdb")
        contract(config(DatabaseType.DuckDB, file.toString()))
        assertEquals(DatabaseType.DuckDB, dev.tablegrip.core.connection.DatabaseFiles.detect(file))
    }

    /** UCanAccess creates the file when asked to; Access writes timestamps as `#…#`. */
    @Test
    fun access() = runBlocking<Unit> {
        val file = Files.createTempDirectory("access").resolve("shop.accdb")
        val config = config(DatabaseType.Access, file.toString()).copy(properties = mapOf("newDatabaseVersion" to "V2010"))
        Database.connect(config, password = null, drivers = DriverLibrary(Files.createTempDirectory("drivers"))).use {
            RelationalContract(it).run()
            DialectSmoke(it, at = "#2024-05-15 13:45:00#").run()
        }
        assertEquals(DatabaseType.Access, dev.tablegrip.core.connection.DatabaseFiles.detect(file))
    }

    @Test
    fun `h2 file databases open from a path`() {
        val file = Files.createTempDirectory("h2").resolve("shop")
        contract(config(DatabaseType.H2, file.toString()))
        assertTrue(Files.exists(Path.of("$file.mv.db")))
    }

    @Test
    fun `any driver works from the profile's own jars and URL`() {
        // A library that sees no drivers on the classpath: the profile's jar is the only source.
        val drivers = DriverLibrary(Files.createTempDirectory("drivers"), useClasspath = false)
        val h2Jar = Path.of(Class.forName("org.h2.Driver").protectionDomain.codeSource.location.toURI())
        val config =
            ConnectionConfig(
                id = "other",
                name = "other",
                type = DatabaseType.OtherJdbc,
                driver = DriverId.Generic,
                url = "jdbc:h2:mem:other-${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                driverJars = listOf(h2Jar.toString()),
            )
        contract(config, drivers)
        assertEquals("org.h2.Driver", runBlocking { drivers.inspectJars(listOf(h2Jar)) })
    }
}
