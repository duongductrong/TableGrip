package dev.tablegrip.app.table

import dev.tablegrip.core.i18n.I18n
import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.filter.Arity
import dev.tablegrip.core.filter.CompiledFilter
import dev.tablegrip.core.filter.Combinator
import dev.tablegrip.core.filter.FilterColumn
import dev.tablegrip.core.filter.FilterOperator
import dev.tablegrip.core.filter.FilterValues
import dev.tablegrip.core.filter.TemporalValue
import dev.tablegrip.core.filter.ValueKind
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import org.jetbrains.jewel.ui.theme.textFieldStyle
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxSize

/** Nested groups beyond this depth stop offering "+ Group"; deeper logic reads better as SQL. */
private const val MaxDepth = 3

/** Builder UI state that outlives a composition but isn't part of the filter. */
private class BuilderUi {
    /** The value field whose suggestion list is showing; it owns ↑, ↓ and Esc until the list closes. */
    var suggestingIn: TextFieldState? = null
}

/** What every row of the builder needs: the tab, its typed columns and the live compile result. */
private class BuilderContext(val tab: TableDataTab, val columns: List<FilterColumn>, val draft: CompiledFilter, val ui: BuilderUi) {
    val builder: FilterBuilderState
        get() = tab.builder

    /** A problem is shown once the user tried to apply, or as soon as there's a typed value it's about. */
    fun issue(node: FilterNodeState): String? {
        val issue = draft.issues[node.id] ?: return null
        val typed =
            when (node) {
                is ConditionState -> node.value.text.isNotEmpty() || node.value2.text.isNotEmpty()
                is RawState -> node.sql.text.isNotBlank()
                is GroupState -> false
            }
        return issue.takeIf { tab.showFilterIssues || typed }
    }

    fun defaultColumn(): FilterColumn? = tab.defaultFilterColumn()
}

/** The table viewer's structured filter: rows of typed conditions in nestable all/any groups. */
@Composable
internal fun BuilderFilterBar(tab: TableDataTab, maxBodyHeight: Dp = 300.dp) {
    val draft by remember(tab) { derivedStateOf { tab.compileDraft() } }
    val ui = remember(tab) { BuilderUi() }
    val context = BuilderContext(tab, tab.filterColumns, draft, ui)
    val root = tab.builder.root
    val info = JewelTheme.globalColors.text.info
    val drag = tab.columnDrag
    DisposableEffect(drag, root) { onDispose { drag.unregister(root) } }

    // A toolbar (mode, columns, apply), then the columns beside a Filter card, as in the document builder.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Unless asked for, the column list only shows where it leaves the conditions room for their values.
        val showColumns = tab.showFilterColumns ?: (root.children.isNotEmpty() && maxWidth >= 900.dp)
        val expanded = root.children.isNotEmpty() || showColumns
        Column(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { drag.register(root, ColumnDrop.Into(root), it.boundsInRoot()) }
                // Collapsed, the whole bar is where a column lands; expanded, the Filter card shows it.
                .background(if (!expanded && drag.hovered == ColumnDrop.Into(root)) dropTint else Color.Transparent)
                .onPreviewKeyEvent { handleFilterShortcut(tab, it) },
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).padding(horizontal = BarInset),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AllIconsKeys.General.Filter, null)
                FilterModeSwitch(tab)
                Tooltip({ Text(if (showColumns) tr("Hide columns") else tr("Show columns to drag into the filter")) }) {
                    ViewChip(tr("Columns"), showColumns) { tab.showFilterColumns = !showColumns }
                }
                Separator()
                // What the filter is (or how to start one) takes the middle and gives way first; the actions keep their place on the right.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (root.children.isEmpty()) {
                        Tooltip({ Text(tr("Add condition ({0})", Shortcuts.FocusFilter)) }) { Link(tr("Add condition"), onClick = { context.addCondition(root) }) }
                        Text(
                            tr("drag a column here, or {0} on a cell to filter by its value", Shortcuts.FilterByValue),
                            Modifier.weight(1f, fill = false),
                            color = info,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        SqlPreview(tab, draft, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilterActions(tab, draft)
            }
            if (expanded) {
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                BuilderBody(context, showColumns, maxBodyHeight)
            }
        }
    }
}

/** The theme's accent, faint, behind where a dragged column would land. */
internal val dropTint: Color
    @Composable get() = LocalIslands.current.accent.copy(alpha = if (JewelTheme.isDark) 0.14f else 0.08f)

/**
 * Column list and the Filter card side by side. The list is never shorter than a few columns; the card grows with its
 * conditions until it scrolls. Both show their scrollbars, so the thumb can be dragged. The bottom edge drags to set the
 * height, up to [maxHeight]; a double-click there fits it to the conditions again.
 */
@Composable
private fun BuilderBody(context: BuilderContext, showColumns: Boolean, maxHeight: Dp) {
    val tab = context.tab
    val density = LocalDensity.current
    var conditionsHeight by remember { mutableIntStateOf(0) }
    val fitted = with(density) { conditionsHeight.toDp() }.coerceIn(if (showColumns) 220.dp else 0.dp, 300.dp)
    val upper = maxHeight.coerceAtLeast(MinBodyHeight)
    val height = (tab.filterBodyHeight ?: fitted.coerceAtMost(upper)).coerceIn(MinBodyHeight, upper)
    val current by rememberUpdatedState(height)
    val bound by rememberUpdatedState(upper)
    Box(Modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxSize()) {
            if (showColumns) {
                ColumnPanel(context, Modifier.width(220.dp).fillMaxHeight())
                Divider(Orientation.Vertical, Modifier.fillMaxHeight())
            }
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Column(Modifier.fillMaxWidth().onSizeChanged { conditionsHeight = it.height }.padding(BarInset)) {
                        FilterCard(context)
                    }
                }
                // In the inset beside the card, so it never covers the card's edge.
                VerticalScrollbar(scroll, Modifier.align(Alignment.TopEnd).fillMaxHeight().padding(vertical = 2.dp, horizontal = 2.dp))
            }
        }
        // Over the body's bottom edge, just above the divider that separates it from the grid.
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(6.dp)
                .pointerHoverIcon(RowResizeCursor)
                .pointerInput(tab) { detectTapGestures(onDoubleTap = { tab.filterBodyHeight = null }) }
                .pointerInput(tab) {
                    detectDragGestures(onDragStart = { tab.filterBodyHeight = current }) { change, amount ->
                        change.consume()
                        val start = tab.filterBodyHeight ?: current
                        tab.filterBodyHeight = (start + with(density) { amount.y.toDp() }).coerceIn(MinBodyHeight, bound)
                    }
                }
        )
    }
}

/** The least the builder body can be dragged to: the card's title and a row. */
private val MinBodyHeight = 80.dp

private val RowResizeCursor = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

/** Room kept beside scrolling content so the scrollbar never covers it. */
private val ScrollbarGutter = 10.dp

/**
 * The bar's edge inset, shared by the toolbar and the body, so the filter icon lines up with the column list and Apply
 * with the Filter card's right edge. Wide enough to hold the conditions' scrollbar.
 */
internal val BarInset = 12.dp

/**
 * The conditions, in a card like the document builder's: a title with how many are active, how they combine and what
 * can be added; then the rows, or a drop target saying what to do when there are none.
 */
@Composable
private fun FilterCard(context: BuilderContext) {
    val builder = context.builder
    val root = builder.root
    val drag = context.tab.columnDrag
    val islands = LocalIslands.current
    val active = drag.hovered == ColumnDrop.Into(root)
    val shape = RoundedCornerShape(6.dp)
    val count = builder.rows().count { it.enabled }
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (active) dropTint else Color.Transparent)
            .border(1.dp, if (active) islands.accent else islands.divider, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AllIconsKeys.General.Filter, null)
            Text(tr("Filter"), fontWeight = FontWeight.SemiBold)
            if (count > 0) CountBadge(count)
            if (root.children.size > 1) {
                Spacer(Modifier.width(4.dp))
                Text(tr("Match"), color = JewelTheme.globalColors.text.info)
                CombinatorSwitch(root)
                Text(tr("of these conditions"), color = JewelTheme.globalColors.text.info)
            }
            Box(Modifier.weight(1f))
            AddLinks(context, root, depth = 0)
            if (root.children.isNotEmpty()) {
                Tooltip({ Text(tr("Remove every condition")) }) { Link(tr("Remove all"), onClick = builder::clear) }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (root.children.isEmpty()) {
                DropPlaceholder()
                Hint(tr("Without conditions, every row is shown."))
            } else {
                GroupBody(context, root, depth = 0)
            }
        }
    }
}

@Composable
internal fun CountBadge(count: Int) {
    Text(
        count.toString(),
        Modifier.clip(RoundedCornerShape(8.dp)).background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.6f)).padding(horizontal = 6.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
}

/** Where an empty builder takes its first column. */
@Composable
private fun DropPlaceholder() {
    val outline = JewelTheme.globalColors.text.info.copy(alpha = 0.45f)
    Row(
        Modifier.fillMaxWidth()
            .height(34.dp)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.General.Add, null)
        Text(tr("Drop a column here to filter by it, or double-click one"), color = JewelTheme.globalColors.text.info)
    }
}

/** Marks the column list as a place a dragged column is put back, not dropped. */
private object ColumnPanelKey

/** The table's columns, searchable, to drag onto a group or a condition (or double-click to add a condition). */
@Composable
private fun ColumnPanel(context: BuilderContext, modifier: Modifier) {
    val drag = context.tab.columnDrag
    val search = remember { TextFieldState() }
    val query = search.text.toString().trim()
    val shown =
        remember(context.columns, query) {
            if (query.isEmpty()) context.columns
            else context.columns.filter { it.name.contains(query, ignoreCase = true) || it.typeName.contains(query, ignoreCase = true) }
        }
    DisposableEffect(drag) { onDispose { drag.unregister(ColumnPanelKey) } }
    Column(
        modifier.onGloballyPositioned { drag.register(ColumnPanelKey, null, it.boundsInRoot()) }.padding(start = BarInset, end = 6.dp, top = BarInset, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Columns"), fontWeight = FontWeight.SemiBold)
            if (context.columns.isNotEmpty()) CountBadge(context.columns.size)
        }
        TextField(
            search,
            Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Search {0} columns", context.columns.size)) },
            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null) },
        )
        Hint(tr("Drag onto the filter, or double-click"))
        if (shown.isEmpty()) Text(tr("No matching column"), Modifier.padding(4.dp), color = JewelTheme.globalColors.text.info)
        val list = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().padding(end = ScrollbarGutter), state = list) {
                items(shown, key = { it.name }) { ColumnRow(context, it, query) }
            }
            VerticalScrollbar(list, Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ColumnRow(context: BuilderContext, column: FilterColumn, query: String) {
    val drag = context.tab.columnDrag
    val origin = remember { arrayOf(Offset.Zero) }
    val current by rememberUpdatedState(column)
    Tooltip({ Text(tr("Drag onto a group to add a condition, or onto a condition to use this column; double-click to add one")) }) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (drag.dragged == column.name) dropTint else Color.Transparent)
                .onGloballyPositioned { origin[0] = it.positionInRoot() }
                .pointerInput(column.name) { detectTapGestures(onDoubleTap = { context.addCondition(context.builder.root, column = current) }) }
                .pointerInput(column.name) {
                    detectDragGestures(
                        onDragStart = { drag.start(current.name, origin[0] + it) },
                        // Absolute rather than summed deltas, which drift from the real pointer.
                        onDrag = { change, _ ->
                            change.consume()
                            drag.move(current.name, origin[0] + change.position)
                        },
                        onDragEnd = drag::drop,
                        onDragCancel = drag::cancel,
                    )
                }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AllIconsKeys.Nodes.DataColumn, null)
            Text(highlight(column.name, query), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Tag(column.typeName.lowercase().take(12).ifEmpty { column.kind.label }, column.kind.color())
        }
    }
}

/** Registers [drop] as where a dragged column lands inside this element, and outlines it in the accent while it's the one. */
@Composable
private fun Modifier.columnDropTarget(drag: ColumnDragState, key: Any, drop: ColumnDrop): Modifier {
    DisposableEffect(drag, key) { onDispose { drag.unregister(key) } }
    val active = drag.hovered == drop
    val shape = RoundedCornerShape(6.dp)
    val accent = LocalIslands.current.accent
    return onGloballyPositioned { drag.register(key, drop, it.boundsInRoot()) }
        .then(if (active) Modifier.clip(shape).background(dropTint).border(1.dp, accent, shape) else Modifier)
}

private fun BuilderContext.addCondition(parent: GroupState, after: FilterNodeState? = null, column: FilterColumn? = defaultColumn()) {
    val condition = builder.addCondition(parent, column, after)
    tab.focusFilterNode = condition.id
}

private fun BuilderContext.addGroup(parent: GroupState, after: FilterNodeState? = null) {
    val nested = builder.addGroup(parent, defaultColumn(), after)
    tab.focusFilterNode = nested.children.first().id
}

/** Filter shortcuts that work anywhere in a table viewer: from the grid, the window, or inside the filter bar. */
internal fun handleFilterShortcut(tab: TableDataTab, event: KeyEvent): Boolean {
    fun done(block: () -> Unit) = true.also { block() }
    return when {
        Shortcuts.FocusFilter.matches(event) -> done { tab.focusFilter() }
        Shortcuts.ToggleFilterMode.matches(event) ->
            done {
                tab.switchFilterMode(if (tab.filterMode == FilterMode.Builder) FilterMode.Sql else FilterMode.Builder)
                tab.focusFilter()
            }
        Shortcuts.ClearFilter.matches(event) -> done { tab.clearFilter() }
        else -> false
    }
}

/**
 * Keys in a condition row (or SQL condition) of [group], which sits at [depth]. Rows are edited like lines in the SQL
 * editor: ⌘D duplicates, ⌘⌫ removes, ⌘/ disables, ⌥⇧↑/↓ moves; ↑/↓ step between rows and Esc goes back to the grid.
 */
private fun BuilderContext.onRowKey(group: GroupState, depth: Int, node: FilterNodeState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val condition = node as? ConditionState
    fun done(block: () -> Unit) = true.also { block() }
    return when {
        Shortcuts.ApplyFilter.matches(event) -> done { tab.applyFilter() }
        Shortcuts.AddCondition.matches(event) -> done { addCondition(group, after = node) }
        Shortcuts.AddConditionGroup.matches(event) -> done { if (depth < MaxDepth) addGroup(group, after = node) }
        Shortcuts.AddSqlCondition.matches(event) -> done { tab.focusFilterNode = builder.addRaw(group, after = node).id }
        Shortcuts.Duplicate.matches(event) -> done { builder.duplicate(node)?.let { tab.focusFilterNode = it.id } }
        Shortcuts.DeleteLine.matches(event) -> done { removeRow(group, node) }
        Shortcuts.Comment.matches(event) -> done { node.enabled = !node.enabled }
        Shortcuts.MoveLineUp.matches(event) -> done { if (builder.move(node, -1)) tab.focusFilterNode = node.id }
        Shortcuts.MoveLineDown.matches(event) -> done { if (builder.move(node, 1)) tab.focusFilterNode = node.id }
        Shortcuts.ToggleCombinator.matches(event) -> done { group.combinator = if (group.combinator == Combinator.All) Combinator.Any else Combinator.All }
        condition != null && Shortcuts.ChooseColumn.matches(event) -> done { tab.openFilterPicker = condition.id to FilterPicker.Column }
        condition != null && Shortcuts.ChooseOperator.matches(event) -> done { tab.openFilterPicker = condition.id to FilterPicker.Operator }
        // Only where the toggle is shown: elsewhere ⌥C still types (e.g. "ç" on macOS).
        condition != null && condition.canMatchCase(tab) && Shortcuts.MatchCase.matches(event) -> done { condition.caseSensitive = !condition.caseSensitive }
        // An open suggestion list takes ↑, ↓ and Esc itself.
        ui.suggestingIn != null -> false
        Shortcuts.PreviousCondition.matches(event) -> focusRow(node, -1)
        Shortcuts.NextCondition.matches(event) -> focusRow(node, 1)
        event.key == Key.Escape && !event.isShiftPressed && !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed -> done { tab.grid.requestFocus() }
        else -> false
    }
}

private fun BuilderContext.focusRow(node: FilterNodeState, by: Int): Boolean {
    val rows = builder.rows()
    val target = rows.getOrNull(rows.indexOf(node) + by) ?: return false
    tab.focusFilterNode = target.id
    return true
}

/** Removes a row and keeps the keyboard in the builder: the next row takes focus, or the grid once none are left. */
private fun BuilderContext.removeRow(group: GroupState, node: FilterNodeState) {
    val rows = builder.rows()
    val at = rows.indexOf(node)
    builder.remove(node)
    // A group emptied from the keyboard would be a box nothing can reach; it goes too.
    if (group !== builder.root && group.children.isEmpty()) builder.remove(group)
    val next = builder.rows().let { it.getOrNull(at) ?: it.lastOrNull() }
    if (next != null) tab.focusFilterNode = next.id else tab.grid.requestFocus()
}

private fun ConditionState.canMatchCase(tab: TableDataTab): Boolean {
    val regex = operator == FilterOperator.Regex || operator == FilterOperator.NotRegex
    return operator.matchesText && (!regex || tab.dialect.regexHonoursCase)
}

@Composable
internal fun FilterModeSwitch(tab: TableDataTab) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Tooltip({ Text(tr("Switch between builder and SQL ({0})", Shortcuts.ToggleFilterMode)) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                ViewChip(tr("Builder"), tab.filterMode == FilterMode.Builder) { tab.switchFilterMode(FilterMode.Builder) }
                ViewChip(tr("SQL"), tab.filterMode == FilterMode.Sql) { tab.switchFilterMode(FilterMode.Sql) }
            }
        }
    }
}

/**
 * The filter's state, then Clear and Apply, shared by both filter modes. One self-contained group, so it sits the same
 * way in either bar: the status reads as a note (a dot and muted text), Clear as a quiet link, and Apply stands apart.
 */
@Composable
internal fun FilterActions(tab: TableDataTab, draft: CompiledFilter? = null) {
    val invalid = draft?.isValid == false && tab.showFilterIssues
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (invalid) {
            val count = draft.issues.size
            FilterStatus(if (count == 1) tr("1 condition needs attention") else tr("{0} conditions need attention", count), JewelTheme.globalColors.text.error)
        } else if (tab.isFilterDirty) {
            FilterStatus(tr("Not applied"), LocalIslands.current.accent, JewelTheme.globalColors.text.info)
        }
        val canClear = tab.hasAppliedFilter || !tab.builder.isEmpty || tab.filter.text.isNotBlank()
        if (canClear) Tooltip({ Text(tr("Clear filter ({0})", Shortcuts.ClearFilter)) }) { Link(tr("Clear"), onClick = tab::clearFilter) }
        Tooltip({ Text(tr("Apply filter (⏎ in any field, {0})", Shortcuts.ApplyFilter)) }) {
            DefaultButton(onClick = tab::applyFilter, enabled = tab.isFilterDirty || invalid) { Text(tr("Apply")) }
        }
    }
}

/** A dot in [dot]'s colour and a short note, e.g. that the filter has changes not yet applied. */
@Composable
internal fun FilterStatus(text: String, dot: Color, color: Color = dot) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(dot))
        Text(text, color = color, maxLines = 1)
    }
}

@Composable
private fun CombinatorSwitch(group: GroupState) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Combinator.entries.forEach { combinator ->
            Tooltip({ Text((if (combinator == Combinator.All) tr("Every condition must hold (AND)") else tr("At least one condition holds (OR)")) + tr(" · {0} in a condition", Shortcuts.ToggleCombinator)) }) {
                ViewChip(combinator.label, group.combinator == combinator) { group.combinator = combinator }
            }
        }
    }
}

@Composable
private fun GroupBody(context: BuilderContext, group: GroupState, depth: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        group.children.forEachIndexed { index, child ->
            key(child.id) {
                Row(verticalAlignment = Alignment.Top) {
                    // The joining word in front of every condition but the first makes the logic readable at a glance.
                    Box(Modifier.width(40.dp).padding(top = 6.dp)) {
                        if (index > 0) Joiner(group)
                    }
                    val keys = if (child is GroupState) Modifier else Modifier.onPreviewKeyEvent { context.onRowKey(group, depth, child, it) }
                    Box(Modifier.weight(1f).then(keys)) {
                        when (child) {
                            is ConditionState -> ConditionRow(context, child)
                            is GroupState -> NestedGroup(context, child, depth + 1)
                            is RawState -> RawRow(context, child)
                        }
                    }
                }
            }
        }
    }
}

/**
 * AND or OR, as a pill so it reads apart from the conditions it joins. A group joins all its conditions one way, so a
 * click flips every joiner in it; mixing AND with OR takes a nested group, which keeps the precedence visible.
 */
@Composable
private fun Joiner(group: GroupState) {
    val other = if (group.combinator == Combinator.All) Combinator.Any else Combinator.All
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val islands = LocalIslands.current
    Tooltip({ Text(tr("Switch this group to {0} ({1} in a condition). To mix AND with OR, use + Group.", other.keyword, Shortcuts.ToggleCombinator)) }) {
        Text(
            group.combinator.keyword,
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(if (hovered) islands.accent.copy(alpha = if (JewelTheme.isDark) 0.22f else 0.12f) else islands.divider)
                .hoverable(interactions)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interactions, indication = null) { group.combinator = other }
                .padding(horizontal = 5.dp, vertical = 1.dp),
            color = if (hovered) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.text.info,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            style = JewelTheme.editorTextStyle,
        )
    }
}

@Composable
private fun NestedGroup(context: BuilderContext, group: GroupState, depth: Int) {
    val shape = RoundedCornerShape(6.dp)
    val islands = LocalIslands.current
    val drag = context.tab.columnDrag
    val drop = ColumnDrop.Into(group)
    val active = drag.hovered == drop
    DisposableEffect(drag, group) { onDispose { drag.unregister(group) } }
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { drag.register(group, drop, it.boundsInRoot()) }
            .clip(shape)
            .background(if (active) dropTint else islands.accent.copy(alpha = if (JewelTheme.isDark) 0.06f else 0.04f))
            .border(1.dp, if (active) islands.accent else islands.divider, shape)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(group.enabled, { group.enabled = it })
            Tooltip({ Text(tr("Match rows this group would not match")) }) { ViewChip("NOT", group.negated) { group.negated = !group.negated } }
            Text(tr("Match"), color = JewelTheme.globalColors.text.info)
            CombinatorSwitch(group)
            Text(tr("of"), color = JewelTheme.globalColors.text.info)
            Box(Modifier.weight(1f))
            AddLinks(context, group, depth)
            RemoveButton(tr("Remove group")) { context.builder.remove(group) }
        }
        Box(Modifier.alpha(if (group.enabled) 1f else 0.5f)) { GroupBody(context, group, depth) }
        context.issue(group)?.let { IssueText(it) }
    }
}

@Composable
private fun AddLinks(context: BuilderContext, group: GroupState, depth: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Tooltip({ Text(tr("Add condition ({0} in a condition adds one below it)", Shortcuts.AddCondition)) }) {
            Link(tr("+ Condition"), onClick = { context.addCondition(group) })
        }
        if (depth < MaxDepth) {
            Tooltip({ Text(tr("A nested group combines its conditions the other way (AND inside OR, or OR inside AND) · {0}", Shortcuts.AddConditionGroup)) }) {
                Link(tr("+ Group"), onClick = { context.addGroup(group) })
            }
        }
        Tooltip({ Text(tr("A hand-written SQL condition, e.g. length(name) > 3 · {0}", Shortcuts.AddSqlCondition)) }) {
            Link(tr("+ SQL"), onClick = { context.tab.focusFilterNode = context.builder.addRaw(group).id })
        }
    }
}

@Composable
private fun ConditionRow(context: BuilderContext, condition: ConditionState) {
    val tab = context.tab
    val column = context.columns.firstOrNull { it.name == condition.column }
    val kind = column?.kind ?: ValueKind.Text
    val issue = context.issue(condition)
    val focus = remember { FocusRequester() }
    val columnFocus = remember { FocusRequester() }
    var columnOpen by remember { mutableStateOf(false) }
    var operatorOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tab.focusFilterNode) {
        if (tab.focusFilterNode == condition.id) {
            tab.focusFilterNode = null
            // A condition without a value (e.g. "is NULL") is reached through its column.
            runCatching { if (condition.operator.arity != Arity.None) focus.requestFocus() else columnFocus.requestFocus() }
        }
    }
    LaunchedEffect(tab.openFilterPicker) {
        val (id, picker) = tab.openFilterPicker ?: return@LaunchedEffect
        if (id != condition.id) return@LaunchedEffect
        tab.openFilterPicker = null
        when (picker) {
            FilterPicker.Column -> columnOpen = true
            FilterPicker.Operator -> operatorOpen = true
        }
    }

    Column(
        Modifier.fillMaxWidth().columnDropTarget(tab.columnDrag, condition, ColumnDrop.Onto(condition)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(condition.enabled, { condition.enabled = it })
            Row(
                Modifier.weight(1f).alpha(if (condition.enabled) 1f else 0.5f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColumnPicker(
                    context.columns,
                    condition.column,
                    error = issue != null && column == null,
                    open = columnOpen,
                    onOpenChange = { columnOpen = it },
                    // Back to the row after a pick or Esc, so typing carries on with the value.
                    onDone = { tab.focusFilterNode = condition.id },
                    Modifier.width(200.dp).focusRequester(columnFocus),
                ) {
                    context.builder.setColumn(condition, it, tab.dialect.supportsRegex)
                }
                OperatorPicker(
                    kind,
                    condition.operator,
                    tab.dialect.supportsRegex,
                    open = operatorOpen,
                    onOpenChange = { operatorOpen = it },
                    onDone = { tab.focusFilterNode = condition.id },
                    Modifier.width(160.dp),
                ) {
                    context.builder.setOperator(condition, it)
                }
                ValueEditor(context, condition, column, kind, if (issue != null && column != null) Outline.Error else Outline.None, focus, Modifier.weight(1f))
                if (condition.canMatchCase(tab)) CaseToggle(condition)
            }
            RemoveButton(tr("Remove condition ({0})", Shortcuts.DeleteLine)) { context.builder.remove(condition) }
        }
        issue?.let { IssueText(it, Modifier.padding(start = 28.dp)) }
    }
}

@Composable
private fun RawRow(context: BuilderContext, raw: RawState) {
    val focus = remember { FocusRequester() }
    val tab = context.tab
    LaunchedEffect(tab.focusFilterNode) {
        if (tab.focusFilterNode == raw.id) {
            tab.focusFilterNode = null
            runCatching { focus.requestFocus() }
        }
    }
    val issue = context.issue(raw)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(raw.enabled, { raw.enabled = it })
            Tag("SQL", Color(0xFFCF8E6D))
            TextField(
                raw.sql,
                Modifier.weight(1f).alpha(if (raw.enabled) 1f else 0.5f).focusRequester(focus),
                textStyle = JewelTheme.editorTextStyle,
                outline = if (issue != null) Outline.Error else Outline.None,
                placeholder = { Text(tr("any SQL condition, e.g. length(name) > 3")) },
                onKeyboardAction = KeyboardActionHandler { tab.applyFilter() },
            )
            RemoveButton(tr("Remove condition ({0})", Shortcuts.DeleteLine)) { context.builder.remove(raw) }
        }
        issue?.let { IssueText(it, Modifier.padding(start = 28.dp)) }
    }
}

@Composable
internal fun IssueText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = JewelTheme.globalColors.text.error, fontSize = 11.sp)
}

@Composable
internal fun RemoveButton(tooltip: String, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) { IconButton(onClick = onClick) { Icon(AllIconsKeys.General.CloseSmall, tooltip) } }
}

@Composable
private fun CaseToggle(condition: ConditionState) {
    Tooltip({ Text((if (condition.caseSensitive) tr("Match case (on)") else tr("Match case (off)")) + " · ${Shortcuts.MatchCase}") }) {
        ViewChip("Aa", condition.caseSensitive) { condition.caseSensitive = !condition.caseSensitive }
    }
}

/** The SQL the builder compiles to, with values inlined; copyable, or editable as SQL. */
@Composable
private fun SqlPreview(tab: TableDataTab, draft: CompiledFilter, modifier: Modifier) {
    val preview = draft.filter?.preview
    val info = JewelTheme.globalColors.text.info
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (preview != null) {
            // The clause shrinks (ellipsized) first, so the actions after it stay visible.
            Tooltip({ Text("WHERE $preview", style = JewelTheme.editorTextStyle) }, Modifier.weight(1f, fill = false)) {
                Text(
                    "WHERE $preview",
                    color = info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = JewelTheme.editorTextStyle,
                    fontSize = 11.sp,
                )
            }
            Tooltip({ Text(tr("Copy WHERE clause")) }) { IconButton(onClick = { copyToClipboard(preview) }) { Icon(AllIconsKeys.Actions.Copy, tr("Copy")) } }
            Tooltip({ Text(tr("Edit as SQL ({0})", Shortcuts.ToggleFilterMode)) }) { Link(tr("Edit as SQL"), onClick = { tab.switchFilterMode(FilterMode.Sql) }) }
        } else if (draft.isValid) {
            Text(tr("No active conditions"), color = info, fontSize = 11.sp)
        }
    }
}

/** A short coloured label, e.g. a column's database type. */
@Composable
internal fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = color,
        fontSize = 10.sp,
        maxLines = 1,
    )
}

internal fun ValueKind.color(): Color =
    when (this) {
        ValueKind.Text -> Color(0xFF6AAB73)
        ValueKind.Number -> Color(0xFF56A8F5)
        ValueKind.Boolean -> Color(0xFFCF8E6D)
        ValueKind.Date, ValueKind.Time, ValueKind.DateTime -> Color(0xFF9876D8)
        else -> Color(0xFF8C8C8C)
    }

/**
 * A combo-box-like button for pickers that open their own popup. Drawn as an input, in the text field's colours, corners
 * and height, so a condition reads as three boxes: column, operator, value.
 */
@Composable
internal fun PickerButton(error: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val style = JewelTheme.textFieldStyle
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val shape = RoundedCornerShape(style.metrics.cornerSize)
    val border =
        when {
            error -> JewelTheme.globalColors.outlines.error
            focused -> JewelTheme.globalColors.outlines.focused
            hovered -> style.colors.borderHovered
            else -> style.colors.border
        }
    Row(
        modifier.height(style.metrics.minSize.height)
            .clip(shape)
            .background(if (focused) style.colors.backgroundFocused else style.colors.background, shape)
            .border(if (error || focused) 2.dp else style.metrics.borderWidth, border, shape)
            .hoverable(interactions)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Searchable column list: tables can have hundreds of columns, so it filters as you type and works from the keyboard. */
@Composable
internal fun ColumnPicker(
    columns: List<FilterColumn>,
    selected: String,
    error: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
    onSelect: (FilterColumn) -> Unit,
) {
    var height by remember { mutableIntStateOf(0) }
    val current = columns.firstOrNull { it.name == selected }
    Box(Modifier.onSizeChanged { height = it.height }) {
        PickerButton(error, modifier, onClick = { onOpenChange(true) }) {
            Icon(AllIconsKeys.Nodes.DataColumn, null)
            Text(
                selected.ifEmpty { tr("Choose column") },
                Modifier.weight(1f),
                color = if (current == null) JewelTheme.globalColors.text.info else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            current?.let { Tag(it.typeName.lowercase().take(14).ifEmpty { it.kind.label }, it.kind.color()) }
            Icon(AllIconsKeys.General.ChevronDown, null)
        }
        if (open) {
            Popup(
                offset = IntOffset(0, height + 2),
                onDismissRequest = { onOpenChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                ColumnList(columns, selected, onDismiss = { onOpenChange(false); onDone() }) {
                    onSelect(it)
                    onOpenChange(false)
                    onDone()
                }
            }
        }
    }
}

@Composable
private fun ColumnList(columns: List<FilterColumn>, selected: String, onDismiss: () -> Unit, onSelect: (FilterColumn) -> Unit) {
    val query = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    val text = query.text.toString().trim()
    val matches =
        remember(columns, text) {
            if (text.isEmpty()) columns
            else
                columns.filter { it.name.contains(text, ignoreCase = true) || it.typeName.contains(text, ignoreCase = true) }
                    .sortedByDescending { it.name.startsWith(text, ignoreCase = true) }
        }
    var highlighted by remember(matches) { mutableIntStateOf(matches.indexOfFirst { it.name == selected }.coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(highlighted) { if (matches.isNotEmpty()) listState.scrollToItem((highlighted - 3).coerceAtLeast(0)) }

    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.width(320.dp)
            .clip(shape)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextField(
            query,
            Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> highlighted = (highlighted + 1).coerceAtMost(matches.lastIndex.coerceAtLeast(0))
                    Key.DirectionUp -> highlighted = (highlighted - 1).coerceAtLeast(0)
                    Key.Enter, Key.NumPadEnter, Key.Tab -> matches.getOrNull(highlighted)?.let(onSelect)
                    Key.Escape -> onDismiss()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
            placeholder = { Text(tr("Search {0} columns", columns.size)) },
            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null) },
        )
        if (matches.isEmpty()) {
            Text(tr("No matching column"), Modifier.padding(8.dp), color = JewelTheme.globalColors.text.info)
        }
        LazyColumn(Modifier.heightIn(max = 300.dp), state = listState) {
            itemsIndexed(matches, key = { _, column -> column.name }) { index, column ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (index == highlighted) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
                        .clickable { onSelect(column) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(highlight(column.name, text), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Tag(column.typeName.lowercase().ifEmpty { column.kind.label }, column.kind.color())
                }
            }
        }
    }
}

/** Operators for the column's type, in sections; opens from its shortcut too, and works from the keyboard. */
@Composable
private fun OperatorPicker(
    kind: ValueKind,
    selected: FilterOperator,
    regex: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
    onSelect: (FilterOperator) -> Unit,
) {
    Box {
        PickerButton(error = false, modifier, onClick = { onOpenChange(true) }) {
            Text(selected.label(kind), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(AllIconsKeys.General.ChevronDown, null)
        }
        if (open) {
            PopupMenu(
                onDismissRequest = { mode ->
                    onOpenChange(false)
                    if (mode == InputMode.Keyboard) onDone()
                    true
                },
                modifier = Modifier.focusProperties { canFocus = true },
                horizontalAlignment = Alignment.Start,
            ) {
                FilterOperator.forKind(kind, regex).forEachIndexed { index, section ->
                    if (index > 0) separator()
                    section.forEach { operator ->
                        selectableItem(selected = operator == selected, onClick = {
                            onSelect(operator)
                            onOpenChange(false)
                            onDone()
                        }) { Text(operator.label(kind)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueEditor(
    context: BuilderContext,
    condition: ConditionState,
    column: FilterColumn?,
    kind: ValueKind,
    outline: Outline,
    focus: FocusRequester,
    modifier: Modifier,
) {
    val operator = condition.operator
    val info = JewelTheme.globalColors.text.info
    when {
        operator.arity == Arity.None -> Box(modifier)
        operator == FilterOperator.LastDays ->
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ValueField(context, condition.value, null, ValueKind.Number, list = false, outline, "7", Modifier.width(90.dp), focus)
                Text(tr("days, including today"), color = info)
            }
        operator.arity == Arity.Two ->
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ValueField(context, condition.value, column, kind, list = false, outline, placeholder(kind, operator), Modifier.weight(1f), focus)
                Text(tr("and"), color = info)
                ValueField(context, condition.value2, column, kind, list = false, outline, placeholder(kind, operator), Modifier.weight(1f))
            }
        else -> ValueField(context, condition.value, column, kind, list = operator.arity == Arity.List, outline, placeholder(kind, operator), modifier, focus)
    }
}

private fun placeholder(kind: ValueKind, operator: FilterOperator): String =
    when {
        operator.arity == Arity.List -> if (kind.isTemporal) "2026-10-01, 2026-10-15" else "a, b, \"c, d\", NULL"
        operator == FilterOperator.Like || operator == FilterOperator.NotLike -> tr("pattern: % any text, _ one character")
        operator == FilterOperator.Regex || operator == FilterOperator.NotRegex -> tr("regular expression")
        else ->
            when (kind) {
                ValueKind.Number -> tr("number")
                ValueKind.Date -> "2026-10-28, today-7d, 1 month ago…"
                ValueKind.DateTime -> "2026-10-28 13:45, now-2h, yesterday…"
                ValueKind.Time -> "13:45"
                ValueKind.Uuid -> tr("uuid")
                ValueKind.Json -> tr("text in the JSON")
                else -> tr("value")
            }
    }

/** Kinds whose values are worth suggesting from the column's data. */
private val SuggestedKinds = setOf(ValueKind.Text, ValueKind.Number, ValueKind.Uuid, ValueKind.Other)

/**
 * A value input: Enter applies the filter; suggests distinct values from the column as you type (or on ⌃Space); date
 * fields offer quick picks and show what a relative date resolves to; lists count their values.
 */
@Composable
private fun ValueField(
    context: BuilderContext,
    state: TextFieldState,
    column: FilterColumn?,
    kind: ValueKind,
    list: Boolean,
    outline: Outline,
    placeholder: String,
    modifier: Modifier,
    focus: FocusRequester? = null,
) {
    val tab = context.tab
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var highlighted by remember { mutableIntStateOf(0) }
    // Text the user dismissed suggestions for (Esc, or by accepting one); they come back on the next edit.
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    var explicit by remember { mutableIntStateOf(0) }
    var height by remember { mutableIntStateOf(0) }
    var width by remember { mutableIntStateOf(0) }
    val suggest = column != null && column.kind in SuggestedKinds

    LaunchedEffect(state, column?.name, suggest) {
        if (!suggest || column == null) return@LaunchedEffect
        snapshotFlow { Triple(state.text.toString(), focused, explicit) }.collectLatest { (text, isFocused, _) ->
            if (!isFocused) {
                suggestions = emptyList()
                return@collectLatest
            }
            delay(150) // Let fast typing settle before querying.
            val token = if (list) lastToken(text) else text
            suggestions = tab.suggestValues(column.name, token).filterNot { it == token }
            highlighted = 0
        }
    }
    val open = focused && suggestions.isNotEmpty() && state.text.toString() != dismissedFor
    SideEffect {
        if (open) context.ui.suggestingIn = state else if (context.ui.suggestingIn === state) context.ui.suggestingIn = null
    }
    DisposableEffect(state) { onDispose { if (context.ui.suggestingIn === state) context.ui.suggestingIn = null } }

    fun accept(index: Int) {
        val value = suggestions.getOrNull(index) ?: return
        val text = state.text.toString()
        val next = if (list) text.substring(0, lastSeparator(text) + 1).let { if (it.isEmpty()) "" else "$it " } + quoteListItem(value) + ", " else value
        state.setTextAndPlaceCursorAtEnd(next)
        dismissedFor = next
    }

    Box(modifier.onSizeChanged { height = it.height; width = it.width }) {
        TextField(
            state,
            Modifier.fillMaxWidth()
                .let { if (focus != null) it.focusRequester(focus) else it }
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.isCtrlPressed && event.key == Key.Spacebar) {
                        dismissedFor = null
                        explicit++
                        return@onPreviewKeyEvent true
                    }
                    if (!open) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> highlighted = (highlighted + 1).mod(suggestions.size)
                        Key.DirectionUp -> highlighted = (highlighted - 1).mod(suggestions.size)
                        Key.Enter, Key.NumPadEnter, Key.Tab -> accept(highlighted)
                        Key.Escape -> dismissedFor = state.text.toString()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
            outline = outline,
            textStyle = JewelTheme.editorTextStyle,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ValueHint(state, kind, list) },
            onKeyboardAction = KeyboardActionHandler {
                dismissedFor = state.text.toString()
                tab.applyFilter()
            },
        )
        if (open) {
            val density = LocalDensity.current
            Popup(offset = IntOffset(0, height + 2), properties = PopupProperties(focusable = false)) {
                SuggestionList(suggestions, highlighted, if (list) lastToken(state.text.toString()) else state.text.toString(), with(density) { width.toDp() }, ::accept)
            }
        }
    }
}

@Composable
private fun SuggestionList(values: List<String>, highlighted: Int, prefix: String, width: androidx.compose.ui.unit.Dp, onAccept: (Int) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val listState = rememberLazyListState()
    LaunchedEffect(highlighted) { listState.scrollToItem((highlighted - 3).coerceAtLeast(0)) }
    Box(
        Modifier.width(width.coerceIn(200.dp, 480.dp))
            .clip(shape)
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
    ) {
        LazyColumn(Modifier.heightIn(max = 240.dp).padding(4.dp), state = listState) {
            itemsIndexed(values) { index, value ->
                Text(
                    highlight(value.replace('\n', '⏎'), prefix),
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (index == highlighted) JewelTheme.treeStyle.colors.backgroundSelectedActive else Color.Transparent)
                        .clickable { onAccept(index) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = JewelTheme.editorTextStyle,
                )
            }
        }
    }
}

private val DayFormat = I18n.dates("EEE d MMM yyyy")
private val MomentFormat = I18n.dates("EEE d MMM yyyy, HH:mm")

/** Inside a value field, after the text: list size, a relative date's resolved value, date quick picks. */
@Composable
private fun ValueHint(state: TextFieldState, kind: ValueKind, list: Boolean) {
    val info = JewelTheme.globalColors.text.info
    val text = state.text.toString()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (list && text.isNotBlank()) {
            val count = FilterValues.parseList(text).size
            Text(if (count == 1) tr("1 value") else tr("{0} values", count), color = info, fontSize = 11.sp)
        }
        if (kind.isTemporal && !list) {
            resolvedDate(text)?.let { Text("= $it", color = info, fontSize = 11.sp, maxLines = 1) }
            if (kind != ValueKind.Time) QuickPicks(state, kind)
        }
    }
}

/** What a relative value like `today-7d` means right now; null for absolute or unparsable input. */
private fun resolvedDate(text: String): String? {
    if (text.isBlank() || text.none { it.isLetter() || it == '+' } && !text.trim().startsWith("-")) return null
    return when (val value = runCatching { FilterValues.temporal(text, LocalDateTime.now()) }.getOrNull()) {
        is TemporalValue.Day -> DayFormat.format(value.date)
        is TemporalValue.Moment -> MomentFormat.format(value.dateTime)
        else -> null
    }
}

private val DateQuickPicks =
    listOf(tr("Today") to "today", tr("Yesterday") to "yesterday", tr("Tomorrow") to "tomorrow", tr("7 days ago") to "today-7d", tr("30 days ago") to "today-30d", tr("1 month ago") to "today-1mo", tr("1 year ago") to "today-1y")
private val DateTimeQuickPicks =
    listOf(tr("Now") to "now", tr("1 hour ago") to "now-1h", tr("24 hours ago") to "now-24h") + DateQuickPicks

@Composable
private fun QuickPicks(state: TextFieldState, kind: ValueKind) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip({ Text(tr("Quick picks; you can also type expressions like today-7d or now-2h")) }) {
            IconButton(onClick = { open = !open }, Modifier.width(20.dp)) { Icon(AllIconsKeys.General.ChevronDown, tr("Quick picks")) }
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                val picks = if (kind == ValueKind.DateTime) DateTimeQuickPicks else DateQuickPicks
                picks.forEachIndexed { index, (label, expression) ->
                    if (kind == ValueKind.DateTime && index == 3) separator()
                    selectableItem(selected = false, onClick = {
                        state.setTextAndPlaceCursorAtEnd(expression)
                        open = false
                    }) {
                        Row(Modifier.widthIn(min = 180.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label)
                            Text(expression, color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle)
                        }
                    }
                }
            }
        }
    }
}

/** Bolds the part of [label] matching [query] (case-insensitively). */
private fun highlight(label: String, query: String) = buildAnnotatedString {
    val at = if (query.isEmpty()) -1 else label.indexOf(query, ignoreCase = true)
    if (at < 0) {
        append(label)
    } else {
        append(label.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label.substring(at, at + query.length)) }
        append(label.substring(at + query.length))
    }
}

/** Index of the last list separator outside quotes, or -1. */
internal fun lastSeparator(text: String): Int {
    var quote: Char? = null
    var last = -1
    text.forEachIndexed { i, c ->
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == ',' || c == '\n' -> last = i
        }
    }
    return last
}

/** The list item being typed, without its opening quote. */
internal fun lastToken(text: String): String = text.substring(lastSeparator(text) + 1).trimStart().trimStart('"', '\'')

/** A value as a list item, quoted when it would otherwise split, trim or read as NULL. */
internal fun quoteListItem(value: String): String =
    if (value.isEmpty() || value.any { it == ',' || it == '\n' || it == '"' || it == '\'' } || value != value.trim() || value.equals("null", ignoreCase = true))
        "\"" + value.replace("\"", "\"\"") + "\""
    else value
