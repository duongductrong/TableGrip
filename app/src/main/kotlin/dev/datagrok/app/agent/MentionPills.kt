package dev.datagrok.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.datagrok.app.ui.LocalIslands
import dev.datagrok.core.agent.Mention
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Text with each mention spelled out as its pill, and where those pills are in it. */
internal class PillLayout(val text: String, val pills: List<IntRange>, val chars: List<Char>) {
    companion object {
        fun of(text: CharSequence, mentions: Map<Char, Mention>): PillLayout {
            val pills = mutableListOf<IntRange>()
            val chars = mutableListOf<Char>()
            val shown = buildString {
                for (char in text) {
                    val mention = mentions[char]
                    if (mention == null) {
                        append(char)
                        continue
                    }
                    val pill = ComposerState.pillText(mention)
                    pills += length until length + pill.length
                    chars += char
                    append(pill)
                }
            }
            return PillLayout(shown, pills, chars)
        }
    }
}

/** The colours of a mention pill: a tint of the accent behind accent-coloured text. */
internal class PillColors(val text: Color, val fill: Color) {
    val style: SpanStyle
        get() = SpanStyle(color = text)

    companion object {
        @Composable
        fun current(): PillColors {
            val accent = LocalIslands.current.accent
            return PillColors(accent, accent.copy(alpha = 0.16f))
        }
    }
}

/** Rounded backgrounds behind [pills], one per line a pill spans; skipped while [layout] is of other text. */
internal fun DrawScope.drawPills(layout: TextLayoutResult?, length: Int, pills: List<IntRange>, fill: Color) {
    if (layout == null || layout.layoutInput.text.length != length) return
    val radius = CornerRadius(4f * density, 4f * density)
    for (pill in pills) {
        if (pill.last >= length) continue
        val firstLine = layout.getLineForOffset(pill.first)
        val lastLine = layout.getLineForOffset(pill.last)
        for (line in firstLine..lastLine) {
            val left = if (line == firstLine) layout.getBoundingBox(pill.first).left else layout.getLineLeft(line)
            val right = if (line == lastLine) layout.getBoundingBox(pill.last).right else layout.getLineRight(line)
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            // A little inset vertically, so pills on adjacent lines don't touch.
            val inset = (bottom - top) * 0.08f
            drawRoundRect(fill, Offset(left, top + inset), Size(right - left, bottom - top - 2 * inset), radius)
        }
    }
}

/** The pill under [position] in [layout], given where pills are in its text. */
internal fun pillUnder(layout: TextLayoutResult?, position: Offset, pills: PillLayout): Char? {
    if (layout == null || layout.layoutInput.text.length != pills.text.length) return null
    val offset = layout.getOffsetForPosition(position)
    // The offset is a caret position: the character under the pointer is on one side of it or the other.
    val index = listOf(offset, offset - 1).firstOrNull { it in pills.text.indices && layout.getBoundingBox(it).contains(position) } ?: return null
    return pills.pills.indexOfFirst { index in it }.takeIf { it >= 0 }?.let(pills.chars::get)
}

/** Calls [onPress] with where a primary-button press lands, without taking it from the text field underneath. */
internal fun Modifier.onPrimaryPress(onPress: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed) onPress(event.changes.first().position)
            }
        }
    }

/** A sent message with its mentions as pills; clicking one shows what was sent for it. */
@Composable
internal fun MentionMessageText(text: String, mentions: Map<Char, Mention>, modifier: Modifier = Modifier, lineHeight: TextUnit = TextUnit.Unspecified) {
    val colors = PillColors.current()
    val layout = remember(text, mentions) { PillLayout.of(text, mentions) }
    val annotated: AnnotatedString =
        remember(layout, colors.text) {
            buildAnnotatedString {
                var at = 0
                for (pill in layout.pills) {
                    append(layout.text.substring(at, pill.first))
                    withStyle(colors.style) { append(layout.text.substring(pill.first, pill.last + 1)) }
                    at = pill.last + 1
                }
                append(layout.text.substring(at))
            }
        }
    var result by remember { mutableStateOf<TextLayoutResult?>(null) }
    var preview by remember { mutableStateOf<Char?>(null) }
    Box(modifier) {
        Text(
            annotated,
            Modifier.drawBehind { drawPills(result, annotated.length, layout.pills, colors.fill) }
                .onPrimaryPress { position -> pillUnder(result, position, layout)?.let { preview = if (preview == it) null else it } },
            lineHeight = lineHeight,
            onTextLayout = { result = it },
        )
        preview?.let(mentions::get)?.let { mention -> MentionPreview(mention, onDismiss = { preview = null }) }
    }
}

/** Puts a popup just above its anchor, or below it when there is no room above. */
internal object AboveAnchor : PopupPositionProvider {
    private const val Gap = 6

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val above = anchorBounds.top - popupContentSize.height - Gap
        return IntOffset(x, if (above >= 0) above else anchorBounds.bottom + Gap)
    }
}

/** How a pill's details read: a line under its name, a block of code or text, and a note under it. */
private class PreviewContent(val detail: String, val body: String?, val note: String? = null, val noteIsError: Boolean = false)

private fun lines(range: IntRange) = if (range.first == range.last) "line ${range.first}" else "lines ${range.first}–${range.last}"

private fun previewOf(mention: Mention): PreviewContent =
    when (mention) {
        is Mention.Sql -> PreviewContent("${mention.engine} · ${lines(mention.lines)}", mention.sql)
        is Mention.Error -> PreviewContent("${mention.engine} · failed statement", mention.sql, mention.message, noteIsError = true)
        is Mention.Table -> PreviewContent("${mention.engine} · ${mention.connection} · ${mention.qualified}", mention.details, if (mention.details == null) "Its columns, keys and indexes are read when the message is sent." else null)
        is Mention.Column -> PreviewContent("${mention.engine} · ${mention.connection}", "${mention.type.lowercase()}${if (mention.primaryKey) ", primary key" else ""}")
        is Mention.Query -> PreviewContent("Saved query", mention.sql)
        is Mention.Cells -> {
            val count = mention.rows.last - mention.rows.first + 1
            val header = mention.columns.joinToString(" | ") { it.first }
            val body = mention.values?.take(PreviewRows)?.joinToString("\n", prefix = "$header\n") { row -> row.joinToString(" | ") { it?.take(40) ?: "NULL" } } ?: header
            val more = mention.values?.let { if (it.size > PreviewRows) "${it.size - PreviewRows} more rows are sent too." else null }
            PreviewContent(
                "${mention.engine} · ${mention.columns.size} column(s) × $count row(s)",
                body,
                if (mention.values == null) "Values aren't sent: this connection only shares its schema with agents." else more,
            )
        }
    }

private const val PreviewRows = 8

/** What a pill stands for, as the agent reads it. [onRemove] offers to take it out of the message. */
@Composable
internal fun MentionPreview(mention: Mention, onDismiss: () -> Unit, onRemove: (() -> Unit)? = null) {
    val islands = LocalIslands.current
    val shape = RoundedCornerShape(8.dp)
    val content = previewOf(mention)
    Popup(popupPositionProvider = AboveAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(
            Modifier.widthIn(min = 240.dp, max = 380.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("@${mention.label}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(content.detail, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onRemove != null) {
                    Tooltip({ Text("Remove from message") }) { IconButton(onClick = onRemove) { Icon(AllIconsKeys.Actions.Close, "Remove from message") } }
                }
            }
            content.body?.let { body ->
                Text(
                    body.trim(),
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(end = 8.dp)
                        .clip(RoundedCornerShape(6.dp)).background(islands.frame)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                    style = JewelTheme.editorTextStyle,
                    softWrap = false,
                )
            }
            content.note?.let {
                Text(
                    it.trim(),
                    Modifier.padding(end = 8.dp),
                    color = if (content.noteIsError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
