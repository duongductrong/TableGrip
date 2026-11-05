package dev.tablegrip.app.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.icon.IconKey
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.grid.ColumnDragOut
import dev.tablegrip.app.table.ToolButton
import dev.tablegrip.app.table.ViewChip
import dev.tablegrip.app.table.ViewChipGroup
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.Shortcuts
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The parts of the builder a field can be dropped on. */
internal enum class FieldZone { Filter, Sort, Projection }

/**
 * A field being dragged from the builder's list or out of the grid's header. Zones register where they are; the
 * pointer decides which one it's over. Positions are in the window's coordinates. Starting a drag opens the builder,
 * so a header dragged up out of the grid finds somewhere to land.
 */
internal class FieldDragState(private val onStart: () -> Unit, private val onDrop: (String, FieldZone) -> Unit) : ColumnDragOut {
    var dragged by mutableStateOf<String?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set

    private val zones = mutableStateMapOf<FieldZone, Rect>()

    val hovered: FieldZone?
        get() = if (dragged == null) null else zones.entries.firstOrNull { it.value.contains(pointer) }?.key

    fun register(zone: FieldZone, bounds: Rect) {
        zones[zone] = bounds
    }

    fun unregister(zone: FieldZone) {
        zones.remove(zone)
    }

    fun start(field: String, at: Offset) {
        dragged = field
        pointer = at
        onStart()
    }

    override fun move(column: String, at: Offset) {
        if (dragged == null) start(column, at) else pointer = at
    }

    override fun drop(column: String) = drop()

    fun drop() {
        val field = dragged
        val zone = hovered
        dragged = null
        if (field != null && zone != null) onDrop(field, zone)
    }

    override fun cancel() {
        dragged = null
    }
}

/**
 * Fields on the left, to drag onto the filter, sort or projection on the right (or double-click for a condition).
 * Changes are written into the filter bar's fields as they're made, and read back from them when those are typed in.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun QueryBuilderPanel(tab: DocumentBrowserTab) {
    val builder = tab.builder
    // Builder to text: every change, once it differs from what the two last agreed on.
    LaunchedEffect(builder) {
        snapshotFlow { builder.render(tab::fieldKind) }.collect { rendered ->
            if (rendered != builder.synced) {
                builder.synced = rendered
                tab.writeFromBuilder(rendered)
            }
        }
    }
    // Text to builder: typing in the fields, Clear, sorting from a header, "Filter by This Value".
    LaunchedEffect(builder) {
        snapshotFlow { Triple(tab.filter.text.toString(), tab.sort.text.toString(), tab.projection.text.toString()) }
            .debounce(250)
            .collect { tab.readIntoBuilder(force = false) }
    }
    val rendered = builder.render(tab::fieldKind)
    Row(Modifier.fillMaxWidth().height(290.dp)) {
        FieldList(tab, Modifier.width(220.dp).fillMaxHeight())
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (builder.unreadable) {
                Notice(AllIconsKeys.General.Information, "The current query is more than the builder can show. It's kept until you change something here, which replaces it.")
            }
            rendered.problems.forEach { Notice(AllIconsKeys.General.Warning, it) }
            FilterCard(tab)
            // Sort and projection shape the documents the filter found, side by side under it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortCard(tab, Modifier.weight(1f))
                ShowCard(tab, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Notice(icon: IconKey, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Text(text, color = JewelTheme.globalColors.text.info)
    }
}

// region Fields

@Composable
private fun FieldList(tab: DocumentBrowserTab, modifier: Modifier) {
    val search = remember { TextFieldState() }
    val fields = tab.completionFields
    val query = search.text.toString().trim().lowercase()
    val shown = fields.filter { query.isEmpty() || query in it.path.lowercase() }
    Column(modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fields", fontWeight = FontWeight.SemiBold)
            if (fields.isNotEmpty()) Text(fields.size.toString(), color = JewelTheme.globalColors.text.info)
        }
        TextField(search, Modifier.fillMaxWidth(), placeholder = { Text("Search fields") })
        Text("Drag onto a card on the right", color = JewelTheme.globalColors.text.info)
        if (fields.isEmpty()) Text("No fields seen yet.", color = JewelTheme.globalColors.text.info)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.path }) { field ->
                FieldRow(tab, field.path, field.types.joinToString(" | "))
            }
        }
    }
}

@Composable
private fun FieldRow(tab: DocumentBrowserTab, path: String, types: String) {
    val drag = tab.fieldDrag
    val origin = remember { arrayOf(Offset.Zero) }
    val current by rememberUpdatedState(path)
    val shape = RoundedCornerShape(4.dp)
    Tooltip({ Text("Drag onto Filter, Sort or Show; double-click to add a condition") }) {
        Row(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(if (drag.dragged == path) JewelTheme.globalColors.outlines.focused.copy(alpha = 0.15f) else Color.Transparent)
                .onGloballyPositioned { origin[0] = it.positionInRoot() }
                .pointerInput(path) { detectTapGestures(onDoubleTap = { tab.dropField(current, FieldZone.Filter) }) }
                .pointerInput(path) {
                    detectDragGestures(
                        onDragStart = { drag.start(current, origin[0] + it) },
                        // Absolute rather than summed deltas, which drift from the real pointer.
                        onDrag = { change, _ ->
                            change.consume()
                            drag.move(current, origin[0] + change.position)
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
            Text(path, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(types, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, color = JewelTheme.globalColors.text.info)
        }
    }
}

// endregion

// region Cards

/** The cards of the builder, by the part of the query each one writes. */
internal enum class BuilderScope(val zone: FieldZone, val title: String, val icon: IconKey) {
    Filter(FieldZone.Filter, "Filter", AllIconsKeys.General.Filter),
    Sort(FieldZone.Sort, "Sort", AllIconsKeys.ObjectBrowser.Sorted),
    Show(FieldZone.Projection, "Show", AllIconsKeys.Actions.Show);

    companion object {
        fun of(zone: FieldZone) = entries.first { it.zone == zone }
    }
}

/** Outlines of places that take a dropped field: the theme's secondary text, faint, so they show on any panel. */
private val dashed: Color
    @Composable get() = JewelTheme.globalColors.text.info.copy(alpha = 0.45f)

/** The theme's accent, used only for what the pointer is over while a field is dragged. */
private val accent: Color
    @Composable get() = JewelTheme.globalColors.outlines.focused

/**
 * A card fields can be dropped on, in the theme's panel colours: a title row, a divider, then its rows or chips; the
 * query they make shows in the filter bar's fields above. Each card keeps a solid outline in the divider colour; while a field
 * is dragged, the one under the pointer takes the accent.
 */
@Composable
private fun ScopeCard(
    tab: DocumentBrowserTab,
    scope: BuilderScope,
    count: Int,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val drag = tab.fieldDrag
    DisposableEffect(scope) { onDispose { drag.unregister(scope.zone) } }
    val active = drag.hovered == scope.zone
    val outline = if (active) accent else LocalIslands.current.divider
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .onGloballyPositioned { drag.register(scope.zone, it.boundsInRoot()) }
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.08f) else Color.Transparent)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = stroke),
                )
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(scope.icon, null)
            Text(scope.title, fontWeight = FontWeight.SemiBold)
            if (count > 0) CountBadge(count)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        count.toString(),
        Modifier.clip(RoundedCornerShape(8.dp)).background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.6f)).padding(horizontal = 6.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Where a card with nothing in it yet takes a field. */
@Composable
private fun DropPlaceholder(text: String, detail: String? = null) {
    val outline = dashed
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
        Text(text, color = JewelTheme.globalColors.text.info)
        detail?.let { Text(it, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun FilterCard(tab: DocumentBrowserTab) {
    val builder = tab.builder
    val scope = BuilderScope.Filter
    ScopeCard(
        tab,
        scope,
        count = builder.conditions.count { it.enabled && it.field.isNotBlank() },
        actions = {
            if (builder.conditions.size > 1) {
                Text("Match", color = JewelTheme.globalColors.text.info)
                ViewChipGroup {
                    ViewChip("all", !builder.matchAny) { builder.matchAny = false }
                    ViewChip("any", builder.matchAny) { builder.matchAny = true }
                }
            }
            Link("Add condition", onClick = { builder.focus = builder.addCondition() })
            if (builder.conditions.isNotEmpty()) Link("Clear", onClick = { builder.conditions.clear() })
        },
    ) {
        builder.conditions.forEachIndexed { index, condition ->
            ConditionRow(tab, condition, if (index == 0) "WHERE" else if (builder.matchAny) "OR" else "AND")
        }
        if (builder.conditions.isEmpty()) DropPlaceholder("Drop a field to filter by it", "or double-click one. Without conditions, every document matches.")
    }
}

/** A condition, led by how it joins the ones before it. */
@Composable
private fun ConditionRow(tab: DocumentBrowserTab, condition: FilterCondition, joiner: String) {
    val builder = tab.builder
    val fields = tab.completionFields.map { it.path }.let { if (condition.field.isEmpty() || condition.field in it) it else listOf(condition.field) + it }
    val kind = tab.fieldKind(condition.field)
    Row(
        Modifier.alpha(if (condition.enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            joiner,
            Modifier.width(54.dp).clip(RoundedCornerShape(4.dp)).background(JewelTheme.globalColors.borders.normal.copy(alpha = 0.45f)).padding(vertical = 2.dp),
            style = JewelTheme.editorTextStyle.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
            color = JewelTheme.globalColors.text.info,
        )
        Tooltip({ Text(if (condition.enabled) "Leave out of the query" else "Put back into the query") }) {
            Checkbox(condition.enabled, { condition.enabled = it })
        }
        ListComboBox(
            items = if (condition.field.isEmpty()) listOf("Field…") + fields else fields,
            selectedIndex = if (condition.field.isEmpty()) 0 else fields.indexOf(condition.field).coerceAtLeast(0),
            onSelectedItemChange = { index ->
                val list = if (condition.field.isEmpty()) listOf("") + fields else fields
                list.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { condition.field = it }
            },
            modifier = Modifier.width(180.dp),
        )
        val operators = operatorsFor(kind)
        ListComboBox(
            items = operators.map { it.symbol },
            selectedIndex = operators.indexOf(condition.operator).coerceAtLeast(0),
            onSelectedItemChange = { condition.operator = operators[it] },
            modifier = Modifier.width(140.dp),
        )
        if (condition.operator.takesValue) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(builder.focus) {
                if (builder.focus === condition) {
                    runCatching { focus.requestFocus() }
                    builder.focus = null
                }
            }
            TextField(
                condition.value,
                Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || Shortcuts.ApplyFilter.matches(event))) {
                        tab.reload()
                        true
                    } else false
                },
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text(placeholder(tab, condition, kind)) },
            )
        } else Box(Modifier.weight(1f))
        ToolButton(AllIconsKeys.General.CloseSmall, "Remove condition") { builder.conditions.remove(condition) }
    }
}

/** The operators that make sense for a field of [kind]; all of them when it's unknown or mixed. */
private fun operatorsFor(kind: BsonKind?): List<FilterOperator> {
    val text = listOf(FilterOperator.Contains, FilterOperator.StartsWith, FilterOperator.EndsWith, FilterOperator.Regex)
    val existence = listOf(FilterOperator.Exists, FilterOperator.Missing)
    val compare = listOf(FilterOperator.Eq, FilterOperator.Ne, FilterOperator.Gt, FilterOperator.Gte, FilterOperator.Lt, FilterOperator.Lte, FilterOperator.In, FilterOperator.Nin)
    return when (kind) {
        BsonKind.String -> listOf(FilterOperator.Eq, FilterOperator.Ne) + text + listOf(FilterOperator.In, FilterOperator.Nin) + existence
        BsonKind.Bool -> listOf(FilterOperator.Eq, FilterOperator.Ne) + existence
        BsonKind.Array -> listOf(FilterOperator.Eq, FilterOperator.In, FilterOperator.Nin, FilterOperator.Size) + existence
        BsonKind.ObjectId -> listOf(FilterOperator.Eq, FilterOperator.Ne, FilterOperator.In, FilterOperator.Nin, FilterOperator.Gt, FilterOperator.Lt) + existence
        null, BsonKind.Other, BsonKind.Null -> FilterOperator.entries
        else -> compare + existence
    }
}

private fun placeholder(tab: DocumentBrowserTab, condition: FilterCondition, kind: BsonKind?): String {
    val example = tab.completionFields.firstOrNull { it.path == condition.field }?.values?.firstOrNull()?.let { if (kind == BsonKind.String) it.trim('\'') else it }
    return when {
        condition.operator.isList -> "values, comma-separated" + (example?.let { " (e.g. $it)" } ?: "")
        condition.operator == FilterOperator.Regex -> "pattern, e.g. ^A.*z$"
        condition.operator == FilterOperator.Size -> "number of elements"
        condition.operator.isText -> "text, any case"
        kind == BsonKind.Date -> "2026-10-31 or 2026-10-31T09:30:00Z"
        example != null -> "e.g. $example"
        else -> "value"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortCard(tab: DocumentBrowserTab, modifier: Modifier) {
    val builder = tab.builder
    val scope = BuilderScope.Sort
    ScopeCard(
        tab,
        scope,
        count = builder.sort.size,
        modifier = modifier,
        actions = { if (builder.sort.isNotEmpty()) Link("Clear", onClick = { builder.sort.clear() }) },
    ) {
        if (builder.sort.isEmpty()) {
            DropPlaceholder("Drop fields to sort by")
            Hint("Unsorted: documents come in the order the server keeps them.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                builder.sort.forEachIndexed { index, sort ->
                    Chip(
                        label = (if (builder.sort.size > 1) "${index + 1}  " else "") + sort.field,
                        trailing = if (sort.ascending) "A→Z" else "Z→A",
                        tooltip = if (sort.ascending) "Ascending (1); click for descending" else "Descending (-1); click for ascending",
                        onClick = { sort.ascending = !sort.ascending },
                        onRemove = { builder.sort.remove(sort) },
                    )
                }
            }
            Hint(if (builder.sort.size > 1) "Ties in a field are ordered by the next one." else "Click a field to flip its direction.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowCard(tab: DocumentBrowserTab, modifier: Modifier) {
    val builder = tab.builder
    val scope = BuilderScope.Show
    ScopeCard(
        tab,
        scope,
        count = builder.projection.size,
        modifier = modifier,
        actions = { if (builder.projection.isNotEmpty()) Link("Clear", onClick = { builder.projection.clear() }) },
    ) {
        if (builder.projection.isEmpty()) {
            DropPlaceholder("Drop fields to show only those")
            Hint("All fields are shown.")
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                builder.projection.forEach { field ->
                    Chip(
                        label = field.field,
                        trailing = if (field.include) "shown" else "hidden",
                        tooltip = if (field.include) "Shown (1); click to hide it instead" else "Hidden (0); click to show it instead",
                        onClick = { field.include = !field.include },
                        onRemove = { builder.projection.remove(field) },
                        muted = !field.include,
                    )
                }
            }
            val shown = builder.projection.filter { it.field != "_id" }
            Hint(
                when {
                    shown.isEmpty() || shown.all { !it.include } -> "Every other field is shown."
                    else -> "Only these fields" + (if (builder.projection.none { it.field == "_id" && !it.include }) ", and _id," else "") + " are shown."
                }
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
}

/** A sort or projection field, in its card's colour; [trailing] says what it does, and a click flips it. */
@Composable
private fun Chip(label: String, trailing: String, tooltip: String, onClick: () -> Unit, onRemove: () -> Unit, muted: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    val outline = JewelTheme.globalColors.borders.normal
    Row(
        Modifier.height(26.dp)
            .clip(shape)
            .background(outline.copy(alpha = if (muted) 0.1f else 0.35f))
            .border(1.dp, outline, shape)
            .padding(start = 10.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tooltip({ Text(tooltip) }) {
            Row(Modifier.clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    color = if (muted) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.normal,
                    textDecoration = if (muted) TextDecoration.LineThrough else null,
                )
                Text(trailing, color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
            }
        }
        ToolButton(AllIconsKeys.General.CloseSmall, "Remove", onClick = onRemove)
    }
}

// endregion

/** The dragged field following the pointer, naming the card it would land on. [origin] is the view's position in the window. */
@Composable
internal fun FieldDragGhost(drag: FieldDragState, origin: Offset) {
    val field = drag.dragged ?: return
    val gap = with(LocalDensity.current) { 12.dp.toPx() }
    val at = drag.pointer - origin + Offset(gap, gap / 2)
    val target = drag.hovered?.let(BuilderScope::of)
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .shadow(6.dp, shape)
            .background(JewelTheme.globalColors.panelBackground, shape)
            .border(1.dp, if (target != null) accent else JewelTheme.globalColors.borders.normal, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AllIconsKeys.Nodes.DataColumn, null)
        Text(field, fontWeight = FontWeight.SemiBold)
        if (target != null) {
            Icon(AllIconsKeys.General.ArrowRight, null)
            Text(target.title)
        } else {
            Text("Drop on Filter, Sort or Show", color = JewelTheme.globalColors.text.info)
        }
    }
}
