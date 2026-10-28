package dev.datagrok.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.datagrok.app.table.TableEditModel
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.core.dialect.SortColumn
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
}
