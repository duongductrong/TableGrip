package dev.tablegrip.app.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.activity.ActivityTab
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.explain.PlanFlag
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Explain and Explain Analyze in the console against a real PostgreSQL:
 * `./gradlew :app:test --tests '*ExplainPlanTest*' -Pit.postgres=localhost:55432` (password `pw`); `-Psnapshot.dir=/tmp`
 * also writes what the plan looks like.
 */
@OptIn(ExperimentalTestApi::class)
class ExplainPlanTest {
    private val server = System.getProperty("it.postgres")?.split(":")

    private fun jdbc(): java.sql.Connection {
        val (host, port) = server!!
        return DriverLibrary.shared.load(DriverId.PostgreSQL)
            .connect("jdbc:postgresql://$host:$port/postgres", Properties().apply { setProperty("user", "postgres"); setProperty("password", "pw") })
    }

    private fun app(readOnly: Boolean = false, environment: Environment = Environment.None): AppState {
        val (host, port) = server!!
        val home = createTempDirectory("tablegrip-explain")
        val store = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore())
        store.save(
            ConnectionConfig(
                id = "pg", name = "Shop", type = DatabaseType.PostgreSQL, host = host, port = port.toInt(), database = "postgres", user = "postgres",
                environment = environment, readOnly = readOnly,
            ),
            password = "pw",
        )
        return AppState(SettingsStore(home.resolve("settings.json")), store, QueryHistory(home.resolve("history.jsonl")))
    }

    private fun ComposeUiTest.console(app: AppState, sql: String): ConsoleTab {
        runOnUiThread { app.workspace.openConsole(app.connections.profiles.single(), sql) }
        return app.workspace.selected as ConsoleTab
    }

    private fun ComposeUiTest.await(console: ConsoleTab, action: () -> Unit) {
        val before = console.output.size
        runOnUiThread(action)
        waitUntil(timeoutMillis = 20_000) { !console.running && console.output.size > before }
    }

    private fun setUp() {
        jdbc().use { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                    DROP TABLE IF EXISTS explain_orders, explain_customers;
                    CREATE TABLE explain_customers (id int PRIMARY KEY, name text, country text) WITH (autovacuum_enabled = false);
                    CREATE TABLE explain_orders (id int PRIMARY KEY, customer_id int, total numeric) WITH (autovacuum_enabled = false);
                    INSERT INTO explain_customers SELECT g, 'c' || g, CASE WHEN g % 10 = 0 THEN 'VN' ELSE 'US' END FROM generate_series(1, 5000) g;
                    INSERT INTO explain_orders SELECT g, 1 + g % 5000, g % 300 FROM generate_series(1, 100000) g;
                    CREATE INDEX explain_orders_customer ON explain_orders (customer_id);
                    ANALYZE explain_customers, explain_orders;
                    -- Rows the statistics don't know about: the planner expects almost none of them.
                    INSERT INTO explain_customers SELECT g, 'z' || g, 'ZZ' FROM generate_series(5001, 35000) g;
                    """
                )
            }
        }
    }

    private val query = "SELECT c.name, count(o.id) FROM explain_customers c LEFT JOIN explain_orders o ON o.customer_id = c.id WHERE c.country = 'ZZ' GROUP BY c.name"

    @Test
    fun `explain shows the plan as a tree, analyze measures it, and writes roll back`() {
        server ?: return
        setUp()
        val app = app()
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1400.dp, 860.dp)) { MainScreen(app) } } }
            val console = console(app, query)
            assertNotNull(console.planEngine)

            await(console) { console.explain() }
            val estimated = console.results.single()
            val plan = assertNotNull(estimated.plan)
            assertFalse(plan.analyzed)
            assertEquals(ResultView.Plan, estimated.view)
            onNodeWithText("Estimated plan").assertExists()
            snapshot("estimated")

            await(console) { console.explainAnalyze() }
            val analyzed = console.results.single()
            val actual = assertNotNull(analyzed.plan)
            assertTrue(actual.analyzed)
            val scan = actual.root.walk().first { it.relation == "explain_customers c" }
            assertEquals(30000.0, scan.actualRows)
            assertTrue(PlanFlag.Misestimate in actual.insight(scan).flags)
            assertTrue(PlanFlag.LargeScan in actual.insight(scan).flags)
            // Selecting a step shows what the server reported about it.
            runOnUiThread { analyzed.selectedNode = scan }
            onNodeWithText("Rows Removed by Filter").assertExists()
            snapshot("analyzed")
            onNodeWithText("Text").performClick()
            onNodeWithText("\"Node Type\"", substring = true).assertExists()
            onNodeWithText("Plan").performClick()

            // A write asks first, runs, and is rolled back.
            runOnUiThread { console.editor.edit { replace(0, length, "UPDATE explain_orders SET total = total + 1 WHERE customer_id = 7") } }
            runOnUiThread { console.explainAnalyze() }
            val confirmation = assertNotNull(console.pendingConfirmation)
            assertTrue(confirmation.reasons.any { "rolls it back" in it }, confirmation.reasons.toString())
            await(console) { console.confirmPending() }
            val update = assertNotNull(console.results.single().plan)
            assertEquals("Update", update.root.operation)
            assertTrue(update.analyzed)
            assertTrue(console.output.any { it.text == "Rolled back what Explain Analyze changed" })
            jdbc().use { connection ->
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT sum(total) FROM explain_orders WHERE customer_id = 7").use { rs ->
                        rs.next()
                        assertEquals(jdbc().use { c -> c.createStatement().use { it.executeQuery("SELECT sum(g % 300) FROM generate_series(1, 100000) g WHERE 1 + g % 5000 = 7").use { r -> r.next(); r.getBigDecimal(1) } } }, rs.getBigDecimal(1))
                    }
                }
            }

            // In manual commit, Explain Analyze leaves the console in manual commit.
            runOnUiThread { console.changeAutoCommit(false) }
            waitUntil(timeoutMillis = 10_000) { !console.autoCommit }
            runOnUiThread { console.editor.edit { replace(0, length, query) } }
            await(console) { console.explainAnalyze() }
            assertFalse(console.autoCommit)
            assertNotNull(console.results.single().plan)

            // DDL isn't run.
            runOnUiThread { console.editor.edit { replace(0, length, "DROP TABLE explain_orders") } }
            await(console) { console.explainAnalyze() }
            assertTrue(console.output.last().isError)
            assertNull(console.pendingConfirmation)
        }
        app.dispose()
    }

    @Test
    fun `a read-only connection measures queries but refuses to run writes`() {
        server ?: return
        setUp()
        val app = app(readOnly = true, environment = Environment.Production)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1400.dp, 860.dp)) { MainScreen(app) } } }
            val console = console(app, query)
            await(console) { console.explainAnalyze() }
            assertTrue(assertNotNull(console.results.single().plan).analyzed)
            snapshot("light")

            runOnUiThread { console.editor.edit { replace(0, length, "DELETE FROM explain_orders WHERE id = 1") } }
            await(console) { console.explainAnalyze() }
            assertNull(console.pendingConfirmation)
            assertTrue(console.output.last().isError)
            assertTrue("read-only" in console.output.last().text)
            // Plain Explain of a write doesn't run it, so it's allowed.
            await(console) { console.explain() }
            assertEquals("Delete", assertNotNull(console.results.single().plan).root.operation)
        }
        app.dispose()
    }

    @Test
    fun `explain from the activity monitor opens the query with its plan`() {
        server ?: return
        setUp()
        val app = app()
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1400.dp, 860.dp)) { MainScreen(app) } } }
            runOnUiThread { app.workspace.openActivity(app.connections.profiles.single()) }
            val activity = app.workspace.selected as ActivityTab
            assertTrue(activity.canExplain)
            waitUntil(timeoutMillis = 15_000) { activity.snapshot != null }
            val session = activity.snapshot!!.sessions.first().copy(query = "SELECT * FROM explain_orders WHERE customer_id = \$1")
            runOnUiThread { activity.explainInConsole(session) }
            val console = app.workspace.selected as ConsoleTab
            waitUntil(timeoutMillis = 20_000) { !console.running && console.output.isNotEmpty() }
            // A generic plan needs PostgreSQL 16; older servers say why in the output.
            val plan = console.results.singleOrNull()?.plan
            if (plan != null) assertEquals("explain_orders", plan.root.walk().mapNotNull { it.relation }.first())
            else assertTrue(console.output.last().isError)
        }
        app.dispose()
    }

    private fun ComposeUiTest.snapshot(name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        Thread.sleep(600)
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "explain-$name.png"))
    }
}
