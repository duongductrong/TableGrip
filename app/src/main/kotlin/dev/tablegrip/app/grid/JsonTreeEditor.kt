package dev.tablegrip.app.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.ui.copyToClipboard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.VerticalScrollbar
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * An inline key or value edit that hasn't been confirmed yet. The popup calls [flush] before it applies or closes, so
 * what the user typed is never lost by clicking away.
 */
internal class JsonTreeDraft {
    /** Confirms the open field; false (and the field stays open) when what's typed can't be used. */
    var confirm: (() -> Boolean)? = null

    /** Confirms the open field, or drops it when what's typed can't be used. */
    var flush: (() -> Unit)? = null

    fun confirmNow(): Boolean = confirm?.invoke() ?: true

    fun flushNow() {
        flush?.invoke()
    }
}

private data class TreeEdit(val path: JsonPath, val key: Boolean)

/**
 * Edits a JSON object or array as a tree: click a value to change it, double-click a key to rename it, and use the row's
 * buttons to change a type, add or remove. Every change goes through [onChange] as a whole, valid value; the tree never
 * produces text that doesn't parse, and nodes the user didn't touch keep their exact content.
 */
@Composable
internal fun JsonTreeEditor(root: JsonElement, onChange: (JsonElement) -> Unit, draft: JsonTreeDraft, colors: GridColors, modifier: Modifier = Modifier) {
    var collapsed by remember { mutableStateOf(jsonContainersFrom(root, 2)) }
    var selected by remember { mutableStateOf<JsonPath?>(null) }
    var editing by remember { mutableStateOf<TreeEdit?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val rows = remember(root, collapsed) { jsonTreeRows(root, collapsed) }
    val list = rememberLazyListState()
    val treeFocus = remember { FocusRequester() }

    fun change(updated: JsonElement, select: JsonPath? = selected) {
        problem = null
        selected = select
        onChange(updated)
    }

    fun startEdit(path: JsonPath, key: Boolean) {
        draft.flushNow()
        selected = path
        editing = TreeEdit(path, key)
    }

    fun addTo(container: JsonPath) {
        draft.flushNow()
        val (updated, added) = root.withChildAdded(container)
        collapsed = collapsed - setOf(container)
        change(updated, added)
        // A new key opens for naming; a new array item for its value.
        editing = TreeEdit(added, key = added.last() is JsonStep.Key)
    }

    Column(modifier.focusRequester(treeFocus).focusable()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val path = selected?.let(::jsonPathText) ?: "$"
            Text(path, Modifier.weight(1f, fill = false), style = JewelTheme.editorTextStyle, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TreeIconButton(AllIconsKeys.Actions.Copy, "Copy path") { copyToClipboard(path) }
            Box(Modifier.weight(1f))
            problem?.let { Text(it, color = ErrorColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TreeIconButton(AllIconsKeys.General.Add, if (root is JsonArray) "Add item" else "Add key") { addTo(emptyList()) }
            TreeIconButton(AllIconsKeys.Actions.Expandall, "Expand all") { collapsed = emptySet() }
            TreeIconButton(AllIconsKeys.Actions.Collapseall, "Collapse all") { collapsed = jsonContainersFrom(root, 1) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().topLine(colors.grid)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = list) {
                items(rows, key = { it.path }) { row ->
                    TreeRow(
                        row = row,
                        open = row.path !in collapsed,
                        selected = row.path == selected,
                        editing = editing?.takeIf { it.path == row.path },
                        colors = colors,
                        draft = draft,
                        onSelect = {
                            draft.flushNow()
                            selected = row.path
                        },
                        onToggle = { collapsed = if (row.path in collapsed) collapsed - setOf(row.path) else collapsed + setOf(row.path) },
                        onEditKey = { startEdit(row.path, key = true) },
                        onEditValue = { startEdit(row.path, key = false) },
                        onEditDone = {
                            editing = null
                            // Back to the tree, so the popup's keys (apply, collapse) keep working.
                            runCatching { treeFocus.requestFocus() }
                        },
                        onRename = { name ->
                            val renamed = root.withKeyRenamed(row.path, name)
                            if (renamed == null) "\"$name\" is already a key here"
                            else {
                                val newPath = row.path.dropLast(1) + JsonStep.Key(name)
                                collapsed = collapsed.mapTo(mutableSetOf()) { p -> if (p.take(row.path.size) == row.path) newPath + p.drop(row.path.size) else p }
                                change(renamed, newPath)
                                null
                            }
                        },
                        onValue = { value -> change(root.withValue(row.path, value)) },
                        onType = { type ->
                            draft.flushNow()
                            editing = null
                            change(root.withValue(row.path, converted(row.element, type)))
                        },
                        onAdd = { addTo(row.path) },
                        onRemove = {
                            draft.flushNow()
                            editing = null
                            change(root.without(row.path), row.path.dropLast(1).ifEmpty { null })
                        },
                        onProblem = { problem = it },
                    )
                }
            }
            VerticalScrollbar(list, Modifier.align(Alignment.TopEnd).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    row: JsonTreeRow,
    open: Boolean,
    selected: Boolean,
    editing: TreeEdit?,
    colors: GridColors,
    draft: JsonTreeDraft,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onEditKey: () -> Unit,
    onEditValue: () -> Unit,
    onEditDone: () -> Unit,
    onRename: (String) -> String?,
    onValue: (JsonElement) -> Unit,
    onType: (JsonValueType) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onProblem: (String?) -> Unit,
) {
    val palette = jsonPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val element = row.element
    val type = JsonValueType.of(element)
    val step = row.path.last()
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 22.dp)
            .background(if (selected) colors.selection else if (hovered) colors.hover else Color.Transparent)
            .hoverable(interaction)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onSelect, onDoubleClick = { if (row.isContainer) onToggle() })
            .padding(start = (8 + (row.depth - 1) * 16).dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).pointerHoverIcon(PointerIcon.Hand).combinedClickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            if (row.isContainer) Icon(if (open) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight, null, tint = colors.muted)
        }
        when {
            editing?.key == true && step is JsonStep.Key -> {
                InlineField(step.name, draft, onCommit = onRename, onDone = onEditDone, onProblem = onProblem)
                Text(":", style = JewelTheme.editorTextStyle, color = colors.muted)
            }
            step is JsonStep.Key ->
                Text(
                    step.name + ":",
                    Modifier.combinedClickable(onClick = onSelect, onDoubleClick = onEditKey),
                    style = JewelTheme.editorTextStyle,
                    color = palette.key.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            step is JsonStep.Index -> Text("${step.index}:", style = JewelTheme.editorTextStyle, color = colors.muted)
        }
        Box(Modifier.weight(1f)) {
            when {
                editing?.key == false && (type == JsonValueType.String || type == JsonValueType.Number) ->
                    InlineField(
                        (element as JsonPrimitive).content,
                        draft,
                        multiLine = type == JsonValueType.String,
                        onCommit = { input ->
                            val value = leafValue(type, input)
                            if (value == null) "Not a JSON number: use digits, an optional . and exponent"
                            else {
                                onValue(value)
                                null
                            }
                        },
                        onDone = onEditDone,
                        onProblem = onProblem,
                    )
                element is JsonObject || element is JsonArray -> {
                    val size = if (element is JsonObject) "{${element.size}}" else "[${(element as JsonArray).size}]"
                    Text(size, Modifier.combinedClickable(onClick = onSelect, onDoubleClick = onToggle), style = JewelTheme.editorTextStyle, color = colors.muted)
                }
                type == JsonValueType.Boolean -> {
                    val value = (element as JsonPrimitive).content == "true"
                    Tooltip({ Text("Click to switch") }) {
                        Text(
                            value.toString(),
                            Modifier.pointerHoverIcon(PointerIcon.Hand).combinedClickable(onClick = { onSelect(); onValue(JsonPrimitive(!value)) }),
                            style = JewelTheme.editorTextStyle,
                            color = palette.literal.color,
                        )
                    }
                }
                element is JsonNull -> Text("null", style = JewelTheme.editorTextStyle, color = palette.literal.color)
                else -> {
                    val primitive = element as JsonPrimitive
                    val shown = if (primitive.isString) "\"" + oneLine(primitive.content) + "\"" else primitive.content
                    Text(
                        shown,
                        Modifier.pointerHoverIcon(PointerIcon.Text).combinedClickable(onClick = onEditValue),
                        style = JewelTheme.editorTextStyle,
                        color = if (primitive.isString) palette.string.color else palette.number.color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (selected || hovered) {
            TypeMenu(type, onType)
            if (row.isContainer) TreeIconButton(AllIconsKeys.General.Add, if (element is JsonArray) "Add item" else "Add key", onClick = onAdd)
            TreeIconButton(AllIconsKeys.General.Delete, "Remove", onClick = onRemove)
        } else {
            Text(type.label.lowercase(), color = colors.muted, fontSize = 11.sp)
        }
    }
}

/** Shows a string's line breaks as ⏎ so every row stays one line; the edit field shows them as they are. */
private fun oneLine(value: String, maxChars: Int = 300): String =
    (if (value.length > maxChars) value.take(maxChars) + "…" else value).replace("\r\n", "⏎").replace('\n', '⏎')

/**
 * A key or value being typed. Enter confirms (Shift+Enter breaks the line in a string), Escape drops it; clicking away
 * confirms it when it's valid. [onCommit] returns why the text can't be used, or null once it's applied.
 */
@Composable
private fun InlineField(initial: String, draft: JsonTreeDraft, onCommit: (String) -> String?, onDone: () -> Unit, onProblem: (String?) -> Unit, multiLine: Boolean = false) {
    val text = remember { TextFieldState(initial).apply { edit { selectAll() } } }
    val focus = remember { FocusRequester() }
    var invalid by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val textColor = JewelTheme.globalColors.text.normal

    fun commit(): Boolean {
        if (finished) return true
        val typed = text.text.toString()
        if (typed == initial) {
            finished = true
            onProblem(null)
            onDone()
            return true
        }
        val problem = onCommit(typed)
        onProblem(problem)
        invalid = problem != null
        if (problem == null) {
            finished = true
            onDone()
        }
        return problem == null
    }

    fun cancel() {
        finished = true
        onProblem(null)
        onDone()
    }

    DisposableEffect(Unit) {
        val flush = { if (!commit()) cancel() }
        val confirm = { commit() }
        draft.flush = flush
        draft.confirm = confirm
        onDispose {
            if (draft.flush === flush) draft.flush = null
            if (draft.confirm === confirm) draft.confirm = null
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(3.dp)
    var hadFocus by remember { mutableStateOf(false) }
    BasicTextField(
        state = text,
        modifier =
            Modifier.widthIn(min = 80.dp)
                .clip(shape)
                .border(1.dp, if (invalid) ErrorColor else JewelTheme.globalColors.borders.focused, shape)
                .background(JewelTheme.globalColors.panelBackground)
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused && !finished && !commit()) cancel()
                    hadFocus = it.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !(multiLine && event.isShiftPressed) -> commit()
                        event.key == Key.Escape -> cancel()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        textStyle = JewelTheme.editorTextStyle.copy(color = textColor),
        cursorBrush = SolidColor(textColor),
        lineLimits = if (multiLine) TextFieldLineLimits.MultiLine(maxHeightInLines = 6) else TextFieldLineLimits.SingleLine,
    )
}

@Composable
private fun TypeMenu(type: JsonValueType, onType: (JsonValueType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(3.dp)).pointerHoverIcon(PointerIcon.Hand).combinedClickable(onClick = { open = !open }).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(type.label.lowercase(), color = JewelTheme.globalColors.text.info, fontSize = 11.sp)
            Icon(AllIconsKeys.General.ChevronDown, null, Modifier.size(10.dp))
        }
        if (open) {
            PopupMenu(onDismissRequest = { open = false; true }, horizontalAlignment = Alignment.End) {
                JsonValueType.entries.forEach { option ->
                    if (option == JsonValueType.Object) separator()
                    selectableItem(selected = option == type, onClick = {
                        open = false
                        if (option != type) onType(option)
                    }) { Text(option.label) }
                }
            }
        }
    }
}

@Composable
private fun TreeIconButton(icon: IconKey, tooltip: String, onClick: () -> Unit) {
    Tooltip({ Text(tooltip) }) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).pointerHoverIcon(PointerIcon.Hand).combinedClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, tooltip, Modifier.size(14.dp))
        }
    }
}
