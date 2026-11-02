package dev.tablegrip.core.session

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionStoreTest {
    private val file = Files.createTempDirectory("session").resolve("session.json")
    private val store = SessionStore(file)

    @Test
    fun `round-trips tabs`() {
        val session =
            Session(
                tabs =
                    listOf(
                        SessionTab.Console("pg", "SELECT 1;\n", file = "/q/a.sql", caret = 3, savedText = "SELECT 1;"),
                        SessionTab.Table("pg", schema = "public", name = "orders", filter = "status = 'paid'"),
                    ),
                selected = 1,
                leftTool = null,
                connectionId = "pg",
            )
        store.save(session)
        assertEquals(session, store.load())
    }

    @Test
    fun `a broken file is an empty session`() {
        file.writeText("{ not json")
        assertEquals(Session(), store.load())
    }
}
