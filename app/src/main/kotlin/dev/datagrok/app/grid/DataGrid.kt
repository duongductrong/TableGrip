package dev.datagrok.app.grid

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.export.CopyFormat
import java.awt.Cursor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * A DataGrip-style data grid: rows and columns are virtualized, row numbers stay pinned, columns stretch to fill the
 * available width and can be resized, auto-fitted, hidden and reordered. Supports range selection with mouse and
 * keyboard, copying in several formats and, when [model] has an editor, inline editing.
 */
@Composable
fun DataGrid(
    model: GridModel,
    state: DataGridState,
    modifier: Modifier = Modifier,
    sort: List<SortColumn> = emptyList(),
    actions: GridActions = GridActions(),
    firstRowNumber: Long = 1,
) {
    remember(model.columns) { state.sync(model.columns) }
    LaunchedEffect(model.rowCount) { state.clamp(model.rowCount) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val gridFocus = remember { FocusRequester() }
    val controller = remember(state) { GridController(state, scope, gridFocus) }
    var hasFocus by remember { mutableStateOf(false) }
    var resizeHover by remember { mutableStateOf(false) }

    val cellStyle = JewelTheme.editorTextStyle
    val measurer = rememberTextMeasurer()
    val charWidth = remember(cellStyle, density) { measurer.measure("0123456789", cellStyle).size.width / 10f / density.density }
    val natural = remember(model, model.rowCount, charWidth) { naturalWidths(model, charWidth) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusRequester(gridFocus)
            .focusable()
            .onPreviewKeyEvent(controller::onKey)
    ) {
        val visible = state.visibleColumns
        val rowNumberWidth = ((firstRowNumber + model.rowCount).toString().length.coerceAtLeast(2) * charWidth + 20).dp
        val base = visible.map { state.userWidths[it] ?: natural.getOrElse(it) { MinColumnWidth } }
        val flexible = visible.indices.filter { visible[it] !in state.userWidths }.toSet()
        val widths = fitWidths(base, flexible, maxWidth - rowNumberWidth - ScrollbarAllowance)

        val geometry =
            with(density) {
                val widthsPx = FloatArray(widths.size) { widths[it].toPx() }
                val offsetsPx = FloatArray(widths.size)
                var x = rowNumberWidth.toPx()
                for (i in widthsPx.indices) {
                    offsetsPx[i] = x
                    x += widthsPx[i]
                }
                GridGeometry(
                    rowNumberWidth = rowNumberWidth.toPx(),
                    offsets = offsetsPx,
                    widths = widthsPx,
                    headerHeight = HeaderHeight.toPx(),
                    rowHeight = RowHeight.toPx(),
                    viewportWidth = constraints.maxWidth.toFloat(),
                    viewportHeight = constraints.maxHeight.toFloat(),
                    resizeSlop = 4.dp.toPx(),
                )
            }
        controller.model = model
        controller.geometry = geometry
        controller.actions = actions
        controller.sort = sort
        controller.naturalWidths = natural
        controller.density = density.density

        LaunchedEffect(state.pendingReveal) {
            state.pendingReveal?.let(controller::scrollIntoView)
            state.pendingReveal = null
        }

        val totalWidth = with(density) { geometry.contentWidth.toDp() }
        val range by remember(geometry) { derivedStateOf { geometry.visibleRange(state.horizontalScroll.value.toFloat()) } }
        val colors = gridColors(hasFocus)

        ContextMenuArea(items = { contextMenu(controller) }) {
            Box(
                Modifier.fillMaxSize()
                    .pointerHoverIcon(if (resizeHover) ResizeCursor else PointerIcon.Default)
                    .pointerInput(controller) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.first()
                                when (event.type) {
                                    PointerEventType.Press ->
                                        controller.onPress(
                                            change.position,
                                            primary = event.buttons.isPrimaryPressed,
                                            shift = event.keyboardModifiers.isShiftPressed,
                                            time = change.uptimeMillis,
                                        )
                                    PointerEventType.Move -> resizeHover = controller.onMove(change.position, event.buttons.isPrimaryPressed)
                                    PointerEventType.Release -> controller.onRelease(event.keyboardModifiers.isShiftPressed)
                                    PointerEventType.Exit -> {
                                        controller.onExit()
                                        resizeHover = false
                                    }
                                }
                            }
                        }
                    }
            ) {
                Box(Modifier.fillMaxSize().horizontalScroll(state.horizontalScroll)) {
                    LazyColumn(Modifier.width(totalWidth).fillMaxHeight(), state = state.verticalScroll) {
                        stickyHeader(key = "header") { HeaderRow(controller, geometry, widths, totalWidth, rowNumberWidth, range, colors) }
                        items(model.rowCount, key = { it }) { row ->
                            DataRow(controller, row, geometry, widths, totalWidth, rowNumberWidth, range, colors, firstRowNumber)
                        }
                    }
                }
                VerticalScrollbar(
                    state.verticalScroll,
                    Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(top = HeaderHeight),
                )
                HorizontalScrollbar(
                    state.horizontalScroll,
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = rowNumberWidth),
                )
                if (model.rowCount == 0) {
                    Text("No rows", Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
                }
            }
        }
    }
}

internal class GridColors(
    val grid: Color,
    val headerBackground: Color,
    val stripe: Color,
    val hover: Color,
    val focusRow: Color,
    val selection: Color,
    val focusBorder: Color,
    val modified: Color,
    val inserted: Color,
    val deleted: Color,
    val muted: Color,
    val text: Color,
)

@Composable
private fun gridColors(focused: Boolean): GridColors {
    val text = JewelTheme.globalColors.text.normal
    val tree = JewelTheme.treeStyle.colors
    val dark = JewelTheme.isDark
    return GridColors(
        grid = JewelTheme.globalColors.borders.normal.copy(alpha = 0.6f),
        headerBackground = JewelTheme.globalColors.panelBackground,
        stripe = text.copy(alpha = 0.025f),
        hover = text.copy(alpha = 0.05f),
        focusRow = text.copy(alpha = 0.07f),
        selection = if (focused) tree.backgroundSelectedActive else tree.backgroundSelected,
        focusBorder = JewelTheme.globalColors.outlines.focused,
        modified = if (dark) Color(0x403574F0) else Color(0x333574F0),
        inserted = if (dark) Color(0x3349A35B) else Color(0x3355A76A),
        deleted = if (dark) Color(0x33DB5C5C) else Color(0x33E55765),
        muted = JewelTheme.globalColors.text.info,
        text = text,
    )
}

@Composable
private fun HeaderRow(
    controller: GridController,
    geometry: GridGeometry,
    widths: List<Dp>,
    totalWidth: Dp,
    rowNumberWidth: Dp,
    range: IntRange,
    colors: GridColors,
) {
    val state = controller.state
    val density = LocalDensity.current
    Box(Modifier.width(totalWidth).height(HeaderHeight).background(colors.headerBackground).bottomLine(colors.grid)) {
        for (column in range) {
            val name = controller.columnName(column)
            val type = controller.model.columns[state.modelColumn(column)].typeName
            Row(
                Modifier.offset(x = with(density) { geometry.offsets[column].toDp() })
                    .width(widths[column])
                    .fillMaxHeight()
                    .rightLine(colors.grid)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(type.lowercase(), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Clip, color = colors.muted)
                val direction = controller.sortDirection(column)
                if (direction != null) {
                    Icon(if (direction == SortDirection.Asc) AllIconsKeys.General.ArrowUp else AllIconsKeys.General.ArrowDown, direction.name)
                    controller.sortOrder(column)?.let { Text(it.toString(), color = colors.muted) }
                }
            }
        }
        controller.dropIndicator?.let { target ->
            Box(
                Modifier.offset(x = with(density) { geometry.dropX(target).toDp() } - 1.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(colors.focusBorder)
            )
        }
        // Pinned corner over the row-number column; it also selects everything when clicked.
        Box(
            Modifier.offset { IntOffset(state.horizontalScroll.value, 0) }
                .zIndex(1f)
                .width(rowNumberWidth)
                .fillMaxHeight()
                .background(colors.headerBackground)
                .rightLine(colors.grid)
        )
    }
}

@Composable
private fun DataRow(
    controller: GridController,
    row: Int,
    geometry: GridGeometry,
    widths: List<Dp>,
    totalWidth: Dp,
    rowNumberWidth: Dp,
    range: IntRange,
    colors: GridColors,
    firstRowNumber: Long,
) {
    val state = controller.state
    val model = controller.model
    val density = LocalDensity.current
    val status = model.rowStatus(row)
    val selection = state.selection
    val focus = selection?.focus
    val rowBackground =
        when {
            status == RowStatus.Inserted -> colors.inserted
            status == RowStatus.Deleted -> colors.deleted
            focus?.row == row -> colors.focusRow
            controller.hoverRow == row -> colors.hover
            row % 2 == 1 -> colors.stripe
            else -> Color.Transparent
        }

    Box(Modifier.width(totalWidth).height(RowHeight).background(rowBackground).bottomLine(colors.grid)) {
        for (column in range) {
            val modelColumn = state.modelColumn(column)
            val cellModifier =
                Modifier.offset(x = with(density) { geometry.offsets[column].toDp() }).width(widths[column]).fillMaxHeight()
            if (state.editing == GridCell(row, column)) {
                CellEditor(controller, cellModifier, colors)
            } else {
                Cell(
                    value = model.value(row, modelColumn),
                    placeholder = model.placeholder(row, modelColumn),
                    numeric = model.columns[modelColumn].isNumeric,
                    selected = selection?.contains(row, column) == true,
                    focused = focus == GridCell(row, column),
                    modified = model.isModified(row, modelColumn),
                    deleted = status == RowStatus.Deleted,
                    colors = colors,
                    modifier = cellModifier,
                )
            }
        }
        val rowSelected = selection != null && row in selection.rows && selection.wholeRows
        Box(
            Modifier.offset { IntOffset(state.horizontalScroll.value, 0) }
                .zIndex(1f)
                .width(rowNumberWidth)
                .fillMaxHeight()
                .background(colors.headerBackground)
                .background(if (rowSelected) colors.selection else rowBackground)
                .rightLine(colors.grid)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val marker =
                when (status) {
                    RowStatus.Inserted -> "+"
                    RowStatus.Deleted -> "−"
                    RowStatus.Normal -> (firstRowNumber + row).toString()
                }
            Text(marker, color = colors.muted, style = JewelTheme.editorTextStyle, maxLines = 1)
        }
    }
}

@Composable
private fun Cell(
    value: String?,
    placeholder: String?,
    numeric: Boolean,
    selected: Boolean,
    focused: Boolean,
    modified: Boolean,
    deleted: Boolean,
    colors: GridColors,
    modifier: Modifier,
) {
    var cell = modifier
    if (modified) cell = cell.background(colors.modified)
    if (selected) cell = cell.background(colors.selection)
    cell = cell.rightLine(colors.grid)
    if (focused) cell = cell.border(1.dp, colors.focusBorder)
    Box(cell.padding(horizontal = 8.dp), contentAlignment = if (numeric && value != null) Alignment.CenterEnd else Alignment.CenterStart) {
        val base = JewelTheme.editorTextStyle
        val style =
            when {
                value == null -> base.copy(color = colors.muted, fontStyle = FontStyle.Italic)
                else -> base.copy(color = colors.text, textAlign = if (numeric) TextAlign.End else TextAlign.Start)
            }
        Text(
            text = value?.let(::singleLine) ?: placeholder ?: "<null>",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (deleted) style.copy(textDecoration = TextDecoration.LineThrough) else style,
        )
    }
}

@Composable
private fun CellEditor(controller: GridController, modifier: Modifier, colors: GridColors) {
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        state = controller.state.editText,
        modifier =
            modifier
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, colors.focusBorder)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    // Clicking elsewhere commits, like DataGrip.
                    if (hadFocus && !it.isFocused) controller.commitEdit()
                    hadFocus = it.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter,
                        Key.NumPadEnter -> controller.commitEdit(moveDown = true).let { true }
                        Key.Tab -> controller.commitEdit(moveRight = if (event.isShiftPressed) -1 else 1).let { true }
                        Key.Escape -> controller.cancelEdit().let { true }
                        else -> false
                    }
                },
        textStyle = JewelTheme.editorTextStyle.copy(color = colors.text),
        cursorBrush = SolidColor(colors.text),
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}

private fun contextMenu(controller: GridController): List<ContextMenuItem> {
    val header = controller.menuHeaderColumn
    return if (header != null) headerMenu(controller, header) else cellMenu(controller)
}

private fun headerMenu(controller: GridController, column: Int): List<ContextMenuItem> {
    val state = controller.state
    val name = controller.columnName(column)
    val onSort = controller.actions.onSort
    return listOfNotNull(
        onSort?.let { ContextMenuItem("Sort Ascending") { it(name, SortDirection.Asc, false) } },
        onSort?.let { ContextMenuItem("Sort Descending") { it(name, SortDirection.Desc, false) } },
        onSort?.takeIf { controller.sortDirection(column) != null }?.let { ContextMenuItem("Remove Sort") { it(name, null, true) } },
        ContextMenuItem("Fit Column Width") { controller.autoFit(column) },
        ContextMenuItem("Reset Column Widths") { state.userWidths.clear() },
        ContextMenuItem("Hide Column") { state.hide(state.modelColumn(column)) }.takeIf { state.visibleColumns.size > 1 },
        ContextMenuItem("Show All Columns") { state.showAll() }.takeIf { state.hidden.isNotEmpty() },
        ContextMenuItem("Copy Column Name") { copyToClipboard(name) },
    )
}

private fun cellMenu(controller: GridController): List<ContextMenuItem> {
    val state = controller.state
    val selection = state.selection ?: return emptyList()
    val editor = controller.model.editor
    val focus = selection.focus
    val single = selection.anchor == selection.focus && !selection.wholeRows
    val filter = controller.actions.onFilterByValue
    return listOfNotNull(
        ContextMenuItem("Edit Cell") { controller.startEditing(focus) }.takeIf { single && controller.canEdit(focus) },
        ContextMenuItem("Copy (${Shortcuts.Copy})") { controller.copy(CopyFormat.Tsv) },
        ContextMenuItem("Copy with Header (${Shortcuts.CopyWithHeader})") { controller.copy(CopyFormat.Tsv, includeHeader = true) },
        ContextMenuItem("Copy as CSV") { controller.copy(CopyFormat.Csv, includeHeader = true) },
        ContextMenuItem("Copy as JSON") { controller.copy(CopyFormat.Json) },
        ContextMenuItem("Copy as Markdown") { controller.copy(CopyFormat.Markdown) },
        ContextMenuItem("Copy as SQL INSERT") { controller.copy(CopyFormat.SqlInsert) },
        ContextMenuItem("Copy as SQL IN (…)") { controller.copy(CopyFormat.SqlInList) },
        filter?.takeIf { single }?.let {
            val column = controller.columnName(focus.column)
            val value = controller.model.value(focus.row, state.modelColumn(focus.column))
            ContextMenuItem("Filter by This Value") { it(column, value) }
        },
        ContextMenuItem("Set NULL (${Shortcuts.SetNull})") { controller.setNull() }.takeIf { editor != null },
        ContextMenuItem("Add Row (${Shortcuts.AddRow})") { controller.addRow() }.takeIf { editor != null },
        ContextMenuItem("Delete Row(s) (${Shortcuts.DeleteRows})") { controller.deleteSelectedRows() }.takeIf { editor != null },
        ContextMenuItem("Revert Row(s) (${Shortcuts.RevertRows})") { controller.revertSelectedRows() }.takeIf { editor != null },
    )
}

private fun singleLine(value: String): String =
    if (value.length > 500 || value.contains('\n')) value.take(500).replace("\r\n", "⏎").replace('\n', '⏎') else value

/** Content-based widths per model column, sampled from the first rows. */
private fun naturalWidths(model: GridModel, charWidth: Float): List<Dp> =
    model.columns.mapIndexed { column, info ->
        var longest = 0
        for (row in 0 until minOf(model.rowCount, 300)) longest = maxOf(longest, model.value(row, column)?.length ?: 6)
        val header = info.name.length + info.typeName.length + 4
        ((maxOf(longest.coerceAtMost(60), header) * charWidth) + 24).dp.coerceIn(MinColumnWidth, MaxNaturalWidth)
    }

private fun Modifier.rightLine(color: Color) = drawBehind {
    drawLine(color, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), strokeWidth = 1f)
}

private fun Modifier.bottomLine(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
}
