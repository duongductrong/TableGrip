package dev.datagrok.app.grid

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.datagrok.core.edit.EditCheck
import dev.datagrok.core.edit.EditKind
import dev.datagrok.core.filter.FilterValues
import dev.datagrok.core.filter.TemporalValue
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.treeStyle

/** Where a boolean cell's check box sits, from the cell's left edge; [GridController.isOnCheckBox] hit-tests it. */
internal val CheckBoxInset = 8.dp
internal val CheckBoxSize = 13.dp

internal val ErrorColor = Color(0xFFDB5C5C)
internal val WarningColor = Color(0xFFD69A2D)

/** A check box drawn rather than composed, so a grid full of them stays cheap and never takes focus. */
@Composable
internal fun CheckBoxGlyph(checked: Boolean?, enabled: Boolean, modifier: Modifier = Modifier) {
    val accent = JewelTheme.globalColors.outlines.focused
    val muted = JewelTheme.globalColors.text.info
    // The theme's border colour nearly disappears on the dark grid; the muted text colour reads on both.
    val border = muted.copy(alpha = if (enabled) 0.85f else 0.5f)
    Canvas(modifier.size(CheckBoxSize)) {
        val radius = CornerRadius(3.dp.toPx())
        val stroke = 1.dp.toPx()
        when (checked) {
            true -> {
                drawRoundRect(if (enabled) accent else muted, cornerRadius = radius)
                val path =
                    Path().apply {
                        moveTo(size.width * 0.24f, size.height * 0.52f)
                        lineTo(size.width * 0.43f, size.height * 0.7f)
                        lineTo(size.width * 0.77f, size.height * 0.3f)
                    }
                drawPath(path, Color.White, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round))
            }
            false -> drawRoundRect(border, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), cornerRadius = radius, style = Stroke(stroke))
            null ->
                drawRoundRect(
                    muted.copy(alpha = 0.6f),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = radius,
                    style = Stroke(stroke, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
                )
        }
    }
}

/** What the inline editor says under the cell: why the text can't be stored, or what it will be stored as. */
internal fun EditCheck.hint(): Pair<String, Color?>? =
    when (this) {
        is EditCheck.Invalid -> message to ErrorColor
        is EditCheck.Ok -> note?.let { it to if (warning) WarningColor else null }
        EditCheck.Empty -> null
    }

/** A one-line note floating just below the cell being edited; it never takes focus or clicks. */
@Composable
internal fun EditHint(text: String, color: Color?) {
    val shape = RoundedCornerShape(4.dp)
    Popup(popupPositionProvider = BelowCellPositionProvider, properties = PopupProperties(focusable = false)) {
        Box(
            Modifier.widthIn(max = 420.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, color ?: JewelTheme.globalColors.borders.normal, shape)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(text, color = color ?: JewelTheme.globalColors.text.info, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Opens just below the anchor (the cell), or above it when there's no room below. */
internal object BelowCellPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val below = anchorBounds.bottom + popupContentSize.height <= windowSize.height
        val y = if (below) anchorBounds.bottom + 2 else (anchorBounds.top - popupContentSize.height - 2).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/** [text] as a number moved by [delta], for ⌥↑/⌥↓ in numeric cells; null when it isn't a number. */
internal fun stepNumber(text: String, delta: Int): String? {
    val cleaned = text.trim().replace("_", "").replace(" ", "")
    val value = if (cleaned.isEmpty()) BigDecimal.ZERO else cleaned.toBigDecimalOrNull() ?: return null
    return value.add(BigDecimal(delta)).toPlainString()
}

private val PrettyJson = Json { prettyPrint = true }

/** [text] re-indented, or minified; null when it isn't JSON. */
internal fun reformatJson(text: String, pretty: Boolean): String? =
    runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { (if (pretty) PrettyJson else Json).encodeToString(JsonElement.serializer(), it) }

/** The date and time [text] stands for, or null when it doesn't parse; relative input resolves against now. */
private fun parseMoment(text: String): Pair<LocalDate?, LocalTime?> =
    when (val value = runCatching { FilterValues.temporal(text, LocalDateTime.now()) }.getOrNull()) {
        is TemporalValue.Day -> value.date to null
        is TemporalValue.Moment -> value.dateTime.toLocalDate() to value.dateTime.toLocalTime()
        is TemporalValue.TimeOfDay -> null to value.time
        null -> null to null
    }

private val MonthTitle = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * A calendar (dates), a time of day (times), or both, editing [text] in place: picking writes the canonical value so
 * the inline field and the picker always agree. Arrows move by a day or a week (a minute in time pickers), Page Up/Down
 * by a month (an hour), Enter applies, Esc goes back to typing.
 */
@Composable
internal fun TemporalPicker(kind: EditKind, text: TextFieldState, onApply: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(6.dp)
    Popup(popupPositionProvider = BelowCellPositionProvider, onDismissRequest = onClose, properties = PopupProperties(focusable = true)) {
        TemporalPickerContent(
            kind,
            text,
            onApply,
            Modifier.width(if (kind != EditKind.Time) 252.dp else 200.dp)
                .clip(shape)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                .focusRequester(focus)
                .focusable()
                .padding(8.dp),
            onClose = onClose,
        )
    }
}

/** The picker itself, for [TemporalPicker]'s popup and the value panel. */
@Composable
internal fun TemporalPickerContent(kind: EditKind, text: TextFieldState, onApply: () -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val (parsedDate, parsedTime) = parseMoment(text.text.toString())
    val today = LocalDate.now()
    val date = parsedDate ?: today
    val time = parsedTime ?: LocalTime.MIDNIGHT
    var month by remember { mutableStateOf(YearMonth.from(date)) }
    LaunchedEffect(date) { month = YearMonth.from(date) }

    fun write(newDate: LocalDate, newTime: LocalTime) {
        val value =
            when (kind) {
                EditKind.Date -> FilterValues.format(newDate)
                EditKind.Time -> FilterValues.format(newTime)
                else -> FilterValues.format(LocalDateTime.of(newDate, newTime))
            }
        text.setTextAndPlaceCursorAtEnd(value)
    }

    val hasDate = kind != EditKind.Time
    Column(
        modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> onApply()
                Key.Escape -> onClose?.invoke() ?: return@onPreviewKeyEvent false
                Key.DirectionLeft -> if (hasDate) write(date.minusDays(1), time) else write(date, time.minusMinutes(1))
                Key.DirectionRight -> if (hasDate) write(date.plusDays(1), time) else write(date, time.plusMinutes(1))
                Key.DirectionUp -> if (hasDate) write(date.minusWeeks(1), time) else write(date, time.plusMinutes(1))
                Key.DirectionDown -> if (hasDate) write(date.plusWeeks(1), time) else write(date, time.minusMinutes(1))
                Key.PageUp -> if (hasDate) write(date.minusMonths(1), time) else write(date, time.plusHours(1))
                Key.PageDown -> if (hasDate) write(date.plusMonths(1), time) else write(date, time.minusHours(1))
                else -> return@onPreviewKeyEvent false
            }
            true
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasDate) Calendar(month, selected = date.takeIf { parsedDate != null }, today = today, onMonth = { month = it }, onPick = { write(it, time) })
        if (kind != EditKind.Date) TimeFields(time) { write(date, it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val now = LocalDateTime.now().withNano(0)
                write(now.toLocalDate(), if (kind == EditKind.Date) time else now.toLocalTime())
            }) {
                Text(if (kind == EditKind.Date) "Today" else "Now")
            }
            Box(Modifier.weight(1f))
            if (onClose != null) DefaultButton(onClick = onApply) { Text("Apply") }
        }
    }
}

@Composable
private fun Calendar(month: YearMonth, selected: LocalDate?, today: LocalDate, onMonth: (YearMonth) -> Unit, onPick: (LocalDate) -> Unit) {
    val info = JewelTheme.globalColors.text.info
    val selectedBackground = JewelTheme.treeStyle.colors.backgroundSelectedActive
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PickerIcon("Previous month", left = true) { onMonth(month.minusMonths(1)) }
            Text(MonthTitle.format(month), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            PickerIcon("Next month", left = false) { onMonth(month.plusMonths(1)) }
        }
        Row {
            DayOfWeek.entries.forEach { day ->
                Text(day.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()).take(2), Modifier.width(33.dp), color = info, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
        // Six weeks from the Monday on or before the 1st, so every month fits and the layout never jumps.
        val first = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        for (week in 0 until 6) {
            Row {
                for (day in 0 until 7) {
                    val date = first.plusDays((week * 7 + day).toLong())
                    val inMonth = YearMonth.from(date) == month
                    val shape = RoundedCornerShape(4.dp)
                    var cell = Modifier.width(33.dp).height(24.dp).clip(shape)
                    if (date == selected) cell = cell.background(selectedBackground)
                    if (date == today) cell = cell.border(1.dp, JewelTheme.globalColors.outlines.focused, shape)
                    Box(cell.clickable { onPick(date) }, contentAlignment = Alignment.Center) {
                        Text(date.dayOfMonth.toString(), color = if (inMonth) JewelTheme.globalColors.text.normal else info.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerIcon(description: String, left: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(if (left) AllIconsKeys.General.ChevronLeft else AllIconsKeys.General.ChevronRight, description)
    }
}

/** Hours, minutes and seconds, each with steppers; seconds keep any fraction the value had. */
@Composable
private fun TimeFields(time: LocalTime, onChange: (LocalTime) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Time", color = JewelTheme.globalColors.text.info, modifier = Modifier.padding(end = 4.dp))
        Stepper("%02d".format(time.hour), { onChange(time.plusHours(1)) }, { onChange(time.minusHours(1)) })
        Text(":")
        Stepper("%02d".format(time.minute), { onChange(time.plusMinutes(1)) }, { onChange(time.minusMinutes(1)) })
        Text(":")
        Stepper("%02d".format(time.second), { onChange(time.plusSeconds(1)) }, { onChange(time.minusSeconds(1)) })
    }
}

@Composable
private fun Stepper(value: String, onUp: () -> Unit, onDown: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.border(1.dp, JewelTheme.globalColors.borders.normal, shape).padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = JewelTheme.editorTextStyle)
        Column {
            Box(Modifier.size(16.dp, 11.dp).clickable(onClick = onUp), contentAlignment = Alignment.Center) {
                Icon(AllIconsKeys.General.ChevronUp, "Up", Modifier.size(10.dp))
            }
            Box(Modifier.size(16.dp, 11.dp).clickable(onClick = onDown), contentAlignment = Alignment.Center) {
                Icon(AllIconsKeys.General.ChevronDown, "Down", Modifier.size(10.dp))
            }
        }
    }
}
