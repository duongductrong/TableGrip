package dev.datagrok.app.files

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.datagrok.app.AppState
import dev.datagrok.core.connection.ConnectionStore
import dev.datagrok.core.connection.InMemorySecretStore
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.SettingsStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Data files opened in windows of their own, as from Finder: their sheets, editing them, and closing. */
@OptIn(ExperimentalTestApi::class)
class FileWindowsTest {
    private val home = createTempDirectory("datagrok-files")
    private var previousHome: String? = null

    @BeforeTest
    fun setHome() {
        previousHome = System.getProperty("datagrok.home")
        System.setProperty("datagrok.home", home.resolve("home").toString())
    }

    @AfterTest
    fun cleanUp() {
        previousHome?.let { System.setProperty("datagrok.home", it) } ?: System.clearProperty("datagrok.home")
        home.toFile().deleteRecursively()
    }

    private fun app() =
        AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
        )

    @Test
    fun `a csv opens in its window, saves edits to the file, and closing it disconnects`() {
        val csv = home.resolve("people.csv").apply { writeText("name,age\nAda,36\nAlan,41\n") }
        val app = app()
        runComposeUiTest {
            runOnUiThread { app.files.open(listOf(csv.toFile())) }
            waitUntil(timeoutMillis = 10_000) { app.files.windows.singleOrNull()?.selected?.model != null }
            val window = app.files.windows.single()
            assertEquals(listOf("people"), window.tables.map { it.name })
            assertFalse(app.mainWindowVisible, "a file opened on its own doesn't bring up the main window")
            // The file got a connection, so the main window's explorer lists it too.
            assertTrue(app.connections.profiles.any { it.id == window.connectionId })

            // Opening it again brings the same window up.
            runOnUiThread { app.files.open(listOf(csv.toFile())) }
            assertSame(window, app.files.windows.single())
            assertEquals(1, window.raiseRequests)

            val tab = window.selected!!
            runOnUiThread {
                tab.model!!.setValue(0, tab.model!!.columns.indexOfFirst { it.name == "age" }, "37")
                tab.submit()
            }
            waitUntil(timeoutMillis = 10_000) { !tab.hasChanges && csv.readText().contains("37") }
            assertEquals("name,age\nAda,37\nAlan,41\n", csv.readText())

            var closed = false
            runOnUiThread { app.files.requestClose(window) { closed = true } }
            waitUntil(timeoutMillis = 5_000) { closed }
            assertTrue(app.files.windows.isEmpty())
            assertNull(app.databases.dataFile(window.connectionId!!), "nothing else showed the file, so its connection closed")
        }
        app.dispose()
    }

    @Test
    fun `a workbook shows each sheet, and unsaved edits hold the window open until discarded`() {
        val book = home.resolve("book.xlsx").also(::workbook)
        val app = app()
        runComposeUiTest {
            runOnUiThread { app.files.open(listOf(book.toFile())) }
            waitUntil(timeoutMillis = 10_000) { app.files.windows.singleOrNull()?.selected?.model != null }
            val window = app.files.windows.single()
            assertEquals(listOf("Sales", "Notes"), window.tables.map { it.name })

            runOnUiThread { window.select(window.tables[1]) }
            waitUntil(timeoutMillis = 10_000) { window.selected?.model != null }
            val notes = window.selected!!
            assertEquals("Notes", notes.table.name)
            runOnUiThread { notes.model!!.setValue(0, notes.model!!.columns.indexOfFirst { it.name == "note" }, "changed") }

            var closed = false
            runOnUiThread { app.files.requestClose(window) { closed = true } }
            waitForIdle()
            assertFalse(closed, "the edit wasn't saved, so closing asks first")
            assertTrue(notes.pendingDiscard != null)
            runOnUiThread { notes.confirmDiscard() }
            waitUntil(timeoutMillis = 5_000) { closed }
            assertTrue("changed" !in sheet(book, "xl/worksheets/sheet2.xml"))
        }
        app.dispose()
    }

    private fun sheet(file: Path, name: String): String = ZipFile(file.toFile()).use { zip -> String(zip.getInputStream(zip.getEntry(name)).readAllBytes()) }

    private fun workbook(file: Path) {
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val worksheet = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"
        fun row(r: Int, vararg cells: String) =
            "<row r=\"$r\">" + cells.mapIndexed { i, text -> "<c r=\"${'A' + i}$r\" t=\"inlineStr\"><is><t>$text</t></is></c>" }.joinToString("") + "</row>"
        val parts =
            linkedMapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                    """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="$worksheet"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="$worksheet"/></Types>""",
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to
                    """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$main" xmlns:r="$rel"><sheets>""" +
                    """<sheet name="Sales" sheetId="1" r:id="rId1"/><sheet name="Notes" sheetId="2" r:id="rId2"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    """<Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main"><sheetData>${row(1, "item", "qty")}${row(2, "Pen", "3")}</sheetData></worksheet>""",
                "xl/worksheets/sheet2.xml" to """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$main"><sheetData>${row(1, "note")}${row(2, "hello")}</sheetData></worksheet>""",
            )
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
