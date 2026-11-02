package dev.tablegrip.core.connection

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DatabaseFilesTest {
    @TempDir lateinit var dir: Path

    private fun sqliteFile(name: String): Path {
        val file = dir.resolve(name)
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().use { s -> s.execute("CREATE TABLE t (id INTEGER)") } }
        return file
    }

    @Test
    fun `files are recognised by content, not name`() {
        assertEquals(DatabaseType.SQLite, DatabaseFiles.detect(sqliteFile("chinook")))
        assertNull(DatabaseFiles.detect(dir.resolve("notes.db").apply { writeText("just text") }))
        assertNull(DatabaseFiles.detect(dir.resolve("missing.db")))
        assertNull(DatabaseFiles.detect(dir))
    }

    @Test
    fun `missing and empty files are fine to open, other files are not`() {
        assertNull(DatabaseFiles.mismatch(dir.resolve("new.db"), DatabaseType.SQLite))
        assertNull(DatabaseFiles.mismatch(dir.resolve("empty.db").createFile(), DatabaseType.SQLite))
        assertNull(DatabaseFiles.mismatch(sqliteFile("app.sqlite"), DatabaseType.SQLite))
        val text = dir.resolve("data.db").apply { writeText("id,name\n1,a\n") }
        assertEquals("data.db isn't a SQLite database: its header doesn't match.", DatabaseFiles.mismatch(text, DatabaseType.SQLite))
    }

    @Test
    fun `pasted paths use the content too`() {
        val bare = sqliteFile("chinook")
        // No extension and not absolute-looking: only its header says it's SQLite.
        val relative = Path.of("").toAbsolutePath().relativize(bare).toString()
        assertEquals(DatabaseType.SQLite, ConnectionString.parse(relative).type)

        val text = dir.resolve("data.db").apply { writeText("not a database") }
        assertTrue(ConnectionString.parse(text.toString()).warnings.any { "header doesn't match" in it })
        assertTrue(ConnectionString.parse(Files.createTempFile(dir, "x", ".db").toString()).warnings.isEmpty())
    }
}
