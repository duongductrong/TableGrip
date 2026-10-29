package dev.datagrok.app.grid

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import dev.datagrok.core.dialect.SortColumn
import dev.datagrok.core.dialect.SortDirection
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.export.CopyFormat
import java.awt.Cursor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalScrollbar
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

private val ResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

/**
 * Consumes every pointer event that reaches this element once its own content has handled it, so the grid's handler
 * (an ancestor) leaves the gesture alone. Pointer capture keeps a drag that started here routed here until release.
 */
private fun Modifier.claimPointer(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }

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
    LaunchedEffect(state.focusRequests) { if (state.focusRequests > 0) runCatching { gridFocus.requestFocus() } }
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
                                // The scrollbars claim everything over them, including drags of their thumb that
                                // wander over the cells; those must not select or hover anything underneath.
                                if (change.isConsumed) {
                                    controller.onExit()
                                    resizeHover = false
                                    continue
                                }
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
                    Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(top = HeaderHeight).claimPointer(),
                )
                HorizontalScrollbar(
                    state.horizontalScroll,
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = rowNumberWidth).claimPointer(),
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
        // Int UI's border colour equals the dark island background, so the grid uses the islands' separator instead.
        grid = LocalIslands.current.divider,
        headerBackground = JewelTheme.globalColors.panelBackground,
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
                    .gridLines(colors.grid)
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
                .gridLines(colors.grid)
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
                .gridLines(colors.grid)
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
    cell = cell.gridLines(colors.grid)
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
    // The expanded editor floats over the grid and edits the same text, so collapsing it keeps what was typed.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) runCatching { focus.requestFocus() } }
    Box(modifier.background(JewelTheme.globalColors.panelBackground).border(1.dp, colors.focusBorder)) {
        BasicTextField(
            state = controller.state.editText,
            modifier =
                Modifier.fillMaxSize()
                    .padding(start = 8.dp, end = ExpandButtonSize + 4.dp, top = 4.dp, bottom = 4.dp)
                    .focusRequester(focus)
                    .onFocusChanged {
                        // Clicking elsewhere commits, like DataGrip; moving into the expanded editor does not.
                        if (hadFocus && !it.isFocused && !expanded) controller.commitEdit()
                        hadFocus = it.isFocused
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            Shortcuts.ExpandEditor.matches(event) -> expanded = true
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> controller.commitEdit(moveDown = true)
                            event.key == Key.Tab -> controller.commitEdit(moveRight = if (event.isShiftPressed) -1 else 1)
                            event.key == Key.Escape -> controller.cancelEdit()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            textStyle = JewelTheme.editorTextStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.text),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        EditorIconButton(
            icon = AllIconsKeys.General.ExpandComponent,
            tooltip = "Expand (${Shortcuts.ExpandEditor.label})",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
        ) {
            expanded = true
        }
        if (expanded) ExpandedCellEditor(controller, colors, onCollapse = { expanded = false })
    }
}

private val ExpandButtonSize = 18.dp

/** A small borderless icon button that never takes focus, so clicking it leaves the text field focused. */
@Composable
private fun EditorIconButton(icon: IconKey, tooltip: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Tooltip({ Text(tooltip) }, modifier) {
        Box(
            Modifier.size(ExpandButtonSize)
                .clip(RoundedCornerShape(3.dp))
                .background(if (hovered) JewelTheme.globalColors.borders.normal.copy(alpha = 0.35f) else Color.Transparent)
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(onClick) { detectTapGestures { onClick() } }
                .alpha(if (hovered) 1f else 0.6f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, tooltip, Modifier.size(14.dp))
        }
    }
}

/**
 * A roomier multi-line editor floating over the cell: it does not take part in the grid's layout. Enter inserts a line
 * break, [Shortcuts.Submit] applies, Escape collapses back to the inline editor and clicking outside applies.
 */
@Composable
private fun ExpandedCellEditor(controller: GridController, colors: GridColors, onCollapse: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(6.dp)
    Popup(
        popupPositionProvider = OverCellPositionProvider,
        onDismissRequest = { controller.commitEdit() },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(ExpandedEditorWidth)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, colors.focusBorder, shape)
        ) {
            BasicTextField(
                state = controller.state.editText,
                modifier =
                    Modifier.fillMaxWidth()
                        .height(ExpandedEditorHeight)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                Shortcuts.Submit.matches(event) -> controller.commitEdit()
                                event.key == Key.Escape -> onCollapse()
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        },
                textStyle = JewelTheme.editorTextStyle.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                lineLimits = TextFieldLineLimits.MultiLine(),
            )
            Row(
                Modifier.fillMaxWidth().topLine(colors.grid).padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${Shortcuts.Submit.label} to apply · Esc to collapse",
                    Modifier.weight(1f),
                    color = colors.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EditorIconButton(AllIconsKeys.General.CollapseComponent, "Collapse (Esc)", onClick = onCollapse)
            }
        }
    }
}

private val ExpandedEditorWidth = 420.dp
private val ExpandedEditorHeight = 180.dp

/** Opens over the cell from its top-left corner, shifted back inside the window when it would overflow. */
private object OverCellPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.top + popupContentSize.height <= windowSize.height
        val y = if (below) anchorBounds.top else (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
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
            ContextMenuItem("Filter by This Value (${Shortcuts.FilterByValue})") { it(column, value, false) }
        },
        filter?.takeIf { single }?.let {
            val column = controller.columnName(focus.column)
            val value = controller.model.value(focus.row, state.modelColumn(focus.column))
            ContextMenuItem("Exclude This Value (${Shortcuts.ExcludeValue})") { it(column, value, true) }
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

/**
 * Every cell — header, row number or data — owns its right and bottom divider, drawn over its own backgrounds (so
 * selection and change highlights never hide them) but under a focus border applied later in the chain.
 */
private fun Modifier.gridLines(color: Color) = drawBehind { drawGridLines(color, right = true) }

/** Bottom divider for a whole row, so it also runs past the last column. */
private fun Modifier.bottomLine(color: Color) = drawBehind { drawGridLines(color, right = false) }

private fun Modifier.topLine(color: Color) = drawBehind {
    val stroke = 1.dp.toPx().coerceAtLeast(1f)
    drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), strokeWidth = stroke)
}

private fun DrawScope.drawGridLines(color: Color, right: Boolean) {
    val stroke = 1.dp.toPx().coerceAtLeast(1f)
    val y = size.height - stroke / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
    if (right) {
        val x = size.width - stroke / 2
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
    }
}
