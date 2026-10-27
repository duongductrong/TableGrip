package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.datagrok.core.driver.QueryResult
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

private val CellWidth = 160.dp
private val RowHeight = 24.dp

/** Minimal read-only result grid. To be replaced by a virtualized, editable grid. */
@Composable
internal fun ResultGrid(result: QueryResult.Rows) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn {
                stickyHeader {
                    Row(Modifier.background(JewelTheme.globalColors.panelBackground)) {
                        Cell("#", width = 48.dp, bold = true)
                        result.columns.forEach { Cell(it, bold = true) }
                    }
                    Divider(Orientation.Horizontal, Modifier.width(48.dp + CellWidth * result.columns.size))
                }
                itemsIndexed(result.rows) { index, row ->
                    Row {
                        Cell("${index + 1}", width = 48.dp)
                        row.forEach { Cell(it ?: "NULL") }
                    }
                }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        val suffix = if (result.truncated) " (truncated)" else ""
        Text(
            "${result.rows.size} row(s)$suffix in ${result.elapsedMillis} ms",
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = JewelTheme.globalColors.text.info,
        )
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp = CellWidth, bold: Boolean = false) {
    Box(Modifier.width(width).height(RowHeight).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) FontWeight.SemiBold else null,
            style = JewelTheme.editorTextStyle,
        )
    }
}
