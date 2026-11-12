package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.SchemaObjectKind
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Functions, procedures and sequences on in-process engines; the server engines are in [StructureIntegrationTest]. */
class SchemaObjectsTest {
    private fun connect(type: DatabaseType, database: String) =
        runBlocking {
            Database.connect(
                ConnectionConfig(id = UUID.randomUUID().toString(), name = type.id, type = type, database = database),
                password = null,
                drivers = DriverLibrary(Files.createTempDirectory("drivers")),
            )
        }

    /** H2 has no reader of its own: routines come from JDBC metadata, sources from INFORMATION_SCHEMA. */
    @Test
    fun `h2 lists aliases through JDBC metadata`() = runBlocking<Unit> {
        connect(DatabaseType.H2, "mem:${UUID.randomUUID()}").use { database ->
            database.openSession().use { session ->
                session.execute("CREATE ALIAS MY_ABS FOR 'java.lang.Math.abs(int)'")
                session.execute("CREATE TABLE T (ID INT)")
            }
            val public = database.namespaces().single { it.schema == "PUBLIC" }
            val objects = database.schemaObjects(public)
            val abs = objects.single { it.ref.name == "MY_ABS" }
            assertEquals(SchemaObjectKind.Function, abs.ref.kind)
            val definition = database.schemaObjectDefinition(abs.ref)
            assertEquals("function", definition.properties.toMap()["kind"])
            assertTrue(definition.source.isNotBlank())
            // Built-in functions aren't in the user's schema.
            assertTrue(objects.none { it.ref.name.equals("ABS", ignoreCase = true) })
            assertTrue(database.tables(public).none { it.materialized })
        }
    }

    @Test
    fun `sqlite has no routines or sequences`() = runBlocking<Unit> {
        val file = Files.createTempDirectory("sqlite").resolve("shop.db")
        connect(DatabaseType.SQLite, file.toString()).use { database ->
            database.openSession().use { it.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)") }
            assertEquals(emptyList(), database.schemaObjects(Namespace(null, null)))
            assertEquals(listOf(false), database.tables(Namespace(null, null)).map { it.materialized })
        }
    }
}
