package dev.tablegrip.app.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.chart.ChartAssistState
import dev.tablegrip.app.ui.TableGripTheme
import dev.tablegrip.app.ui.MainScreen
import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentRegistry
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.chart.Aggregate
import dev.tablegrip.core.chart.ChartSpec
import dev.tablegrip.core.chart.ChartType
import dev.tablegrip.core.chart.Measure
import dev.tablegrip.core.chart.TimeBucket
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.settings.SettingsStore
import java.io.File
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The chart view of console results over SQLite, with a scripted agent: nothing reaches a real one.
 * `-Psnapshot.dir=/tmp` also writes what it looks like.
 */
@OptIn(ExperimentalTestApi::class)
class ConsoleChartTest {
    private class ScriptedAgent(private vararg val answers: String) : AgentAdapter {
        val requests = mutableListOf<AgentRequest>()
        override val descriptor = AgentDescriptor("test:scripted", "Claude Code", AgentTransport.Cli)

        override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
            val answer = answers[requests.size]
            requests += request
            emit(AgentEvent.Completed(answer))
        }
    }

    private val regions = listOf("north", "south", "east", "west")

    private fun app(agent: AgentAdapter): AppState {
        val home = createTempDirectory("tablegrip-chart")
        val db = home.resolve("shop.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE TABLE orders (created_at TEXT, region TEXT, total REAL)") }
            connection.prepareStatement("INSERT INTO orders VALUES (?, ?, ?)").use { insert ->
                for (i in 0 until 600) {
                    insert.setString(1, "2024-%02d-%02d 10:00:00".format(i % 12 + 1, i % 28 + 1))
                    insert.setString(2, regions[(i / 12) % 4])
                    insert.setDouble(3, (i * 37 % 500).toDouble() + i % 12 * 40)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
        home.resolve("connections.json").writeText("""[{"id":"shop","name":"Shop","type":"SQLite","database":"$db"}]""")
        return AppState(
            settingsStore = SettingsStore(home.resolve("settings.json")),
            connectionStore = ConnectionStore(home.resolve("connections.json"), InMemorySecretStore()),
            history = QueryHistory(home.resolve("history.jsonl")),
            agentRegistry = AgentRegistry(listOf(object : AgentSource {
                override suspend fun discover(rescan: Boolean) = listOf(agent)
            })),
        )
    }

    private fun ComposeUiTest.run(app: AppState, sql: String): ConsoleTab {
        runOnUiThread {
            val console = app.workspace.selected as? ConsoleTab
            if (console == null) app.workspace.openConsole(app.connections.profiles.single(), sql)
            else console.editor.edit { replace(0, length, sql) }
        }
        val console = app.workspace.selected as ConsoleTab
        val before = console.output.size
        runOnUiThread { console.run(RunMode.Current) }
        waitUntil(timeoutMillis = 10_000) { !console.running && console.output.size > before }
        return console
    }

    @Test
    fun `a result charts as suggested, keeps its chart when run again, and aggregates on the server`() {
        val app = app(ScriptedAgent())
        app.updateSettings { it.copy(maxResultRows = 400) }
        runComposeUiTest {
            setContent { TableGripTheme(isDark = true) { Box(Modifier.size(1300.dp, 900.dp)) { MainScreen(app) } } }
            val console = run(app, "SELECT * FROM orders")
            val result = console.results.single()
            assertTrue(result.rows.truncated)
            onNodeWithText("Chart").performClick()
            waitUntil(timeoutMillis = 10_000) { result.chart.frame != null }
            assertEquals(ChartSpec(ChartType.Line, "created_at", listOf(Measure("total", Aggregate.Sum)), series = "region", bucket = TimeBucket.Week), result.chart.spec)
            onNodeWithText("Drawn from the first 400 rows fetched; the query returns more.").assertExists()
            snapshot("line")

            // Edited, then run again: the new result opens as the same chart.
            val byMonth = ChartSpec(ChartType.Line, "created_at", listOf(Measure("total", Aggregate.Sum)), series = "region", bucket = TimeBucket.Month)
            runOnUiThread { result.chart.show(byMonth) }
            run(app, "SELECT * FROM orders")
            val again = console.results.single()
            assertEquals(ResultView.Chart, again.view)
            waitUntil(timeoutMillis = 10_000) { again.chart.frame != null }
            assertEquals(byMonth, again.chart.spec)

            // On the server: every row, grouped by month, as a new result beside the pinned original.
            onNodeWithText("Aggregate on server").performClick()
            waitUntil(timeoutMillis = 10_000) { !console.running && console.results.size == 2 }
            val aggregated = console.results.last()
            assertTrue(again.pinned)
            assertEquals(ResultView.Chart, aggregated.view)
            assertFalse(aggregated.rows.truncated)
            assertEquals(48, aggregated.rows.rows.size)
            waitUntil(timeoutMillis = 10_000) { aggregated.chart.frame != null }
            assertEquals(listOf(Measure("sum(total)")), aggregated.chart.spec?.y)
            assertEquals(aggregated.rows.rows.sumOf { it[2]!!.toDouble() }, aggregated.chart.frame!!.points.sumOf { it.y })
            snapshot("aggregated")
        }
        app.dispose()
    }

    @Test
    fun `the agent's chart is reviewed before it's drawn, and gets columns but no values`() {
        val agent =
            ScriptedAgent(
                "```json\n{\"type\": \"bar\", \"x\": \"region\", \"y\": [{\"column\": \"total\", \"aggregate\": \"sum\"}], \"series\": null, \"bucket\": null, " +
                    "\"horizontal\": true, \"explanation\": \"Revenue per region.\", \"assumptions\": [\"total is the order's revenue\"]}\n```"
            )
        val app = app(agent)
        runComposeUiTest {
            setContent { TableGripTheme(isDark = false) { Box(Modifier.size(1300.dp, 900.dp)) { MainScreen(app) } } }
            val console = run(app, "SELECT * FROM orders")
            val result = console.results.single()
            onNodeWithText("Chart").performClick()
            waitUntil(timeoutMillis = 10_000) { result.chart.frame != null }
            val suggested = result.chart.spec

            onNodeWithText("Describe a chart, e.g. revenue by month").performTextInput("revenue per region")
            onNodeWithText("revenue per region").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 10_000) { result.chart.assist is ChartAssistState.Proposed }
            assertEquals(suggested, result.chart.spec)
            val prompt = agent.requests.single().messages.single().text
            assertTrue("- region: category (TEXT), 4 distinct values" in prompt, prompt)
            assertFalse("north" in prompt, prompt)
            onNodeWithText("• total is the order's revenue").assertExists()
            snapshot("proposal")

            onNodeWithText("Apply").performClick()
            assertEquals(null, result.chart.assist)
            assertEquals(ChartType.Bar, result.chart.spec?.type)
            waitUntil(timeoutMillis = 10_000) { result.chart.frame?.spec?.type == ChartType.Bar }
            assertEquals(regions.size, result.chart.frame!!.categories.size)
            snapshot("bars")
        }
        app.dispose()
    }

    private fun ComposeUiTest.snapshot(name: String) {
        val out = System.getProperty("snapshot.dir") ?: return
        waitForIdle()
        Thread.sleep(600)
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "chart-$name.png"))
    }
}
