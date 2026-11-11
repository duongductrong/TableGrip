package dev.tablegrip.app.console

import dev.tablegrip.core.i18n.tr
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.completion.CompletionController
import dev.tablegrip.app.completion.CompletionPopup
import dev.tablegrip.app.completion.CompletionSource
import dev.tablegrip.app.completion.handleCompletionKey
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.LocalSettings
import dev.tablegrip.app.ui.theme.ColorTheme
import dev.tablegrip.app.ui.theme.LocalColorTheme
import dev.tablegrip.app.ui.Shortcuts
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.sql.KeywordCase
import dev.tablegrip.core.sql.SqlFormatter
import dev.tablegrip.core.sql.SqlLexer
import dev.tablegrip.core.sql.SqlSplitter
import dev.tablegrip.core.sql.SqlSyntax
import dev.tablegrip.core.sql.TokenType
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

// Gutter: [start pad][line number][gap][statement bar] — then the text with its own start pad.
private val GutterPaddingStart = 12.dp
private val GutterNumberGap = 8.dp
private val StatementBarWidth = 2.dp
private val TextPaddingStart = 12.dp
private val TextPaddingTop = 8.dp
private val TextPaddingEnd = 24.dp
/** Empty lines kept below the last one, so the end of a long script doesn't sit flush against the edge. */
private const val OverscrollLines = 3
private val CaretWidth = 2.dp
/** Caret blink half-period, like IntelliJ's default; the caret stays solid for this long after every move or edit. */
private const val CaretBlinkMillis = 500

/** What the editor exposes to its host's toolbar. */
class SqlEditorActions {
    var format: () -> Unit = {}
    var openFind: () -> Unit = {}
}

/**
 * A plain-Compose SQL editor with IntelliJ-style behaviour: line numbers with the current statement marked, current
 * line and matching-bracket highlights, syntax colours, auto-pairing, comment/duplicate/move/delete line actions,
 * find and replace, formatting and context-aware completion.
 */
@Composable
internal fun SqlEditor(
    state: TextFieldState,
    syntax: SqlSyntax,
    fontSize: Int,
    modifier: Modifier = Modifier,
    completion: CompletionSource? = null,
    completionVersion: Int = 0,
    actions: SqlEditorActions? = null,
    onShortcut: (KeyEvent) -> Boolean = { false },
    /** Selection, copy and find still work; nothing edits the text. */
    readOnly: Boolean = false,
) {
    val isDark = JewelTheme.isDark
    val settings = LocalSettings.current
    val accent = LocalIslands.current.accent
    val colorTheme = LocalColorTheme.current
    val palette = remember(colorTheme, settings.syntaxScheme, settings.syntaxColors, accent) { sqlPalette(colorTheme, settings, accent) }
    val highlighter = remember(syntax, palette) { SqlHighlighter(syntax, palette) }
    val autoPair = remember(syntax) { AutoPair(syntax) }
    val splitter = remember(syntax) { SqlSplitter(syntax) }
    val indentUnit = if (settings.indentWithTabs) "\t" else " ".repeat(settings.tabSize.coerceIn(1, 16))
    val formatter = remember(syntax, indentUnit, settings.formatKeywordCase) { SqlFormatter(syntax, indentUnit, KeywordCase.of(settings.formatKeywordCase)) }
    val highlightLine = settings.highlightCurrentLine
    val scheme = SyntaxScheme.of(settings.syntaxScheme)
    val editorBackground = scheme.background(isDark) ?: colorTheme.editor.background ?: JewelTheme.globalColors.panelBackground
    val editorText = scheme.foreground(isDark) ?: colorTheme.editor.foreground ?: JewelTheme.globalColors.text.normal
    // No trimming: every line — first and last included — is exactly one pitch tall, so line N always sits at N × pitch.
    val textStyle =
        JewelTheme.editorTextStyle.copy(
            fontSize = fontSize.sp,
            lineHeight = (fontSize * settings.editorLineHeight.coerceIn(100, 300) / 100f).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            color = editorText,
        )
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Line pitch as the text engine really lays it out (fractional px), measured once per style rather than read from the
    // field's latest layout, which lags the text by a frame after every edit.
    val linePitch = remember(textStyle, density) { measurer.measure("0\n0", textStyle).let { it.getLineTop(1) - it.getLineTop(0) } }
    val lineHeight = with(density) { linePitch.toDp() }
    // The caret spans the glyphs (ascent + descent), not the whole line pitch, so a roomy line height doesn't stretch it.
    val caretHeight = remember(textStyle, density) { measurer.measure("Ag", textStyle.copy(lineHeight = TextUnit.Unspecified)).size.height.toFloat().coerceAtMost(linePitch) }
    var focused by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val controller = remember { CompletionController(scope) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var triggerCompletion by remember { mutableStateOf(false) }
    val find = remember { FindState() }

    fun apply(edit: EditorText) = state.applyMinimal(edit)
    fun current() = EditorText(state.text.toString(), state.selection)

    fun format() {
        val sel = state.selection
        val text = state.text.toString()
        if (sel.collapsed) apply(EditorText(formatter.format(text) + if (text.endsWith("\n")) "\n" else "", 0))
        else {
            val formatted = formatter.format(text.substring(sel.min, sel.max))
            apply(EditorText(text.replaceRange(sel.min, sel.max, formatted), TextRange(sel.min, sel.min + formatted.length)))
        }
    }
    actions?.format = ::format
    actions?.openFind = { find.open(state, replace = false) }

    // Recompute suggestions as the text or caret changes while the popup is open (or typing asked for it), and when
    // schema metadata arrives.
    LaunchedEffect(state, completion) {
        snapshotFlow { state.text.toString() to state.selection }.collect {
            val source = completion ?: return@collect
            if (triggerCompletion || controller.isActive) controller.update(state, source, explicit = false)
            triggerCompletion = false
        }
    }
    LaunchedEffect(completionVersion) { if (controller.isActive && completion != null) controller.update(state, completion, explicit = false) }
    fun accept(index: Int = controller.selected) {
        if (controller.accept(state, index)) triggerCompletion = true
    }

    // Keep the current find match on screen.
    LaunchedEffect(find.current, layout) {
        val match = find.matches.getOrNull(find.current) ?: return@LaunchedEffect
        val box = layout?.getBoundingBox(match.first.coerceAtMost((layout?.layoutInput?.text?.length ?: 1) - 1)) ?: return@LaunchedEffect
        val top = box.top.toInt()
        if (top < verticalScroll.value || top > verticalScroll.value + verticalScroll.viewportSize - 60) verticalScroll.animateScrollTo((top - 80).coerceAtLeast(0))
    }

    Column(modifier.background(editorBackground)) {
        if (find.visible) {
            FindBar(find, state, onClose = { find.visible = false; editorFocus.requestFocus() })
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            val viewportWidth = maxWidth
            val text = state.text.toString()
            val caret = state.selection.start
            val statement = remember(text, caret) { if (text.isBlank()) null else splitter.statementAt(text, caret) }
            val brackets = remember(text, caret) { if (state.selection.collapsed) EditorActions.matchingBracket(text, caret, syntax) else null }
            val lineCount = text.count { it == '\n' } + 1
            val caretLine = lineIndexAt(text, caret)
            // The gutter grows with the digit count (min. two), so short scripts don't waste width.
            val digits = if (settings.showLineNumbers) maxOf(2, lineCount.toString().length) else 0
            val digitWidth = remember(textStyle, digits) { if (digits == 0) 0.dp else with(density) { measurer.measure("0".repeat(digits), textStyle).size.width.toDp() } }
            val gutterWidth = GutterPaddingStart + digitWidth + GutterNumberGap + StatementBarWidth
            // From the text itself (no soft wrap), so the bar never blinks out while the layout catches up.
            val statementLines = if (statement != null) lineIndexAt(text, statement.start)..lineIndexAt(text, statement.end) else IntRange.EMPTY

            Row(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
                LineNumbers(
                    lineCount = lineCount,
                    linePitch = linePitch,
                    gutterWidth = gutterWidth,
                    digitWidth = digitWidth,
                    lineHeight = lineHeight,
                    textStyle = textStyle,
                    caretLine = caretLine,
                    highlightCaretLine = highlightLine && state.selection.collapsed,
                    showNumbers = settings.showLineNumbers,
                    statementLines = statementLines,
                    palette = palette,
                )
                Box(Modifier.horizontalScroll(horizontalScroll)) {
                    BasicTextField(
                        state = state,
                        readOnly = readOnly,
                        inputTransformation = autoPair.takeIf { settings.autoCloseBrackets },
                        modifier =
                            Modifier.defaultMinSize(minWidth = viewportWidth - gutterWidth, minHeight = viewportHeight)
                                .focusRequester(editorFocus)
                                .onFocusChanged { focused = it.isFocused }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    if (handleCompletionKey(controller, event.key) { accept() }) return@onPreviewKeyEvent true
                                    if (onShortcut(event)) return@onPreviewKeyEvent true
                                    if (readOnly) return@onPreviewKeyEvent handleReadOnlyKey(event, state, find)
                                    // Before the edit lands, so the change it makes is completed (also for an auto-paired quote).
                                    if (settings.completionWhileTyping && isCompletionTrigger(event)) triggerCompletion = true
                                    handleEditorKey(event, state, syntax, ::current, ::apply, ::format, find, controller, completion, indentUnit)
                                },
                        textStyle = textStyle,
                        // The field's own caret is as tall as the line pitch; [Caret] draws one sized to the text instead.
                        cursorBrush = SolidColor(Color.Transparent),
                        outputTransformation = highlighter,
                        // Padding lives inside the decorator so the whole area — margins included — takes clicks, and the
                        // inner field stretches to the full width so line highlights run edge to edge.
                        decorator = { innerTextField ->
                            Box(
                                Modifier.padding(start = TextPaddingStart, top = TextPaddingTop, end = TextPaddingEnd, bottom = lineHeight * OverscrollLines)
                                    .drawBehind {
                                        // Current line, from the gutter edge to the far right — same geometry as the gutter's band.
                                        if (highlightLine && state.selection.collapsed) {
                                            val left = -TextPaddingStart.toPx()
                                            drawRect(palette.currentLine, Offset(left, caretLine * linePitch), Size(size.width - left + TextPaddingEnd.toPx(), linePitch))
                                        }
                                        // Matches and brackets need character positions; skip the frame where the layout is stale.
                                        val result = layout ?: return@drawBehind
                                        if (result.layoutInput.text.length != state.text.length) return@drawBehind
                                        // Find matches.
                                        find.matches.forEachIndexed { index, range ->
                                            if (range.last >= result.layoutInput.text.length) return@forEachIndexed
                                            val first = result.getBoundingBox(range.first)
                                            val last = result.getBoundingBox(range.last)
                                            drawRoundRect(
                                                if (index == find.current) palette.findCurrent else palette.findMatch,
                                                Offset(first.left, first.top),
                                                Size(last.right - first.left, first.height),
                                                CornerRadius(2f, 2f),
                                            )
                                        }
                                        // Matching brackets.
                                        brackets?.let { (open, close) ->
                                            for (offset in listOf(open, close)) {
                                                if (offset >= result.layoutInput.text.length) continue
                                                val box = result.getBoundingBox(offset)
                                                drawRect(palette.bracket, Offset(box.left, box.top), Size(box.width, box.height))
                                                drawRect(palette.bracketBorder, Offset(box.left, box.top), Size(box.width, box.height), style = Stroke(1f))
                                            }
                                        }
                                    },
                                propagateMinConstraints = true,
                            ) {
                                innerTextField()
                            }
                        },
                        onTextLayout = { getResult -> layout = getResult() },
                    )
                    if (focused && !readOnly) {
                        val caretOffset = state.selection.end.coerceIn(0, text.length)
                        // Restarts the blink on every edit or caret move, so the caret stays solid while typing.
                        key(text, state.selection) {
                            Caret(
                                state = state,
                                layout = { layout },
                                offset = caretOffset,
                                line = lineIndexAt(text, caretOffset),
                                linePitch = linePitch,
                                caretHeight = caretHeight,
                                textStyle = textStyle,
                                color = editorText,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                    val cursor = layout?.takeIf { it.layoutInput.text.length == state.text.length }?.getCursorRect(state.selection.start.coerceAtMost(state.text.length))
                    if (cursor != null) {
                        val offset =
                            with(density) { IntOffset((cursor.left + TextPaddingStart.toPx()).toInt(), (cursor.bottom + TextPaddingTop.toPx() + 2).toInt()) }
                        CompletionPopup(controller, offset) { index -> accept(index) }
                    }
                }
            }
        }
    }
}

/**
 * The editor's caret: glyph-tall and centred in its line (the same geometry the text uses with
 * [LineHeightStyle.Alignment.Center]), on the fixed line pitch so it lines up with the current-line band and the gutter.
 */
@Composable
private fun Caret(
    state: TextFieldState,
    layout: () -> TextLayoutResult?,
    offset: Int,
    line: Int,
    linePitch: Float,
    caretHeight: Float,
    textStyle: TextStyle,
    color: Color,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec =
            infiniteRepeatable(
                keyframes {
                    durationMillis = CaretBlinkMillis * 2
                    1f at 0
                    1f at CaretBlinkMillis - 1
                    0f at CaretBlinkMillis
                    0f at CaretBlinkMillis * 2 - 1
                },
                RepeatMode.Restart,
            ),
        label = "caret-blink",
    )
    Box(
        modifier.drawBehind {
            val alpha = blink
            if (alpha <= 0f) return@drawBehind
            val text = state.text
            // The field's layout when it matches the text; on the frame right after an edit, the line measured on its own.
            val x =
                layout()?.takeIf { it.layoutInput.text.length == text.length }?.getHorizontalPosition(offset, usePrimaryDirection = true)
                    ?: run {
                        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
                        measurer.measure(text.substring(lineStart, offset), textStyle).getHorizontalPosition(offset - lineStart, true)
                    }
            val width = CaretWidth.toPx()
            val left = (TextPaddingStart.toPx() + x - width / 2).roundToInt().toFloat()
            val top = (TextPaddingTop.toPx() + line * linePitch + (linePitch - caretHeight) / 2).roundToInt().toFloat()
            drawRect(color, Offset(left, top), Size(width, caretHeight.roundToInt().toFloat()), alpha = alpha)
        }
    )
}

/** Keys the editor handles itself; returns true when consumed. */
private fun handleReadOnlyKey(event: KeyEvent, state: TextFieldState, find: FindState): Boolean =
    when {
        Shortcuts.Find.matches(event) -> true.also { find.open(state, replace = false) }
        event.key == Key.Escape && find.visible -> true.also { find.visible = false }
        else -> false
    }

private fun handleEditorKey(
    event: KeyEvent,
    state: TextFieldState,
    syntax: SqlSyntax,
    current: () -> EditorText,
    apply: (EditorText) -> Unit,
    format: () -> Unit,
    find: FindState,
    controller: CompletionController,
    completion: CompletionSource?,
    indentUnit: String,
): Boolean {
    val primary = if (isMac) event.isMetaPressed else event.isCtrlPressed
    val shift = event.isShiftPressed
    val alt = event.isAltPressed
    fun done(block: () -> Unit) = true.also { block() }

    return when {
        // ⌃Space opens completion explicitly (also on macOS, like IntelliJ).
        Shortcuts.Completion.matches(event) -> done { completion?.let { controller.update(state, it, explicit = true) } }
        Shortcuts.Comment.matches(event) -> done { apply(EditorActions.toggleLineComment(current())) }
        Shortcuts.Duplicate.matches(event) -> done { apply(EditorActions.duplicate(current())) }
        Shortcuts.Find.matches(event) -> done { find.open(state, replace = false) }
        Shortcuts.Replace.matches(event) -> done { find.open(state, replace = true) }
        Shortcuts.Format.matches(event) -> done(format)
        Shortcuts.DeleteLine.matches(event) -> done { apply(EditorActions.deleteLines(current())) }
        Shortcuts.MoveLineUp.matches(event) -> done { apply(EditorActions.moveLines(current(), up = true)) }
        Shortcuts.MoveLineDown.matches(event) -> done { apply(EditorActions.moveLines(current(), up = false)) }
        Shortcuts.Indent.matches(event) || Shortcuts.Outdent.matches(event) -> {
            val outdent = Shortcuts.Outdent.matches(event)
            val text = current()
            val multiLine = !text.selection.collapsed && '\n' in text.text.substring(text.selection.min, text.selection.max)
            done {
                if (outdent || multiLine) apply(EditorActions.indent(text, outdent = outdent, unit = indentUnit))
                else state.edit { replaceSelectionWith(indentUnit) }
            }
        }
        primary || event.isCtrlPressed -> false
        event.key == Key.Escape && find.visible -> done { find.visible = false }
        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !shift && !alt -> done {
            val caret = state.selection.min
            val text = state.text
            val lineStart = text.lastIndexOf('\n', caret - 1) + 1
            val indent = text.subSequence(lineStart, caret).takeWhile { it == ' ' || it == '\t' }
            // Between brackets, open an indented line: (\n    |\n)
            val between = text.getOrNull(caret - 1) == '(' && text.getOrNull(caret) == ')'
            state.edit {
                if (between) {
                    replaceSelectionWith("\n$indent$indentUnit\n$indent")
                    selection = TextRange(caret + 1 + indent.length + indentUnit.length)
                } else {
                    replaceSelectionWith("\n$indent")
                }
            }
        }
        event.key == Key.Backspace && !alt -> EditorActions.backspace(current())?.let { apply(it); true } ?: false
        // Brackets and quotes are paired by [AutoPair], on the text that arrives rather than the key.
        else -> false
    }
}

/**
 * Pairs brackets and quotes, steps over closers and wraps selections as the typed character lands. Working on the edit
 * itself rather than the key press means a character committed by an input method (Telex, dead keys, …) is handled
 * once, instead of being paired on the key and then inserted again by the input method.
 */
private class AutoPair(private val syntax: SqlSyntax) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val inserted = changes.getRange(0)
        val replaced = changes.getOriginalRange(0)
        // Exactly one character typed over the selection (or at the caret), caret right after it.
        if (inserted.length != 1 || replaced.min != originalSelection.min || replaced.max != originalSelection.max) return
        if (!selection.collapsed || selection.start != inserted.end) return
        val char = asCharSequence()[inserted.start]
        if (char !in "()[]'\"`") return
        val edit = EditorActions.typeChar(EditorText(originalText.toString(), originalSelection), char, syntax) ?: return
        replaceMinimal(edit.text)
        selection = edit.selection
    }
}

/** Replaces the buffer's text with [new], touching only the part that differs. */
private fun TextFieldBuffer.replaceMinimal(new: String) {
    val old = asCharSequence()
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    if (prefix + suffix < old.length || prefix + suffix < new.length) replace(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
}

/**
 * Letters, digits, `_`, `.` and quotes open the completion popup as you type. Deleting doesn't: Backspace only keeps an
 * already open popup in step.
 */
private fun isCompletionTrigger(event: KeyEvent): Boolean {
    if (event.isMetaPressed || event.isCtrlPressed) return false
    val code = event.utf16CodePoint
    if (code <= 0) return false
    val char = code.toChar()
    return char.isLetterOrDigit() || char == '_' || char == '.' || char == '\'' || char == '$' || char == ' '
}

private fun TextFieldBuffer.replaceSelectionWith(text: String) {
    val start = selection.min
    replace(start, selection.max, text)
    selection = TextRange(start + text.length)
}

/** Applies an edit as the smallest replacement, so undo restores just the changed part. */
internal fun TextFieldState.applyMinimal(edit: EditorText) {
    val old = text.toString()
    val new = edit.text
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    this.edit {
        if (old != new) replace(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
        selection = TextRange(edit.selection.start.coerceIn(0, new.length), edit.selection.end.coerceIn(0, new.length))
    }
}

// region Find and replace

private class FindState {
    var visible by mutableStateOf(false)
    var showReplace by mutableStateOf(false)
    val query = TextFieldState()
    val replacement = TextFieldState()
    var matchCase by mutableStateOf(false)
    var matches by mutableStateOf<List<IntRange>>(emptyList())
    var current by mutableStateOf(0)
    val focus = FocusRequester()

    /** Opens the bar, seeding the query from a single-line selection. */
    fun open(state: TextFieldState, replace: Boolean) {
        visible = true
        showReplace = replace
        val sel = state.selection
        if (!sel.collapsed) {
            val selected = state.text.substring(sel.min, sel.max)
            if ('\n' !in selected) query.edit { replace(0, length, selected) }
        }
        focusRequested = true
    }

    var focusRequested by mutableStateOf(false)

    fun search(text: String, caret: Int) {
        matches = EditorActions.findAll(text, query.text.toString(), matchCase)
        current = matches.indexOfFirst { it.first >= caret }.takeIf { it >= 0 } ?: 0
    }

    fun step(delta: Int, state: TextFieldState) {
        if (matches.isEmpty()) return
        current = (current + delta).mod(matches.size)
        val match = matches[current]
        state.edit { selection = TextRange(match.first, match.last + 1) }
    }
}

@Composable
private fun FindBar(find: FindState, state: TextFieldState, onClose: () -> Unit) {
    LaunchedEffect(find.focusRequested) {
        if (find.focusRequested) {
            find.focus.requestFocus()
            find.focusRequested = false
        }
    }
    LaunchedEffect(find.query.text, find.matchCase, state.text) { find.search(state.text.toString(), state.selection.start) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                find.query,
                Modifier.width(260.dp).focusRequester(find.focus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) onClose().let { true }
                    else if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) find.step(if (it.isShiftPressed) -1 else 1, state).let { true }
                    else false
                },
                placeholder = { Text(tr("Find")) },
                leadingIcon = { Icon(AllIconsKeys.Actions.Search, null) },
            )
            val count = find.matches.size
            Text(if (find.query.text.isEmpty()) "" else if (count == 0) tr("0 results") else "${find.current + 1}/$count", Modifier.width(72.dp), color = JewelTheme.globalColors.text.info)
            Tooltip({ Text(tr("Previous (⇧Enter)")) }) { IconButton(onClick = { find.step(-1, state) }, enabled = count > 0) { Icon(AllIconsKeys.Actions.PreviousOccurence, tr("Previous")) } }
            Tooltip({ Text(tr("Next (Enter)")) }) { IconButton(onClick = { find.step(1, state) }, enabled = count > 0) { Icon(AllIconsKeys.Actions.NextOccurence, tr("Next")) } }
            Tooltip({ Text(if (find.matchCase) tr("Match case: on") else tr("Match case: off")) }) {
                IconButton(onClick = { find.matchCase = !find.matchCase }) { Text("Aa", color = if (find.matchCase) JewelTheme.globalColors.outlines.focused else JewelTheme.globalColors.text.info) }
            }
            Box(Modifier.weight(1f))
            Tooltip({ Text(tr("Close (Esc)")) }) { IconButton(onClick = onClose) { Icon(AllIconsKeys.Actions.Close, tr("Close")) } }
        }
        if (find.showReplace) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(find.replacement, Modifier.width(260.dp), placeholder = { Text(tr("Replace")) }, onKeyboardAction = KeyboardActionHandler { replaceCurrent(find, state) })
                OutlinedButton(onClick = { replaceCurrent(find, state) }, enabled = find.matches.isNotEmpty()) { Text(tr("Replace")) }
                OutlinedButton(onClick = { replaceAll(find, state) }, enabled = find.matches.isNotEmpty()) { Text(tr("Replace All")) }
            }
        }
    }
}

private fun replaceCurrent(find: FindState, state: TextFieldState) {
    val match = find.matches.getOrNull(find.current) ?: return
    val replacement = find.replacement.text.toString()
    state.edit {
        replace(match.first, match.last + 1, replacement)
        selection = TextRange(match.first + replacement.length)
    }
}

private fun replaceAll(find: FindState, state: TextFieldState) {
    val replacement = find.replacement.text.toString()
    state.edit {
        // From the end, so earlier offsets stay valid.
        find.matches.asReversed().forEach { replace(it.first, it.last + 1, replacement) }
    }
}

// endregion

/** Zero-based line of [offset] in [text]; lines only break at `\n` because the editor never soft-wraps. */
internal fun lineIndexAt(text: CharSequence, offset: Int): Int {
    var line = 0
    for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') line++
    return line
}

/**
 * Line numbers on the same fixed pitch as the text ([linePitch] per line, no trimming), computed from the text alone so
 * nothing shifts while the field's layout catches up with an edit.
 */
@Composable
private fun LineNumbers(
    lineCount: Int,
    linePitch: Float,
    gutterWidth: Dp,
    digitWidth: Dp,
    lineHeight: Dp,
    textStyle: TextStyle,
    caretLine: Int,
    highlightCaretLine: Boolean,
    showNumbers: Boolean,
    statementLines: IntRange,
    palette: SqlPalette,
) {
    val dimmed = textStyle.copy(color = JewelTheme.globalColors.text.info, textAlign = TextAlign.End)
    val bright = dimmed.copy(color = textStyle.color)
    Box(
        Modifier.width(gutterWidth)
            .height(lineHeight * lineCount + TextPaddingTop)
            .drawBehind {
                val inset = TextPaddingTop.toPx()
                if (highlightCaretLine) drawRect(palette.currentLine, Offset(0f, inset + caretLine * linePitch), Size(size.width, linePitch))
                // Marks the statement ⌘Enter would run.
                if (!statementLines.isEmpty()) {
                    val barWidth = StatementBarWidth.toPx()
                    drawRect(
                        palette.statementBar,
                        Offset(size.width - barWidth, inset + statementLines.first * linePitch),
                        Size(barWidth, (statementLines.last - statementLines.first + 1) * linePitch),
                    )
                }
            }
    ) {
        if (showNumbers) for (line in 0 until lineCount) {
            Box(
                Modifier.offset { IntOffset(0, (TextPaddingTop.toPx() + line * linePitch).roundToInt()) }
                    .padding(start = GutterPaddingStart)
                    .width(digitWidth)
                    .height(lineHeight),
                contentAlignment = Alignment.TopEnd,
            ) {
                Text((line + 1).toString(), style = if (line == caretLine) bright else dimmed)
            }
        }
    }
}

private data class SqlPalette(
    val keyword: SpanStyle,
    val string: SpanStyle,
    val number: SpanStyle,
    val comment: SpanStyle,
    val quotedIdentifier: SpanStyle,
    val parameter: SpanStyle,
    val currentLine: Color,
    val bracket: Color,
    val bracketBorder: Color,
    val findMatch: Color,
    val findCurrent: Color,
    val statementBar: Color,
)

/** The editor's colours from [theme], with the user's syntax scheme and token colours over its tokens. */
private fun sqlPalette(theme: ColorTheme, settings: Settings, accent: Color): SqlPalette {
    fun style(token: SyntaxToken) = theme.syntax.getValue(token).copy(color = settings.syntaxColor(token, theme))
    val e = theme.editor
    return SqlPalette(
        keyword = style(SyntaxToken.Keyword),
        string = style(SyntaxToken.String),
        number = style(SyntaxToken.Number),
        comment = style(SyntaxToken.Comment),
        quotedIdentifier = style(SyntaxToken.QuotedIdentifier),
        parameter = style(SyntaxToken.Parameter),
        currentLine = e.currentLine,
        bracket = e.bracket,
        bracketBorder = e.bracketBorder,
        findMatch = e.findMatch,
        findCurrent = e.findCurrent,
        statementBar = accent,
    )
}

/** Colours tokens on the displayed text only; the underlying state stays plain. */
private class SqlHighlighter(syntax: SqlSyntax, private val palette: SqlPalette) : OutputTransformation {
    private val lexer = SqlLexer(syntax)

    override fun TextFieldBuffer.transformOutput() {
        for (token in lexer.tokenize(asCharSequence())) {
            palette.styleOf(token.type)?.let { addStyle(it, token.start, token.end) }
        }
    }
}

private fun SqlPalette.styleOf(type: TokenType): SpanStyle? =
    when (type) {
        TokenType.Keyword -> keyword
        TokenType.String -> string
        TokenType.Number -> number
        TokenType.Comment -> comment
        TokenType.QuotedIdentifier -> quotedIdentifier
        TokenType.Parameter -> parameter
        else -> null
    }

/** [sql] coloured as the editor colours it, for read-only text shown outside an editor. */
@Composable
internal fun rememberHighlightedSql(sql: String, syntax: SqlSyntax): AnnotatedString {
    val settings = LocalSettings.current
    val accent = LocalIslands.current.accent
    val colorTheme = LocalColorTheme.current
    val palette = remember(colorTheme, settings.syntaxScheme, settings.syntaxColors, accent) { sqlPalette(colorTheme, settings, accent) }
    return remember(sql, syntax, palette) {
        buildAnnotatedString {
            append(sql)
            for (token in SqlLexer(syntax).tokenize(sql)) palette.styleOf(token.type)?.let { addStyle(it, token.start, token.end) }
        }
    }
}

