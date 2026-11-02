package dev.tablegrip.app.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.tablegrip.app.AppState
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ConnectionGroupingTest {
    private val home = createTempDirectory("tablegrip-groups")
    private val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore())

    private fun app(): AppState {
        store.save(ConnectionConfig("a", "Orders", DatabaseType.PostgreSQL, group = "Shop"), password = null)
        store.save(ConnectionConfig("b", "Cache", DatabaseType.Redis, group = "Shop"), password = null)
        store.save(ConnectionConfig("c", "Analytics", DatabaseType.ClickHouse), password = null)
        store.save(ConnectionConfig("d", "Local", DatabaseType.SQLite, favorite = true), password = null)
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = store,
            history = QueryHistory(home.resolve("history.jsonl")),
            driverLibrary = DriverLibrary(home.resolve("drivers"), useClasspath = false),
        )
    }

    private fun AppState.byId(id: String) = connections.byId(id)!!

    @Test
    fun `dropping on a group moves the connection there, out of Favorites, and saves it`() = runComposeUiTest {
        val app = app()
        val drag = ConnectionDragState(app)
        drag.register("group:Shop", Rect(0f, 0f, 200f, 20f), DropTarget.Group("Shop"))
        drag.register("zone:new", Rect(0f, 100f, 200f, 140f), DropTarget.NewGroup)

        drag.start(app.byId("d"), Offset(10f, 60f))
        assertNull(drag.hovered)
        drag.moveTo(Offset(10f, 10f))
        assertEquals(DropTarget.Group("Shop"), drag.hovered)
        drag.drop()

        assertEquals("Shop", app.byId("d").group)
        assertEquals(false, app.byId("d").favorite)
        assertTrue(!drag.dragging)
        waitUntil { store.load().first { it.id == "d" }.group == "Shop" }
    }

    @Test
    fun `a group isn't a target for connections already in it, and New group asks for a name`() {
        val app = app()
        val drag = ConnectionDragState(app)
        drag.register("group:Shop", Rect(0f, 0f, 200f, 20f), DropTarget.Group("Shop"))
        drag.register("zone:new", Rect(0f, 100f, 200f, 140f), DropTarget.NewGroup)

        drag.start(app.byId("a"), Offset(10f, 10f))
        assertNull(drag.hovered)
        drag.moveTo(Offset(10f, 120f))
        drag.drop()
        val request = assertNotNull(app.connections.groupRequest)
        assertTrue(request.newGroup)
        assertEquals(listOf("a"), request.configs.map { it.id })
    }

    @Test
    fun `selected connections are dragged together`() {
        val app = app()
        app.explorer.treeState.selectedKeys = setOf(ConnectionKey("a"), ConnectionKey("c"))
        val drag = ConnectionDragState(app)
        drag.start(app.byId("c"), Offset.Zero)
        assertEquals(setOf("a", "c"), drag.dragged.map { it.id }.toSet())
        drag.cancel()
        drag.start(app.byId("b"), Offset.Zero)
        assertEquals(listOf("b"), drag.dragged.map { it.id })
    }

    @Test
    fun `renaming merges groups and ungrouping moves members to the top level`() {
        val app = app()
        app.connections.moveToGroup(listOf(app.byId("c")), "Reports")
        app.connections.renameGroup("Reports", "Shop")
        assertEquals(listOf("Shop"), app.connections.groups)
        app.connections.ungroup("Shop")
        assertEquals(emptyList(), app.connections.groups)
        assertEquals("", app.byId("a").group)
    }
}
