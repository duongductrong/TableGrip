package dev.tablegrip.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.app.ui.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Two spaces: a record is read in a dialog, where width is short. */
@OptIn(ExperimentalSerializationApi::class)
private val RecordPrettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private const val Indent = "  "

/** Highlighting is skipped past this, so a record holding a whole HTML page still types smoothly. */
private const val MaxHighlightChars = 200_000

internal fun prettyRecord(json: JsonElement): String = RecordPrettyJson.encodeToString(JsonElement.serializer(), exactNumbers(json))

/**
 * Shows the JSON record editor while [state] has a record open. [subject] names what the records belong to (a table, a
 * collection). Render it beside the grid and the JSON view, so it stays open when the user switches between them.
 */
@Composable
fun RecordEditorHost(model: GridModel, state: DataGridState, subject: String) {
    val request = state.recordEdit ?: return
    RecordEditorDialog(
        request = request,
        current = model,
        subject = subject,
        onClose = { state.recordEdit = null },
        onApplied = { row, isNew ->
            state.recordEdit = null
            state.revealEditable(model, row)
            val noun = model.records?.recordNoun ?: "row"
            state.notice = GridNotice(if (isNew) "Added a $noun from JSON; submit to save it" else "Updated the $noun from JSON; submit to save it")
        },
    )
}

@Composable
private fun RecordEditorDialog(request: RecordEditRequest, current: GridModel, subject: String, onClose: () -> Unit, onApplied: (row: Int, isNew: Boolean) -> Unit) {
    val noun = (request.model.records?.recordNoun ?: "row").replaceFirstChar(Char::uppercase)
    AppDialog(
        onCloseRequest = onClose,
        title = if (request.row == null) "New $noun" else "Edit $noun",
        size = DpSize(720.dp, 640.dp),
        // The pane decides: a first Esc with unapplied edits only warns.
        closeOnEscape = false,
    ) {
        RecordEditorPane(request, current, subject, onClose, onApplied)
    }
}

/** The record editor's content: the JSON, what applying it will do, and the buttons. */
@Composable
internal fun RecordEditorPane(request: RecordEditRequest, current: GridModel, subject: String, onClose: () -> Unit, onApplied: (row: Int, isNew: Boolean) -> Unit) {
    val records = request.model.records
    val noun = records?.recordNoun ?: "row"
    val nounTitle = noun.replaceFirstChar(Char::uppercase)
    val isNew = request.row == null
    // The page was reloaded (or the connection went read-only) under the open record: its row means nothing any more.
    val stale = current !== request.model || records == null
    val initial = remember(request) { if (request.base.isEmpty()) EmptyRecord else prettyRecord(request.base) + "\n" }
    var value by remember(request) { mutableStateOf(TextFieldValue(initial, TextRange(initialCaret(initial, isNew)))) }
    val text = value.text
    val dirty = text != initial
    var escapeArmed by remember(request) { mutableStateOf(false) }
    LaunchedEffect(text) { escapeArmed = false }

    // Parsed off the UI thread, a beat after typing stops; the plan reads the model, so it's made here.
    var parsed by remember(request) { mutableStateOf<ParsedRecord?>(null) }
    LaunchedEffect(request, text) {
        if (parsed != null) delay(150)
        parsed = withContext(Dispatchers.Default) { parseRecord(text, noun) }
    }
    val parsedNow = parsed
    val plan = (parsedNow as? ParsedRecord.Ok)?.let { ok -> if (stale) null else records.plan(request.row, request.base, ok.json) }
    val error = parsedNow as? ParsedRecord.Invalid
    val editorFocus = remember { FocusRequester() }

    fun apply() {
        if (stale) return
        // Parse again: the shown plan may be a beat behind the last key.
        val json = (parseRecord(text, noun) as? ParsedRecord.Ok)?.json ?: return
        if (records.plan(request.row, request.base, json).canApply) records.apply(request.row, request.base, json)?.let { onApplied(it, isNew) }
    }

    fun requestClose() {
        if (dirty && !escapeArmed && !stale) escapeArmed = true else onClose()
    }

    fun goTo(offset: Int) {
        value = value.copy(selection = TextRange(offset.coerceIn(0, text.length)))
        runCatching { editorFocus.requestFocus() }
    }

    val info = JewelTheme.globalColors.text.info
    Column(
        Modifier.fillMaxSize().padding(12.dp).onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                Shortcuts.Submit.matches(event) -> true.also { apply() }
                event.key == Key.Escape && !event.isMetaPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed -> true.also { requestClose() }
                else -> false
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(subject, color = info)
            Text("·", color = info)
            Text(request.row?.let { row -> records?.takeIf { !stale && row < current.rowCount }?.describeRecord(row) } ?: "New $noun", maxLines = 1)
        }
        request.hint?.let { Text(it, color = info, maxLines = 3) }

        JsonCodeEditor(
            value = value,
            onValueChange = { value = it },
            errorLine = error?.line,
            focus = editorFocus,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Status(stale, noun, error, plan, onGoTo = ::goTo, onCopy = { copyToClipboard(text) })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tooltip({ Text("Indent the JSON neatly") }) {
                OutlinedButton(
                    onClick = { (parseRecord(text, noun) as? ParsedRecord.Ok)?.let { value = TextFieldValue(prettyRecord(it.json) + "\n", TextRange(0)) } },
                    enabled = parsedNow is ParsedRecord.Ok,
                ) { Text("Format") }
            }
            Tooltip({ Text("Back to the $noun as it was opened") }) {
                OutlinedButton(onClick = { value = TextFieldValue(initial, TextRange(initialCaret(initial, isNew))) }, enabled = dirty) { Text("Reset") }
            }
            Spacer(Modifier.weight(1f))
            if (escapeArmed) Text("Press Esc again to discard your edits", color = info)
            OutlinedButton(onClick = onClose) { Text("Cancel") }
            Tooltip({ Text("Stage the changes (${Shortcuts.Submit.label}); Submit in the toolbar saves them") }) {
                DefaultButton(onClick = ::apply, enabled = plan?.canApply == true) { Text(if (isNew) "Add $nounTitle" else "Apply") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { editorFocus.requestFocus() } }
}

/** An empty record, opened with a line ready to type on. */
private const val EmptyRecord = "{\n$Indent\n}\n"

/** A new record opens with the caret where its first field goes, ready to type; an existing one at the top. */
private fun initialCaret(text: String, isNew: Boolean): Int =
    when {
        !isNew -> 0
        text == EmptyRecord -> 2 + Indent.length
        else -> text.indexOf('{').let { if (it < 0) 0 else it + 1 }
    }

/** Under the editor: why the JSON can't be applied, or what applying it will change. */
@Composable
private fun Status(stale: Boolean, noun: String, error: ParsedRecord.Invalid?, plan: RecordPlan?, onGoTo: (Int) -> Unit, onCopy: () -> Unit) {
    val info = JewelTheme.globalColors.text.info
    val errorColor = JewelTheme.globalColors.text.error
    Column(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        when {
            stale ->
                StatusLine(AllIconsKeys.General.Warning) {
                    Text("The page reloaded while this $noun was open, so it can't be applied here. ", color = errorColor)
                    Link("Copy the JSON", onClick = onCopy)
                }
            error != null ->
                StatusLine(AllIconsKeys.General.Error) {
                    Text(error.text, color = errorColor, maxLines = 2)
                    error.offset?.let { offset ->
                        Spacer(Modifier.size(8.dp))
                        Link("Show", onClick = { onGoTo(offset) })
                    }
                }
            plan == null -> Text("Checking…", color = info)
            plan.problems.isNotEmpty() -> {
                plan.problems.take(4).forEach { problem -> StatusLine(AllIconsKeys.General.Error) { Text(problem, color = errorColor, maxLines = 2) } }
                if (plan.problems.size > 4) Text("…and ${plan.problems.size - 4} more", color = errorColor)
            }
            plan.changes.isEmpty() && !plan.isNew -> Text("No changes yet. Edit the values above; only the fields you change are staged.", color = info)
            else -> StatusLine(AllIconsKeys.General.InspectionsOK) { Text(changeSummary(plan, noun), maxLines = 2) }
        }
        if (!stale && error == null) plan?.notes?.forEach { Text(it, color = info, maxLines = 2) }
    }
}

@Composable
private fun StatusLine(icon: IconKey, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** "Changes name, email · adds tags · removes legacy", or for a new record what it sets. */
internal fun changeSummary(plan: RecordPlan, noun: String): String {
    if (plan.isNew) {
        val names = plan.changes.map { it.name }
        if (names.isEmpty()) return if (noun == "document") "Adds an empty document; MongoDB gives it an _id" else "Adds a $noun with every column at its default"
        return "Adds a $noun with ${names.size} field(s): ${names.joinToString(", ", limit = 8)}"
    }
    val byKind = plan.changes.groupBy { it.kind }
    return listOfNotNull(
        byKind[FieldChange.Kind.Changed]?.let { "Changes " + it.joinToString(", ", limit = 8) { c -> c.name } },
        byKind[FieldChange.Kind.Added]?.let { "adds " + it.joinToString(", ", limit = 8) { c -> c.name } },
        byKind[FieldChange.Kind.Removed]?.let { "removes " + it.joinToString(", ", limit = 8) { c -> c.name } },
    ).joinToString(" · ").replaceFirstChar(Char::uppercase)
}

/**
 * A small JSON editor: line numbers, syntax colours, and the indentation people expect: Enter keeps the line's indent
 * (one more after `{` or `[`), Tab indents, Shift+Tab outdents. Lines don't wrap, so numbers stay beside their lines.
 */
@Composable
internal fun JsonCodeEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, errorLine: Int?, focus: FocusRequester, modifier: Modifier = Modifier) {
    val style = JewelTheme.editorTextStyle
    val textColor = JewelTheme.globalColors.text.normal
    val info = JewelTheme.globalColors.text.info
    val errorColor = JewelTheme.globalColors.text.error
    val palette = jsonPalette()
    val highlighter = remember(palette) { JsonHighlighter(palette) }
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val numbers =
        remember(lineCount, errorLine, info, errorColor) {
            buildAnnotatedString {
                for (line in 1..lineCount) {
                    if (line > 1) append('\n')
                    if (line == errorLine) withStyle(SpanStyle(color = errorColor, fontWeight = FontWeight.Bold)) { append(line.toString()) }
                    else withStyle(SpanStyle(color = info)) { append(line.toString()) }
                }
            }
        }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(
        modifier.clip(shape).border(1.dp, JewelTheme.globalColors.borders.normal, shape).background(JewelTheme.globalColors.panelBackground)
    ) {
        val viewport = maxWidth
        Row(Modifier.fillMaxSize().verticalScroll(vertical)) {
            Text(numbers, Modifier.padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), style = style, softWrap = false)
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontal)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier.widthIn(min = viewport - 48.dp)
                            .padding(end = 12.dp, top = 6.dp, bottom = 6.dp)
                            .focusRequester(focus)
                            .onPreviewKeyEvent { event -> indentKey(event, value)?.let { onValueChange(it); true } ?: false },
                    textStyle = style.copy(color = textColor),
                    cursorBrush = SolidColor(textColor),
                    visualTransformation = highlighter,
                )
            }
        }
    }
}

/** Colours JSON as it's typed; positions don't move, so the mapping is the identity. */
private class JsonHighlighter(private val palette: JsonPalette) : VisualTransformation {
    private var lastText: String? = null
    private var lastResult: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled =
            if (raw == lastText) lastResult!!
            else if (raw.length > MaxHighlightChars) AnnotatedString(raw)
            else highlightAll(raw).also {
                lastText = raw
                lastResult = it
            }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    private fun highlightAll(raw: String): AnnotatedString = buildAnnotatedString {
        append(raw)
        var start = 0
        while (start <= raw.length) {
            val end = raw.indexOf('\n', start).let { if (it < 0) raw.length else it }
            jsonTokens(raw.substring(start, end)).forEach { addStyle(palette.style(it.type), start + it.start, start + it.end) }
            start = end + 1
        }
    }
}

/** What Enter, Tab and Shift+Tab do to [value]; null to let the field handle the key. */
internal fun indentKey(event: KeyEvent, value: TextFieldValue): TextFieldValue? {
    if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed || event.isAltPressed) return null
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> if (event.isShiftPressed) null else newLine(value)
        Key.Tab -> if (event.isShiftPressed) outdent(value) else indent(value)
        else -> null
    }
}

/** A line break that keeps the indent; between `{` and `}` it opens an indented line and puts the closer below it. */
internal fun newLine(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = value.selection.min
    val end = value.selection.max
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val indent = text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
    val before = text.substring(lineStart, start).trimEnd().lastOrNull()
    val after = text.substring(end).trimStart(' ', '\t').firstOrNull()
    val opens = before == '{' || before == '['
    val closes = (before == '{' && after == '}') || (before == '[' && after == ']')
    val insert =
        when {
            closes -> "\n$indent$Indent\n$indent"
            opens -> "\n$indent$Indent"
            else -> "\n$indent"
        }
    val caret = start + if (closes) 1 + indent.length + Indent.length else insert.length
    // Spaces before the caret's closer are dropped, so it lines up with the opening line.
    val rest = if (closes) text.substring(end).trimStart(' ', '\t') else text.substring(end)
    return TextFieldValue(text.substring(0, start) + insert + rest, TextRange(caret))
}

/** Tab: two spaces at the caret, or one more indent on every selected line. */
internal fun indent(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        return TextFieldValue(text.substring(0, selection.start) + Indent + text.substring(selection.start), TextRange(selection.start + Indent.length))
    }
    val lines = selectedLines(text, selection)
    val updated = StringBuilder(text)
    lines.reversed().forEach { updated.insert(it, Indent) }
    return TextFieldValue(updated.toString(), TextRange(selection.min + Indent.length, selection.max + Indent.length * lines.size))
}

/** Shift+Tab: one indent less on every line the selection touches. */
internal fun outdent(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val updated = StringBuilder(text)
    var removedBeforeStart = 0
    var removed = 0
    selectedLines(text, selection).reversed().forEach { lineStart ->
        val count = text.substring(lineStart).takeWhile { it == ' ' }.length.coerceAtMost(Indent.length)
        if (count > 0) {
            updated.delete(lineStart, lineStart + count)
            removed += count
            if (lineStart < selection.min) removedBeforeStart = minOf(count, selection.min - lineStart)
        }
    }
    val start = (selection.min - removedBeforeStart).coerceAtLeast(0)
    return TextFieldValue(updated.toString(), if (selection.collapsed) TextRange(start) else TextRange(start, (selection.max - removed).coerceAtLeast(start)))
}

/** The start offsets of the lines [selection] touches. */
private fun selectedLines(text: String, selection: TextRange): List<Int> {
    val first = text.lastIndexOf('\n', selection.min - 1) + 1
    // A selection ending at a line's very start doesn't take that line.
    val lastPosition = if (!selection.collapsed && selection.max > 0 && text.getOrNull(selection.max - 1) == '\n') selection.max - 1 else selection.max
    return buildList {
        add(first)
        var i = text.indexOf('\n', first)
        while (i in 0 until lastPosition) {
            add(i + 1)
            i = text.indexOf('\n', i + 1)
        }
    }
}
