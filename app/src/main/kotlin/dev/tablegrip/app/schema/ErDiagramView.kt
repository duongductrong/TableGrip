package dev.tablegrip.app.schema

import dev.tablegrip.app.ui.theme.ColorTheme
import dev.tablegrip.app.ui.theme.LocalColorTheme
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.ConnectionLoadingPlaceholder
import dev.tablegrip.app.table.ErrorBanner
import dev.tablegrip.app.table.Separator
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.onPinch
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import dev.tablegrip.core.schema.ErBox
import dev.tablegrip.core.schema.ErEdge
import dev.tablegrip.core.schema.ErLayout
import kotlin.math.exp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun ErDiagramView(app: AppState, tab: ErDiagramTab) {
    Column(Modifier.fillMaxSize()) {
        ErToolbar(tab)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        tab.error?.let { ErrorBanner(it, onDismiss = { tab.error = null }) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab.snapshot == null && tab.loading -> {
                    val progress = tab.progress
                    val message = progress?.let { (done, total) -> "Reading tables… $done of $total" } ?: "Reading ${tab.namespace.displayName}…"
                    ConnectionLoadingPlaceholder(tab.config, app.databases.status(tab.config.id), message, Modifier.align(Alignment.Center))
                }
                tab.snapshot != null && tab.tables.isEmpty() ->
                    EmptyState("No tables", "${tab.namespace.displayName} has no tables to draw yet.", Modifier.align(Alignment.Center)) {
                        OutlinedButton(onClick = { app.workspace.openTableDesigner(tab.config, tab.namespace) }) { Text("New Table…") }
                    }
                tab.snapshot != null -> DiagramCanvas(tab)
            }
        }
        StatusLine(tab)
    }
}

@Composable
private fun ErToolbar(tab: ErDiagramTab) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            tab.search,
            Modifier.width(200.dp),
            placeholder = { Text("Find table") },
            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 4.dp)) },
            onKeyboardAction = KeyboardActionHandler { tab.findNext() },
        )
        Separator()
        ToolButton(AllIconsKeys.General.ZoomOut, "Zoom out (pinch or ⌘ scroll)") { tab.zoomBy(1 / 1.25f) }
        Text("${(tab.zoom * 100).toInt()}%", Modifier.width(42.dp), color = JewelTheme.globalColors.text.info)
        ToolButton(AllIconsKeys.General.ZoomIn, "Zoom in (pinch or ⌘ scroll)") { tab.zoomBy(1.25f) }
        ToolButton(AllIconsKeys.General.FitContent, "Fit to window") { tab.fit() }
        ToolButton(AllIconsKeys.General.ActualZoom, "Actual size") { tab.resetZoom() }
        Separator()
        ToolButton(AllIconsKeys.Graph.Layout, "Auto-arrange") { tab.arrange() }
        ToolButton(AllIconsKeys.Actions.Refresh, "Reload the schema") { tab.load() }
        if (tab.focus != null) {
            Separator()
            ViewChip("Around ${tab.focus.name}", !tab.showAll) { tab.showAll(false) }
            ViewChip("Whole schema", tab.showAll) { tab.showAll(true) }
        }
        Box(Modifier.weight(1f))
        ExportMenu(tab)
    }
}

@Composable
private fun ExportMenu(tab: ErDiagramTab) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = tab.tables.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Export")
                Icon(AllIconsKeys.General.ChevronDown, null)
            }
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                val name = tab.focus?.name ?: tab.namespace.displayName
                selectableItem(selected = false, onClick = {
                    open = false
                    chooseSaveFile("$name-er.png", "Save Diagram")?.let { tab.savePng(it, dark = false) }
                }) { Text("Save as PNG…") }
                selectableItem(selected = false, onClick = {
                    open = false
                    chooseSaveFile("$name-er-dark.png", "Save Diagram")?.let { tab.savePng(it, dark = true) }
                }) { Text("Save as PNG (dark)…") }
                separator()
                selectableItem(selected = false, onClick = {
                    open = false
                    tab.copyMermaid()
                }) { Text("Copy as Mermaid") }
                selectableItem(selected = false, onClick = {
                    open = false
                    chooseSaveFile("$name-er.mmd", "Save Mermaid")?.let(tab::saveMermaid)
                }) { Text("Save as Mermaid…") }
            }
        }
    }
}

@Composable
private fun StatusLine(tab: ErDiagramTab) {
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val info = JewelTheme.globalColors.text.info
        Text("${tab.tables.size} table(s) · ${tab.edges.size} relationship(s)", color = info, maxLines = 1, softWrap = false)
        tab.selected?.let { table ->
            Text(table.name, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            OutlinedLink("Open Data") { tab.openData(table) }
            if (!tab.config.readOnly) OutlinedLink("Edit Structure") { tab.editStructure(table) }
        }
        Legend()
        Text(
            "Drag to arrange · scroll to pan · ⌘ scroll to zoom · double-click opens data",
            Modifier.weight(1f),
            color = info,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** What the key markers in the boxes mean. */
@Composable
private fun Legend() {
    val colors = ErColors.of(LocalColorTheme.current, LocalIslands.current.accent)
    val info = JewelTheme.globalColors.text.info
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(colors.primaryKey, size.minDimension / 2) }
        Text("primary key", color = info, maxLines = 1, softWrap = false)
        Box(Modifier.width(4.dp))
        Canvas(Modifier.size(8.dp)) { drawCircle(colors.foreignKey, size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx())) }
        Text("foreign key", color = info, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun OutlinedLink(label: String, onClick: () -> Unit) {
    org.jetbrains.jewel.ui.component.Link(label, onClick = onClick)
}

/** Diagram colours from the theme, shared by the canvas and the PNG export. */
internal data class ErColors(
    val background: Color,
    val grid: Color,
    val box: Color,
    val header: Color,
    val headerText: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val primaryKey: Color,
    val foreignKey: Color,
    val match: Color,
) {
    companion object {
        /** The diagram in [theme]'s colours, with [accent] for selection. */
        fun of(theme: ColorTheme, accent: Color): ErColors {
            val d = theme.diagram
            return ErColors(
                background = d.background, grid = d.grid, box = d.box, header = d.header, headerText = d.headerText, border = d.border,
                text = d.text, muted = d.muted, line = d.line, accent = accent, primaryKey = d.primaryKey, foreignKey = d.foreignKey, match = d.match,
            )
        }

        /** Graphite's diagram colours, for the PNG export, which is drawn outside composition. */
        fun of(dark: Boolean, accent: Color): ErColors =
            if (dark) {
                ErColors(
                    background = Color(0xFF1E1F22), grid = Color(0xFF2B2D30), box = Color(0xFF2B2D30), header = Color(0xFF393B40), headerText = Color(0xFFDFE1E5),
                    border = Color(0xFF4E5157), text = Color(0xFFDFE1E5), muted = Color(0xFF8C8F94), line = Color(0xFF6F737A), accent = accent,
                    primaryKey = Color(0xFFE0B53E), foreignKey = Color(0xFF5C9CF5), match = Color(0xFFE0B53E),
                )
            } else {
                ErColors(
                    background = Color(0xFFF7F8FA), grid = Color(0xFFEBECF0), box = Color.White, header = Color(0xFFEDF3FF), headerText = Color(0xFF1E1F22),
                    border = Color(0xFFC9CCD6), text = Color(0xFF1E1F22), muted = Color(0xFF6C707E), line = Color(0xFF8C909C), accent = accent,
                    primaryKey = Color(0xFFC28A0C), foreignKey = Color(0xFF3574F0), match = Color(0xFFF2C55C),
                )
            }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DiagramCanvas(tab: ErDiagramTab) {
    val colors = ErColors.of(LocalColorTheme.current, LocalIslands.current.accent)
    // Text is measured at density 1, in the same units as the boxes; the whole canvas is then scaled by zoom × density,
    // so text and boxes stay in proportion on any screen.
    val fonts = LocalFontFamilyResolver.current
    val direction = LocalLayoutDirection.current
    val measurer = remember(fonts, direction) { TextMeasurer(fonts, Density(1f), direction, cacheSize = 1024) }
    val density = LocalDensity.current.density
    LaunchedEffect(density) { tab.density = density }
    LaunchedEffect(measurer) { tab.useMeasure { table -> measureWidth(measurer, table) } }
    var dragging by remember { mutableStateOf<TableRef?>(null) }
    var hovered by remember { mutableStateOf<TableRef?>(null) }
    var menuTable by remember { mutableStateOf<TableRef?>(null) }
    LaunchedEffect(tab) { snapshotFlow { tab.search.text.toString() }.collect { if (it.isNotBlank()) tab.findNext() } }

    ContextMenuArea(
        items = {
            val table = menuTable ?: return@ContextMenuArea emptyList()
            listOfNotNull(
                ContextMenuItem("Open Data") { tab.openData(table) },
                ContextMenuItem("Edit Structure…") { tab.editStructure(table) }.takeUnless { tab.config.readOnly },
                ContextMenuItem("Show Around ${table.name}") { tab.showAround(table) },
            )
        },
    ) {
        Canvas(
            Modifier.fillMaxSize()
                // Boxes dragged past the edge mustn't draw over the toolbar.
                .clipToBounds()
                .background(colors.background)
                .onSizeChanged { tab.viewportChanged(it.toSize()) }
                .pointerHoverIcon(if (dragging != null) PointerIcon.Crosshair else if (hovered != null) PointerIcon.Hand else PointerIcon.Default)
                .onPointerEvent(PointerEventType.Move) { event -> hovered = tab.hit(tab.toDiagram(event.changes.first().position)) }
                .onPointerEvent(PointerEventType.Exit) { hovered = null }
                .onPinch { factor, anchor -> tab.zoomBy(factor, anchor) }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val delta = change.scrollDelta
                    if (event.keyboardModifiers.isMetaPressed || event.keyboardModifiers.isCtrlPressed) {
                        // Proportional, so a trackpad's many small deltas zoom as smoothly as a wheel's notches.
                        tab.zoomBy(exp(-delta.y * 0.1f), change.position)
                    } else {
                        tab.pan -= Offset(delta.x, delta.y) * 40f
                    }
                    change.consume()
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed) {
                        val hit = tab.hit(tab.toDiagram(event.changes.first().position))
                        menuTable = hit
                        if (hit != null) tab.selected = hit
                    }
                }
                .pointerInput(tab) {
                    detectTapGestures(
                        onTap = { tab.selected = tab.hit(tab.toDiagram(it)) },
                        onDoubleTap = { point -> tab.hit(tab.toDiagram(point))?.let(tab::openData) },
                    )
                }
                .pointerInput(tab) {
                    detectDragGestures(
                        onDragStart = { start ->
                            dragging = tab.hit(tab.toDiagram(start))
                            dragging?.let { tab.selected = it }
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                        onDrag = { change, amount ->
                            change.consume()
                            val table = dragging
                            if (table != null) tab.move(table, amount / tab.scale) else tab.pan += amount
                        },
                    )
                },
        ) {
            drawGrid(tab, colors)
            withTransform({
                translate(tab.pan.x, tab.pan.y)
                scale(tab.scale, tab.scale, pivot = Offset.Zero)
            }) {
                val visible = visibleArea(tab)
                val focus = tab.selected ?: hovered
                val edges = tab.edges
                val related = edges.filter { it.from == focus || it.to == focus }
                // Columns at either end of the focused table's lines, tinted so the join reads at a glance.
                val linked = related.flatMap { listOf(it.from to it.fromColumns, it.to to it.toColumns) }.groupBy({ it.first }, { it.second }).mapValues { (_, c) -> c.flatten().toSet() }
                // Lines first, under the boxes; the focused table's on top of the others.
                edges.sortedBy { it in related }.forEach { edge ->
                    val highlight = edge in related
                    drawEdge(tab, edge, if (highlight) colors.accent else colors.line, if (highlight) 1.8f else 1.2f, colors.background)
                }
                val faded = focus != null
                for (table in tab.tables) {
                    val box = tab.box(table.table) ?: continue
                    if (box.right < visible.first.x || box.x > visible.second.x || box.bottom < visible.first.y || box.y > visible.second.y) continue
                    val near = !faded || table.table == focus || table.table in linked
                    drawTable(
                        measurer, table, box, colors,
                        selected = table.table == tab.selected,
                        hovered = table.table == hovered,
                        match = tab.matches(table.table),
                        linked = linked[table.table].orEmpty(),
                        dimmed = !near && tab.selected != null,
                        detailed = tab.zoom >= 0.4f,
                    )
                }
            }
        }
    }
}

/** The diagram area currently on screen, in diagram units. */
private fun visibleArea(tab: ErDiagramTab): Pair<Offset, Offset> =
    tab.toDiagram(Offset.Zero) to tab.toDiagram(Offset(tab.viewport.width, tab.viewport.height))

private fun DrawScope.drawGrid(tab: ErDiagramTab, colors: ErColors) {
    val step = 24f * tab.scale
    if (step < 10f) return
    val startX = tab.pan.x % step
    val startY = tab.pan.y % step
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(colors.grid, radius = 1.2f, center = Offset(x, y))
            y += step
        }
        x += step
    }
}

private val TitleStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
private val CountStyle = TextStyle(fontSize = 11.sp)
private val NameStyle = TextStyle(fontSize = 12.sp)
private val TypeStyle = TextStyle(fontSize = 11.sp)

/** Room left of a column name for its key marker, and around the text. */
private const val MarkerSpace = 26f
private const val SidePadding = 12f
private const val NameTypeGap = 16f

/** A box wide enough for the table's name and every shown column's name and type, within limits. */
internal fun measureWidth(measurer: TextMeasurer, table: TableDetails): Float {
    val title = measurer.measure(table.table.name, TitleStyle).size.width + SidePadding * 2 + 28f
    val rows =
        table.columns.take(ErLayout.MaxRows).maxOfOrNull { column ->
            measurer.measure(column.name, NameStyle).size.width + measurer.measure(column.typeName.lowercase(), TypeStyle).size.width + MarkerSpace + NameTypeGap + SidePadding
        } ?: 0f
    return maxOf(title, rows.toFloat()).coerceIn(180f, 420f)
}

private fun DrawScope.drawTable(
    measurer: TextMeasurer,
    table: TableDetails,
    box: ErBox,
    colors: ErColors,
    selected: Boolean,
    hovered: Boolean,
    match: Boolean,
    linked: Set<String>,
    dimmed: Boolean,
    detailed: Boolean,
) {
    val alpha = if (dimmed) 0.45f else 1f
    val topLeft = Offset(box.x, box.y)
    val size = Size(box.width, box.height)
    val radius = CornerRadius(10f, 10f)
    // Two soft layers of shadow lift the box off the canvas; more when it's picked up.
    val lift = if (selected || hovered) 1.6f else 1f
    drawRoundRect(Color.Black.copy(alpha = 0.05f * lift * alpha), topLeft + Offset(0f, 4f * lift), size, radius)
    drawRoundRect(Color.Black.copy(alpha = 0.07f * lift * alpha), topLeft + Offset(0f, 1f), size, radius)
    drawRoundRect(colors.box.copy(alpha = alpha), topLeft, size, radius)

    // Header: tinted band with the name, the column count on the right, and an accent stripe when selected.
    clipRect(box.x, box.y, box.right, box.y + ErLayout.HeaderHeight) {
        val header = if (selected) colors.accent.copy(alpha = 0.16f * alpha) else colors.header.copy(alpha = alpha)
        drawRoundRect(header, topLeft, Size(box.width, ErLayout.HeaderHeight + 12f), radius)
        if (selected) drawRect(colors.accent, topLeft, Size(box.width, 3f))
    }
    drawLine(colors.border.copy(alpha = alpha), Offset(box.x, box.y + ErLayout.HeaderHeight), Offset(box.right, box.y + ErLayout.HeaderHeight), 1f)
    val outline =
        when {
            selected -> colors.accent
            match -> colors.match
            hovered -> colors.accent.copy(alpha = 0.6f)
            else -> colors.border
        }
    drawRoundRect(outline.copy(alpha = outline.alpha * alpha), topLeft, size, radius, style = Stroke(if (selected || match) 2f else 1f))

    val count = "${table.columns.size}"
    val countLayout = measurer.measure(count, CountStyle.copy(color = colors.muted.copy(alpha = alpha)))
    val headerMid = box.y + ErLayout.HeaderHeight / 2 + if (selected) 1.5f else 0f
    drawText(countLayout, topLeft = Offset(box.right - SidePadding - countLayout.size.width, headerMid - countLayout.size.height / 2))
    drawLabel(measurer, table.table.name, TitleStyle.copy(color = colors.headerText.copy(alpha = alpha)), box.x + SidePadding, headerMid, box.width - SidePadding * 3 - countLayout.size.width)
    if (!detailed) return

    val foreignKeys = table.foreignKeys.flatMap { it.columns }.toSet()
    table.columns.take(ErLayout.MaxRows).forEachIndexed { i, column ->
        val rowTop = box.y + ErLayout.HeaderHeight + ErLayout.RowPadding + i * ErLayout.RowHeight
        val mid = rowTop + ErLayout.RowHeight / 2
        if (column.name in linked) {
            drawRoundRect(colors.accent.copy(alpha = 0.12f * alpha), Offset(box.x + 4f, rowTop + 1f), Size(box.width - 8f, ErLayout.RowHeight - 2f), CornerRadius(5f, 5f))
        }
        val center = Offset(box.x + SidePadding + 3f, mid)
        val foreign = column.name in foreignKeys
        if (column.primaryKey) drawCircle(colors.primaryKey.copy(alpha = alpha), 3.6f, center)
        if (foreign) drawCircle(colors.foreignKey.copy(alpha = alpha), if (column.primaryKey) 5.6f else 3.6f, center, style = Stroke(1.5f))

        val typeLayout = measurer.measure(column.typeName.lowercase(), TypeStyle.copy(color = colors.muted.copy(alpha = alpha)), maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (box.width * 0.42f).toInt().coerceAtLeast(1)))
        drawText(typeLayout, topLeft = Offset(box.right - SidePadding - typeLayout.size.width, mid - typeLayout.size.height / 2))
        val nameStyle = NameStyle.copy(color = colors.text.copy(alpha = alpha), fontWeight = if (column.primaryKey) FontWeight.SemiBold else FontWeight.Normal)
        drawLabel(measurer, column.name, nameStyle, box.x + MarkerSpace, mid, box.width - MarkerSpace - SidePadding - NameTypeGap - typeLayout.size.width)
    }
    if (table.columns.size > ErLayout.MaxRows) {
        val mid = box.y + ErLayout.HeaderHeight + ErLayout.RowPadding + ErLayout.MaxRows * ErLayout.RowHeight + ErLayout.RowHeight / 2
        drawLabel(measurer, "+ ${table.columns.size - ErLayout.MaxRows} more columns", TypeStyle.copy(color = colors.muted.copy(alpha = alpha)), box.x + MarkerSpace, mid, box.width - MarkerSpace - SidePadding)
    }
}

/** Draws [text] on one line, vertically centred on [centerY], cut with an ellipsis at [maxWidth]. */
private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, style: TextStyle, x: Float, centerY: Float, maxWidth: Float) {
    if (maxWidth <= 4f) return
    val layout = measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxWidth.toInt()))
    drawText(layout, topLeft = Offset(x, centerY - layout.size.height / 2))
}

/** The y of [column]'s row in [table]'s box; columns folded into "… more" (or missing) use the header. */
internal fun rowAnchor(table: TableDetails?, box: ErBox, column: String?): Float {
    val index = table?.columns?.indexOfFirst { it.name == column } ?: -1
    return if (index in 0 until ErLayout.MaxRows) box.y + ErLayout.HeaderHeight + ErLayout.RowPadding + index * ErLayout.RowHeight + ErLayout.RowHeight / 2
    else box.y + ErLayout.HeaderHeight / 2
}

/** Which sides a line leaves and enters by, and its control points: shared by the canvas and the PNG. */
internal data class EdgeGeometry(val start: Offset, val c1: Offset, val c2: Offset, val end: Offset, val startDir: Float, val endDir: Float)

internal fun edgeGeometry(fromTable: TableDetails?, from: ErBox, toTable: TableDetails?, to: ErBox, edge: ErEdge): EdgeGeometry {
    val y1 = rowAnchor(fromTable, from, edge.fromColumns.firstOrNull())
    val y2 = rowAnchor(toTable, to, edge.toColumns.firstOrNull())
    val bend = 56f
    return when {
        from == to -> {
            // A table referencing itself: a loop out of the right side and back.
            val s = Offset(from.right, y1)
            val e = Offset(from.right, y2.takeIf { it != y1 } ?: (y1 + ErLayout.RowHeight))
            EdgeGeometry(s, s + Offset(bend, 0f), e + Offset(bend, 0f), e, 1f, 1f)
        }
        from.x > to.right -> EdgeGeometry(Offset(from.x, y1), Offset(from.x - bend, y1), Offset(to.right + bend, y2), Offset(to.right, y2), -1f, 1f)
        from.right < to.x -> EdgeGeometry(Offset(from.right, y1), Offset(from.right + bend, y1), Offset(to.x - bend, y2), Offset(to.x, y2), 1f, -1f)
        // Stacked: both on the right side.
        else -> {
            val right = maxOf(from.right, to.right)
            EdgeGeometry(Offset(from.right, y1), Offset(right + bend, y1), Offset(right + bend, y2), Offset(to.right, y2), 1f, 1f)
        }
    }
}

private fun DrawScope.drawEdge(tab: ErDiagramTab, edge: ErEdge, color: Color, width: Float, background: Color) {
    val from = tab.box(edge.from) ?: return
    val to = tab.box(edge.to) ?: return
    val tables = tab.tables
    val g = edgeGeometry(tables.firstOrNull { it.table == edge.from }, from, tables.firstOrNull { it.table == edge.to }, to, edge)
    val path = Path().apply {
        moveTo(g.start.x, g.start.y)
        cubicTo(g.c1.x, g.c1.y, g.c2.x, g.c2.y, g.end.x, g.end.y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round))
    // Crow's foot at the referencing table: many rows point at one.
    val foot = 10f
    val base = g.start + Offset(g.startDir * foot, 0f)
    drawLine(color, base, g.start + Offset(0f, -5f), width)
    drawLine(color, base, g.start + Offset(0f, 5f), width)
    drawLine(color, base, g.start, width)
    // At the referenced table: a bar for "exactly one", a bar and a ring for "zero or one".
    val tick = g.end + Offset(g.endDir * 7f, 0f)
    drawLine(color, tick + Offset(0f, -5f), tick + Offset(0f, 5f), width)
    if (edge.optional) {
        val ring = g.end + Offset(g.endDir * 15f, 0f)
        drawCircle(background, 3.5f, ring)
        drawCircle(color, 3.5f, ring, style = Stroke(width))
    } else {
        val second = g.end + Offset(g.endDir * 11f, 0f)
        drawLine(color, second + Offset(0f, -5f), second + Offset(0f, 5f), width)
    }
}
