package dev.datagrok.core

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.driver.Database
import dev.datagrok.core.drivers.DriverLibrary
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
        assertEquals(DatabaseType.DuckDB, dev.datagrok.core.connection.DatabaseFiles.detect(file))
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
