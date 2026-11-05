package dev.tablegrip.app.documents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.grid.DataGrid
import dev.tablegrip.app.grid.DataGridState
import dev.tablegrip.app.grid.GridActions
import dev.tablegrip.app.ui.TableGripTheme
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalTestApi::class)
class DocumentCopyTest {
    @Test
    fun `cmd-C copies the selected document cells`() = runComposeUiTest {
        val docs = listOf(
            Json.parseToJsonElement("""{"_id": 1, "customer": "ada"}""").jsonObject,
            Json.parseToJsonElement("""{"_id": 2, "customer": "linus"}""").jsonObject,
        )
        val model = DocumentEditModel(docs)
        val state = DataGridState()
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(model, state, actions = GridActions()) } } }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection("before"), null)
        onNodeWithText("linus").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        assertEquals("linus", Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor))
    }

    private val server = System.getProperty("it.mongo")?.split(":")

    @Test
    fun `cmd-C copies from the full document browser`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (host, port) = server
        val home = kotlin.io.path.createTempDirectory("tablegrip-mongo-copy")
        val config = dev.tablegrip.core.connection.ConnectionConfig(id = "mongo", name = "shop", type = dev.tablegrip.core.connection.DatabaseType.MongoDB, host = host, port = port.toInt())
        val store = dev.tablegrip.core.connection.ConnectionStore(home.resolve("connections.json"), dev.tablegrip.core.connection.InMemorySecretStore()).apply { save(config, null) }
        val app = dev.tablegrip.app.AppState(
            dev.tablegrip.core.settings.SettingsStore(home.resolve("settings.json")), store,
            dev.tablegrip.core.history.QueryHistory(home.resolve("history.jsonl")), dev.tablegrip.core.drivers.DriverLibrary(home.resolve("drivers")),
        )
        val db = "dg_copy_${System.currentTimeMillis()}"
        kotlinx.coroutines.runBlocking {
            dev.tablegrip.core.driver.DocumentStore.connect(config, null).use { it.command(db, "db.orders.insertMany([{_id: 1, customer: 'ada'}, {_id: 2, customer: 'linus'}])") }
        }
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1100.dp, 520.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 2 }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection("before"), null)
        onNodeWithText("linus").performMouseInput { click(center) }
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.C) } }
        waitForIdle()
        val copied = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
        val primary = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.A); pressKey(Key.C) } }
        waitForIdle()
        val all = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
        onNodeWithText("ada").performMouseInput { click(center) }
        onRoot().performKeyInput { withKeyDown(androidx.compose.ui.input.key.Key.ShiftLeft) { pressKey(Key.DirectionDown); pressKey(Key.DirectionRight) } }
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.C) } }
        waitForIdle()
        val range = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor)
        kotlinx.coroutines.runBlocking { dev.tablegrip.core.driver.DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
        assertEquals("linus", copied)
        assertEquals("1\tada\n2\tlinus", all, "cmd-A, cmd-C copies every cell")
        assertEquals("ada\nlinus", range, "a shift-extended range copies as a block")
    }

    @Test
    fun `cmd-V pastes into the editor of a new document`() = runComposeUiTest {
        val docs = listOf(Json.parseToJsonElement("""{"_id": 1, "customer": "ada"}""").jsonObject)
        val model = DocumentEditModel(docs)
        val state = DataGridState()
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(800.dp, 400.dp)) { DataGrid(model, state, actions = GridActions()) } } }
        val primary = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
        Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection("pasted"), null)
        val row = model.addRow()
        waitForIdle()
        state.select(dev.tablegrip.app.grid.GridCell(row, 1))
        state.startEditing(dev.tablegrip.app.grid.GridCell(row, 1), "", initial = "", popup = dev.tablegrip.app.grid.EditPopup.None)
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.V) } }
        waitForIdle()
        assertEquals("pasted", state.editText.text.toString())
    }

    @Test
    fun `cmd-V pastes into the Add field box as soon as it opens`() = runComposeUiTest {
        if (server == null) return@runComposeUiTest
        val (host, port) = server
        val home = kotlin.io.path.createTempDirectory("tablegrip-mongo-paste")
        val config = dev.tablegrip.core.connection.ConnectionConfig(id = "mongo", name = "shop", type = dev.tablegrip.core.connection.DatabaseType.MongoDB, host = host, port = port.toInt())
        val store = dev.tablegrip.core.connection.ConnectionStore(home.resolve("connections.json"), dev.tablegrip.core.connection.InMemorySecretStore()).apply { save(config, null) }
        val app = dev.tablegrip.app.AppState(
            dev.tablegrip.core.settings.SettingsStore(home.resolve("settings.json")), store,
            dev.tablegrip.core.history.QueryHistory(home.resolve("history.jsonl")), dev.tablegrip.core.drivers.DriverLibrary(home.resolve("drivers")),
        )
        val db = "dg_paste_${System.currentTimeMillis()}"
        kotlinx.coroutines.runBlocking {
            dev.tablegrip.core.driver.DocumentStore.connect(config, null).use { it.command(db, "db.orders.insertMany([{_id: 1, customer: 'ada'}])") }
        }
        val tab = DocumentBrowserTab(app, config, db, "orders")
        setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1100.dp, 520.dp)) { DocumentBrowserView(tab) } } }
        waitUntil(timeoutMillis = 10_000) { tab.documents.size == 1 }
        val primary = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
        Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection("nickname"), null)
        onNodeWithText("ada").performMouseInput { click(center) } // the grid has focus, as after copying a cell
        onNodeWithContentDescription("Add field").performClick()
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.V) } }
        waitForIdle()
        onNodeWithText("nickname").assertExists()
        kotlinx.coroutines.runBlocking { dev.tablegrip.core.driver.DocumentStore.connect(config, null).use { it.command(db, "db.dropDatabase()") } }
        app.dispose()
    }
}
