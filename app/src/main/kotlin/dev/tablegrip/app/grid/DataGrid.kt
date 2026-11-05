package dev.tablegrip.app.grid

import dev.tablegrip.app.ui.theme.LocalColorTheme
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.tablegrip.core.dialect.SortColumn
import dev.tablegrip.core.dialect.SortDirection
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.LocalSettings
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.settings.Settings
import java.time.ZoneId
import java.time.ZoneOffset
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.edit.EditCheck
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.core.edit.EditKind
import dev.tablegrip.core.export.CopyFormat
import dev.tablegrip.core.filter.FilterValues
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
 * The data grid: rows and columns are virtualized, row numbers stay pinned, columns stretch to fill the
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

    val settings = LocalSettings.current
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
                    rowHeight = rowHeight(settings).toPx(),
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
                    .onGloballyPositioned { controller.windowOrigin = it.positionInRoot() }
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
                    // A scrollbar-wide gutter past the last row and column, so scrolling to the end (e.g. onto the
                    // insert row) leaves them clear of the overlay scrollbars instead of underneath them.
                    LazyColumn(
                        Modifier.width(totalWidth + ScrollbarAllowance).fillMaxHeight(),
                        state = state.verticalScroll,
                        contentPadding = PaddingValues(bottom = ScrollbarAllowance),
                    ) {
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
                state.notice?.let { notice ->
                    LaunchedEffect(notice) {
                        delay(4_000)
                        if (state.notice === notice) state.notice = null
                    }
                    val shape = RoundedCornerShape(4.dp)
                    Text(
                        notice.text,
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = ScrollbarAllowance + 6.dp)
                            .clip(shape)
                            .background(JewelTheme.globalColors.panelBackground)
                            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 12.sp,
                    )
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
    /** Every other row's tint, or transparent when Settings → Data Editor doesn't ask for it. */
    val zebra: Color,
    val format: CellFormat,
)

@Composable
private fun gridColors(focused: Boolean): GridColors {
    val settings = LocalSettings.current
    val text = JewelTheme.globalColors.text.normal
    val tree = JewelTheme.treeStyle.colors
    val changes = LocalColorTheme.current.grid
    return GridColors(
        // Int UI's border colour equals the dark island background, so the grid uses the islands' separator instead.
        grid = LocalIslands.current.divider,
        headerBackground = JewelTheme.globalColors.panelBackground,
        hover = text.copy(alpha = 0.05f),
        focusRow = text.copy(alpha = 0.07f),
        selection = if (focused) tree.backgroundSelectedActive else tree.backgroundSelected,
        focusBorder = JewelTheme.globalColors.outlines.focused,
        modified = changes.modified,
        inserted = changes.inserted,
        deleted = changes.deleted,
        muted = JewelTheme.globalColors.text.info,
        text = text,
        zebra = if (settings.zebraRows) text.copy(alpha = 0.03f) else Color.Transparent,
        format = remember(settings.nullText, settings.dateTimeFormat, settings.dateTimeZone, settings.numberGrouping) { settings.cellFormat() },
    )
}

/** Row height for Settings → Data Editor's density. */
internal fun rowHeight(settings: Settings): Dp =
    when (settings.gridDensity) {
        "Compact" -> 22.dp
        "Spacious" -> 32.dp
        else -> RowHeight
    }

internal fun Settings.cellFormat(): CellFormat =
    CellFormat(
        nullText = nullText,
        dateTimePattern = dateTimeFormat,
        zone = if (dateTimeZone == "Utc") ZoneOffset.UTC else ZoneId.systemDefault(),
        numberGrouping = numberGrouping,
    )

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
            row % 2 == 1 -> colors.zebra
            else -> Color.Transparent
        }

    Box(Modifier.width(totalWidth).height(rowHeight(LocalSettings.current)).background(rowBackground).bottomLine(colors.grid)) {
        for (column in range) {
            val modelColumn = state.modelColumn(column)
            val cellModifier =
                Modifier.offset(x = with(density) { geometry.offsets[column].toDp() }).width(widths[column]).fillMaxHeight()
            if (state.editing == GridCell(row, column)) {
                CellEditor(controller, cellModifier, colors)
            } else {
                val name = model.columns[modelColumn].name
                val edit = model.editor?.cellEdit(row, modelColumn)
                Cell(
                    value = model.value(row, modelColumn),
                    checkBox = edit?.takeIf { it.kind == EditKind.Boolean }?.let { CheckBoxCell(model.editor?.canEdit(row, modelColumn) == true) },
                    problem = model.problem(row, modelColumn) != null,
                    link = controller.actions.onFollowLink?.takeIf { name in controller.actions.linkedColumns }?.let { follow -> { follow(row, name) } },
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

/** A boolean cell shown as a check box; [editable] cells toggle on click and Space. */
private class CheckBoxCell(val editable: Boolean)

@Composable
private fun Cell(
    value: String?,
    checkBox: CheckBoxCell?,
    /** The pending value can't be saved as it is: tinted, and the value panel says why. */
    problem: Boolean,
    /** Follows the cell's foreign key; shown as an arrow at the cell's end. */
    link: (() -> Unit)?,
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
    if (problem) cell = cell.background(ErrorColor.copy(alpha = 0.22f))
    if (selected) cell = cell.background(colors.selection)
    cell = cell.gridLines(colors.grid)
    if (focused) cell = cell.border(1.dp, colors.focusBorder)
    val showLink = link != null && value != null
    Box(
        cell.padding(start = 8.dp, end = if (showLink) 22.dp else 8.dp),
        contentAlignment = if (numeric && value != null) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        val base = JewelTheme.editorTextStyle
        val style =
            when {
                value == null -> base.copy(color = colors.muted, fontStyle = FontStyle.Italic)
                else -> base.copy(color = colors.text, textAlign = if (numeric) TextAlign.End else TextAlign.Start)
            }
        val text = value?.let { singleLine(colors.format.display(it, numeric)) } ?: placeholder ?: colors.format.nullText
        val decorated = if (deleted) style.copy(textDecoration = TextDecoration.LineThrough) else style
        val checked = value?.let(FilterValues::boolean)
        if (checkBox != null && (value == null || checked != null)) {
            // The value stays next to the box: NULL and engine spellings (t, 1) remain visible.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckBoxGlyph(checked, enabled = checkBox.editable && !deleted)
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = decorated.copy(color = colors.muted))
            }
        } else {
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = decorated)
        }
    }
    if (showLink) LinkArrow(link!!, modifier.padding(end = 3.dp))
}

/** The arrow on a foreign-key cell; claims its clicks so the grid doesn't also select underneath. */
@Composable
private fun LinkArrow(onClick: () -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Tooltip({ Text("Go to referenced row (${Shortcuts.GoToReferenced})") }) {
            Box(
                Modifier.size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (hovered) JewelTheme.globalColors.borders.normal.copy(alpha = 0.35f) else Color.Transparent)
                    .hoverable(interaction)
                    .claimPointer()
                    .pointerInput(onClick) { detectTapGestures { onClick() } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AllIconsKeys.General.ArrowRight, "Go to referenced row", Modifier.alpha(if (hovered) 1f else 0.6f))
            }
        }
    }
}

@Composable
private fun CellEditor(controller: GridController, modifier: Modifier, colors: GridColors) {
    val state = controller.state
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // The expanded editor and the picker float over the grid and edit the same text, so closing them keeps what was
    // typed; while one is open, the inline field losing focus to it doesn't commit.
    val popup = state.editPopup
    LaunchedEffect(popup) { if (popup == EditPopup.None) runCatching { focus.requestFocus() } }
    val kind = state.editing?.let { controller.cellEdit(it)?.kind }
    val hasPicker = kind?.isTemporal == true
    val check by remember { derivedStateOf { controller.editCheck() } }
    val changed by remember { derivedStateOf { state.editText.text.toString() != state.editInitial } }
    Box(modifier.background(JewelTheme.globalColors.panelBackground).border(1.dp, if (check is EditCheck.Invalid && changed) ErrorColor else colors.focusBorder)) {
        BasicTextField(
            state = state.editText,
            modifier =
                Modifier.fillMaxSize()
                    .padding(start = 8.dp, end = ExpandButtonSize + 4.dp, top = 4.dp, bottom = 4.dp)
                    .focusRequester(focus)
                    .onFocusChanged {
                        // Clicking elsewhere commits; moving into a floating editor does not.
                        if (hadFocus && !it.isFocused && state.editPopup == EditPopup.None) controller.commitEdit()
                        hadFocus = it.isFocused
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val step = if (event.key == Key.DirectionUp) 1 else if (event.key == Key.DirectionDown) -1 else 0
                        when {
                            Shortcuts.ExpandEditor.matches(event) -> state.editPopup = EditPopup.Expanded
                            hasPicker && event.isAltPressed && event.key == Key.DirectionDown -> state.editPopup = EditPopup.Picker
                            kind?.isNumeric == true && event.isAltPressed && step != 0 ->
                                stepNumber(state.editText.text.toString(), step)?.let(state.editText::setTextAndPlaceCursorAtEnd)
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> controller.commitEdit(moveDown = true, strict = true)
                            event.key == Key.Tab -> controller.commitEdit(moveRight = if (event.isShiftPressed) -1 else 1, strict = true)
                            event.key == Key.Escape -> controller.cancelEdit()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            textStyle = JewelTheme.editorTextStyle.copy(color = colors.text, textAlign = if (kind?.isNumeric == true) TextAlign.End else TextAlign.Start),
            cursorBrush = SolidColor(colors.text),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        if (hasPicker) {
            EditorIconButton(
                icon = AllIconsKeys.General.ChevronDown,
                tooltip = "Pick (⌥↓)",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            ) {
                state.editPopup = EditPopup.Picker
            }
        } else {
            EditorIconButton(
                icon = AllIconsKeys.General.ExpandComponent,
                tooltip = "Expand (${Shortcuts.ExpandEditor.label})",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
            ) {
                state.editPopup = EditPopup.Expanded
            }
        }
        when (popup) {
            EditPopup.Expanded -> ExpandedCellEditor(controller, colors, kind, onCollapse = { state.editPopup = EditPopup.None })
            EditPopup.Picker ->
                TemporalPicker(kind!!, state.editText, onApply = { controller.commitEdit(strict = true) }, onClose = { state.editPopup = EditPopup.None })
            EditPopup.None -> if (changed) check?.hint()?.let { (text, color) -> EditHint(text, color) }
        }
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
 * break, [Shortcuts.Submit] applies, Escape collapses back to the inline editor and clicking outside applies. JSON
 * objects and arrays open as an editable tree, which writes the text back only when something in it changes. So does
 * a text cell holding one, which stays text: it is checked and saved exactly as a typed value would be.
 */
@Composable
private fun ExpandedCellEditor(controller: GridController, colors: GridColors, kind: EditKind?, onCollapse: () -> Unit) {
    val check by remember { derivedStateOf { controller.editCheck() } }
    val text = controller.state.editText
    // Parsed when the tree needs it, not on every keystroke in Text mode; the tree's own edits are always valid JSON.
    val sources = remember { JsonTreeSources() }
    // Decided once, on what the popup opened with, so the Tree/Text switch doesn't come and go while typing.
    val jsonText = kind == EditKind.Text && remember { textOpensAsTree(text.text.toString(), sources) }
    val json = kind == EditKind.Json || jsonText
    // The value as stored: a tree edit that brings back what it holds restores its exact text, not a re-encoding.
    val initial = controller.state.editInitial
    val initialRoot =
        remember(initial) {
            // Usually what the popup opened with, already parsed into [sources].
            val opened = if (!json) null else if (text.text.toString() == initial) sources.of(initial) else jsonTreeSource(initial)
            (opened as? JsonTreeSource.Ok)?.root
        }
    var tree by remember { mutableStateOf(json && sources.of(text.text.toString()) is JsonTreeSource.Ok) }
    val source = if (json && tree) sources.of(text.text.toString()) else null
    val showTree = source is JsonTreeSource.Ok
    // Why Tree was refused; it lasts until the text changes.
    var refusal by remember { mutableStateOf(if (json && !tree) (sources.of(text.text.toString()) as? JsonTreeSource.Unavailable)?.reason else null) }
    if (json) {
        LaunchedEffect(Unit) { snapshotFlow { text.text.toString() }.drop(1).collect { refusal = null } }
    }
    val draft = remember { JsonTreeDraft() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(showTree) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(6.dp)
    Popup(
        popupPositionProvider = OverCellPositionProvider,
        onDismissRequest = {
            draft.flushNow()
            controller.commitEdit()
        },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(if (json) ExpandedEditorWidth * 1.4f else ExpandedEditorWidth)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, colors.focusBorder, shape)
                // Applying comes first: it confirms the tree's open field, and stays open when that can't be used.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !showTree || !Shortcuts.Submit.matches(event)) return@onPreviewKeyEvent false
                    if (draft.confirmNow()) controller.commitEdit(strict = true)
                    true
                }
                // The rest after the tree's fields have had their say (Escape there drops the field, not the popup; undo
                // there undoes typing). Each tree change is one step of the text's own history, which starts at the cell.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !showTree) return@onKeyEvent false
                    when {
                        event.key == Key.Escape -> onCollapse()
                        Shortcuts.Undo.matches(event) -> {
                            draft.flushNow()
                            text.undoState.undo()
                        }
                        Shortcuts.Redo.matches(event) || Shortcuts.RedoAlt.matches(event) -> {
                            draft.flushNow()
                            text.undoState.redo()
                        }
                        else -> return@onKeyEvent false
                    }
                    true
                }
        ) {
            if (showTree) {
                JsonTreeEditor(
                    root = (source as JsonTreeSource.Ok).root,
                    onChange = { root ->
                        val encoded = if (initialRoot != null && root == initialRoot) initial else encodeJson(root, source.layout)
                        sources.put(encoded, JsonTreeSource.Ok(root, source.layout))
                        text.setTextAndPlaceCursorAtEnd(encoded)
                    },
                    draft = draft,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth().height(ExpandedEditorHeight * 1.8f).focusRequester(focus),
                )
            } else {
                BasicTextField(
                    state = text,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(if (json) ExpandedEditorHeight * 1.8f else ExpandedEditorHeight)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .focusRequester(focus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    Shortcuts.Submit.matches(event) -> controller.commitEdit(strict = true)
                                    event.key == Key.Escape -> onCollapse()
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                    textStyle = JewelTheme.editorTextStyle.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.text),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                )
            }
            Row(
                Modifier.fillMaxWidth().topLine(colors.grid).padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (json) {
                    ViewChip("Tree", showTree) {
                        when (val opened = sources.of(text.text.toString())) {
                            is JsonTreeSource.Ok -> tree = true
                            is JsonTreeSource.Unavailable -> refusal = opened.reason
                        }
                    }
                    ViewChip("Text", !showTree) {
                        draft.flushNow()
                        tree = false
                    }
                }
                val hint = check?.hint()
                Text(
                    hint?.first ?: refusal?.let { "$it, so it opens as text" }
                        ?: "${if (jsonText) "Saved as text · " else ""}${Shortcuts.Submit.label} to apply · Esc to collapse",
                    Modifier.weight(1f).padding(start = 6.dp),
                    color = hint?.second ?: colors.muted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (json && !showTree) {
                    EditorIconButton(AllIconsKeys.Actions.ReformatCode, "Format JSON") {
                        reformatJson(text.text.toString(), pretty = true)?.let(text::setTextAndPlaceCursorAtEnd)
                    }
                    EditorIconButton(AllIconsKeys.Actions.Collapseall, "Minify JSON") {
                        reformatJson(text.text.toString(), pretty = false)?.let(text::setTextAndPlaceCursorAtEnd)
                    }
                }
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
    val records = controller.model.records
    val focus = selection.focus
    val single = selection.anchor == selection.focus && !selection.wholeRows
    val filter = controller.actions.onFilterByValue
    val history = editor?.history
    return listOfNotNull(
        history?.nextUndo?.let { ContextMenuItem("Undo ${it.label.replaceFirstChar(Char::uppercase)} (${Shortcuts.Undo})") { state.undo(controller.model) } },
        history?.nextRedo?.let { ContextMenuItem("Redo ${it.label.replaceFirstChar(Char::uppercase)} (${Shortcuts.Redo})") { state.redo(controller.model) } },
        ContextMenuItem("Edit Cell") { controller.startEditing(focus) }.takeIf { single && controller.canEdit(focus) },
        ContextMenuItem("Copy (${Shortcuts.Copy})") { controller.copy(CopyFormat.Tsv) },
        ContextMenuItem("Copy with Header (${Shortcuts.CopyWithHeader})") { controller.copy(CopyFormat.Tsv, includeHeader = true) },
        ContextMenuItem("Paste (${if (isMac) "⌘V" else "Ctrl+V"})") { controller.paste() }.takeIf { editor != null },
        ContextMenuItem("Copy as CSV") { controller.copy(CopyFormat.Csv, includeHeader = true) },
        ContextMenuItem("Copy as JSON") { controller.copy(CopyFormat.Json) },
        ContextMenuItem("Copy as Markdown") { controller.copy(CopyFormat.Markdown) },
        ContextMenuItem("Copy as SQL INSERT") { controller.copy(CopyFormat.SqlInsert) },
        ContextMenuItem("Copy as SQL IN (…)") { controller.copy(CopyFormat.SqlInList) },
        ContextMenuItem("Add to Chat (${Shortcuts.AddToChat})") { controller.mentionSelection() }.takeIf { controller.actions.onMention != null },
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
        ContextMenuItem("Go to Referenced Row (${Shortcuts.GoToReferenced})") { controller.followFocusedLink() }
            .takeIf { single && controller.columnName(focus.column) in controller.actions.linkedColumns && controller.model.value(focus.row, state.modelColumn(focus.column)) != null },
    ) + controller.actions.relatedRows?.takeIf { single }?.invoke(focus.row).orEmpty().map { (label, open) -> ContextMenuItem(label, open) } + listOfNotNull(
        ContextMenuItem("Toggle (Space)") { controller.toggleBoolean() }.takeIf { single && controller.isBooleanCell(focus) && controller.canEdit(focus) },
        ContextMenuItem("Set NULL (${Shortcuts.SetNull})") { controller.setNull() }.takeIf { editor != null },
        ContextMenuItem(editor?.setDefaultLabel ?: "Set DEFAULT") { controller.setDefault() }.takeIf { controller.canSetDefault() },
        ContextMenuItem("Generate UUID") { controller.generateUuids(timeOrdered = false) }.takeIf { controller.hasUuidSelection() },
        ContextMenuItem("Generate UUID v7 (time-ordered)") { controller.generateUuids(timeOrdered = true) }.takeIf { controller.hasUuidSelection() },
        records?.takeIf { selection.rows.first == selection.rows.last && it.canEditRecord(focus.row) }?.let {
            ContextMenuItem("Edit ${it.recordNoun.replaceFirstChar(Char::uppercase)} as JSON…") { state.editRecord(controller.model, focus.row) }
        },
        ContextMenuItem("Add Row (${Shortcuts.AddRow})") { controller.addRow() }.takeIf { editor != null },
        records?.takeIf { it.canAddRecord }?.let { ContextMenuItem("Add ${it.recordNoun.replaceFirstChar(Char::uppercase)} from JSON…") { state.newRecord(controller.model) } },
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

internal fun Modifier.topLine(color: Color) = drawBehind {
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
