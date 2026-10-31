package dev.datagrok.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.table.TableEditModel
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SqlDialect
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.core.driver.QueryResult
import dev.datagrok.core.driver.ResultColumn
import java.io.File
import java.sql.Types
import javax.imageio.ImageIO
import kotlin.test.Test
import org.jetbrains.jewel.foundation.theme.JewelTheme

/**
 * Renders the grid to PNGs for eyeballing changes without launching the app:
 * `./gradlew :app:test --tests '*GridSnapshotTest*' -Psnapshot.dir=/tmp`. Skipped otherwise.
 */
@OptIn(ExperimentalTestApi::class)
class GridSnapshotTest {
    @Test
    fun snapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        for (dark in listOf(true, false)) runComposeUiTest {
            val result =
                QueryResult.Rows(
                    columns =
                        listOf(
                            ResultColumn("id", "integer", Types.INTEGER),
                            ResultColumn("name", "text", Types.VARCHAR),
                            ResultColumn("email", "text", Types.VARCHAR),
                            ResultColumn("city", "text", Types.VARCHAR),
                            ResultColumn("total", "real", Types.REAL),
                        ),
                    rows = (1..30).map { listOf("$it", "Customer $it", "c$it@example.com", if (it % 3 == 0) null else "Hanoi", "${it * 37 % 1000}.5") },
                    truncated = false,
                    elapsedMillis = 1,
                )
            val model = TableEditModel(result, listOf("id"))
            model.setValue(1, 1, "Renamed customer")
            model.deleteRows(listOf(3))
            model.setValue(model.addRow(), 1, "New one")
            val state = DataGridState()
            setContent {
                DatagrokTheme(isDark = dark) {
                    Box(Modifier.size(900.dp, 420.dp).background(JewelTheme.globalColors.panelBackground)) {
                        DataGrid(model, state, sort = listOf(SortColumn("total", SortDirection.Desc)))
                    }
                }
            }
            waitForIdle()
            state.selection = GridSelection(GridCell(5, 1), GridCell(7, 2))
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "grid-${if (dark) "dark" else "light"}.png"))
        }
    }

    /** Typed editors: check boxes, a value that can't be saved, the date picker, and the value panel's boolean choice. */
    @Test
    fun editorsSnapshot() {
        val out = System.getProperty("snapshot.dir") ?: return
        for (dark in listOf(true, false)) runComposeUiTest {
            val columns =
                listOf(
                    ResultColumn("id", "int4", Types.INTEGER),
                    ResultColumn("active", "bool", Types.BIT),
                    ResultColumn("qty", "int2", Types.SMALLINT),
                    ResultColumn("due", "date", Types.DATE),
                    ResultColumn("token", "uuid", Types.OTHER),
                )
            val rows = (1..12).map { listOf("$it", if (it % 4 == 0) null else if (it % 2 == 0) "t" else "f", "${it * 3}", "2026-10-${10 + it}", "0b9e3b4a-7f6c-4a8e-9d2b-1c3e5f7a9b0$it".take(36)) }
            val model = TableEditModel(QueryResult.Rows(columns, rows, false, 1), listOf("id"), dialect = SqlDialect.PostgreSQLDialect())
            model.setValue(2, 2, "abc")
            model.setValue(1, 1, "true")
            val state = DataGridState()
            setContent {
                DatagrokTheme(isDark = dark) {
                    Row(Modifier.size(1100.dp, 420.dp).background(JewelTheme.globalColors.panelBackground)) {
                        Box(Modifier.weight(1f)) { DataGrid(model, state) }
                        Box(Modifier.width(320.dp)) { ValuePanel(model, state) }
                    }
                }
            }
            waitForIdle()
            state.selection = GridSelection(GridCell(1, 1), GridCell(1, 1))
            state.startEditing(GridCell(4, 3), "2026-10-14", popup = EditPopup.Picker)
            waitForIdle()
            val suffix = if (dark) "dark" else "light"
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(out, "editors-$suffix.png"))
            ImageIO.write(onAllNodes(isRoot())[1].captureToImage().toAwtImage(), "png", File(out, "picker-$suffix.png"))
        }
    }
}
