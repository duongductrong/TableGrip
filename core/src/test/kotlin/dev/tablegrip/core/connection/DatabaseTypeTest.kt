package dev.tablegrip.core.connection

import dev.tablegrip.core.dialect.SqlDialect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class DatabaseTypeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `profiles saved when engines were an enum still load`() {
        // The exact shape older versions wrote.
        val saved = """[{"id":"1","name":"shop","type":"MariaDB","host":"db","database":"shop"},{"id":"2","name":"local","type":"SQLite","database":"/tmp/a.db"}]"""
        val profiles = json.decodeFromString<List<ConnectionConfig>>(saved)
        assertSame(DatabaseType.MariaDB, profiles[0].type)
        assertSame(DatabaseType.SQLite, profiles[1].type)
        assertTrue("\"type\":\"MariaDB\"" in json.encodeToString(profiles[0]))
    }

    @Test
    fun `an unknown engine says so`() {
        val error = assertFailsWith<SerializationException> { json.decodeFromString<ConnectionConfig>("""{"id":"1","name":"x","type":"Oracle"}""") }
        assertTrue("Oracle" in error.message!!)
    }

    @Test
    fun `an engine in an existing family needs nothing but its descriptor`() {
        val cockroach = DatabaseType("CockroachDB", "CockroachDB", EngineFamily.PostgreSQL, 26257, listOf(DriverId.PostgreSQL))
        assertSame(SqlDialect.PostgreSQL, cockroach.dialect)
        val config = ConnectionConfig(id = "c", name = "crdb", type = cockroach, host = "db", database = "bank")
        assertEquals(DriverId.PostgreSQL, config.effectiveDriver)
        assertEquals("jdbc:postgresql://db/bank", JdbcUrls.build(config))
        assertEquals("public", cockroach.dialect!!.defaultNamespace(listOf("crdb_internal", "public"), "bank"))
    }

    @Test
    fun `every relational engine has a dialect, and every engine a default driver it allows`() {
        DatabaseType.entries.forEach { type ->
            // Relational engines have SQL; key-value stores don't.
            assertEquals(type.paradigm == Paradigm.Relational, type.dialect != null, "$type")
            assertTrue(ConnectionConfig(id = "x", name = "x", type = type).effectiveDriver in type.drivers)
        }
        assertEquals(DriverId.MariaDB, DatabaseType.MySQL.drivers.first(), "MySQL's own driver is GPL; it must not be the default")
    }
}
