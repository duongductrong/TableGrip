package dev.datagrok.core.queries

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryLibraryTest {
    private val library = QueryLibrary(Files.createTempDirectory("queries"))

    @Test
    fun `saves into folders and lists by folder and name`() {
        library.save("Top users", "", "SELECT 1", connection = null)
        library.save("monthly", "reports/finance", "SELECT 2", connection = "Prod DB")
        val queries = library.list()
        assertEquals(listOf("" to "Top users", "reports/finance" to "monthly"), queries.map { it.folder to it.name })
        assertEquals("Prod DB", queries[1].connection)
        assertEquals("-- connection: Prod DB\nSELECT 2", queries[1].file.readText())
        assertNull(queries[0].connection)
    }

    @Test
    fun `connection line is replaced or removed`() {
        assertEquals("-- connection: B\nSELECT 1", QueryLibrary.withConnection("-- connection: A\nSELECT 1", "B"))
        assertEquals("SELECT 1", QueryLibrary.withConnection("-- connection: A\nSELECT 1", null))
        assertEquals("-- just a comment\nSELECT 1", QueryLibrary.withConnection("-- just a comment\nSELECT 1", null))
    }

    @Test
    fun `names become safe file names`() {
        val query = library.save("a/b: c?", "../x", "SELECT 1", connection = null)
        assertEquals("a_b_ c_", query.name)
        assertTrue(query.file.startsWith(library.root))
    }

    @Test
    fun `rename moves the file and refuses to overwrite`() {
        val first = library.save("one", "", "SELECT 1", connection = null)
        library.save("two", "", "SELECT 2", connection = null)
        assertFailsWith<IllegalArgumentException> { library.rename(first, "two") }
        val moved = library.rename(first, "uno", "spanish")
        assertFalse(first.file.exists())
        assertEquals("spanish" to "uno", moved.folder to moved.name)
        library.delete(moved)
        assertEquals(listOf("two"), library.list().map { it.name })
    }
}
