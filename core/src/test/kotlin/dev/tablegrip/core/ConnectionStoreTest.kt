package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.InMemorySecretStore
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ConnectionStoreTest {
    @TempDir lateinit var dir: Path

    private val pg = ConnectionConfig(id = "pg", name = "Local PG", type = DatabaseType.PostgreSQL, database = "app", user = "me")

    @Test
    fun `saves profiles to disk and passwords to the secret store only`() {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file, InMemorySecretStore())

        store.save(pg, password = "s3cret")

        assertEquals(listOf(pg), store.load())
        assertEquals("s3cret", store.password("pg"))
        assertFalse("s3cret" in file.readText())
    }

    @Test
    fun `updates in place, keeps password when null and clears it when empty`() {
        val store = ConnectionStore(dir.resolve("connections.json"), InMemorySecretStore())
        store.save(pg, password = "s3cret")

        val renamed = pg.copy(name = "Renamed")
        assertEquals(listOf(renamed), store.save(renamed, password = null))
        assertEquals("s3cret", store.password("pg"))

        store.save(renamed, password = "")
        assertNull(store.password("pg"))
    }

    @Test
    fun `delete removes profile and password`() {
        val store = ConnectionStore(dir.resolve("connections.json"), InMemorySecretStore())
        store.save(pg, password = "s3cret")

        assertEquals(emptyList(), store.delete("pg"))
        assertNull(store.password("pg"))
    }
}
