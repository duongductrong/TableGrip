package dev.tablegrip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** Below its anchor, left edges lined up, nudged left when it would run past the window's right edge. */
private object BelowAnchorStart : PopupPositionProvider {
    private const val Gap = 6
    private const val Margin = 8

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width - Margin).coerceAtLeast(Margin)
        return IntOffset(x, anchorBounds.bottom + Gap)
    }
}

/**
 * The statements a grid's Submit is about to run, under the toolbar button that asked, with Cancel and Submit.
 * When [canSkip], a checkbox lets the user stop being asked; it only takes effect if they submit.
 *
 * Esc or a click outside cancels; the submit shortcut again submits.
 */
@Composable
internal fun ReviewChangesPopup(
    changeCount: Int,
    /** What is being changed, e.g. `orders · Shop (local)`. */
    target: String,
    statements: AnnotatedString,
    /** How the statements run, under them. */
    note: String,
    canSkip: Boolean,
    onCancel: () -> Unit,
    onSubmit: (skipReviewNextTime: Boolean) -> Unit,
) {
    var skip by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val info = JewelTheme.globalColors.text.info
    val shape = RoundedCornerShape(10.dp)
    LaunchedEffect(Unit) { focus.requestFocus() }

    Popup(popupPositionProvider = BelowAnchorStart, onDismissRequest = onCancel, properties = PopupProperties(focusable = true)) {
        // Room for the shadow, which the popup window would clip otherwise.
        Box(Modifier.padding(start = 2.dp, end = 12.dp, bottom = 16.dp)) {
            Column(
                Modifier.width(560.dp)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(JewelTheme.globalColors.panelBackground)
                    .border(1.dp, JewelTheme.globalColors.borders.normal, shape)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> true.also { onCancel() }
                            Shortcuts.Submit.matches(event) -> true.also { onSubmit(skip) }
                            else -> false
                        }
                    },
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (changeCount == 1) tr("Review 1 change") else tr("Review {0} changes", changeCount),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f).width(16.dp))
                    Text(target, color = info, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                SelectionContainer {
                    Box(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(statements, style = JewelTheme.editorTextStyle.copy(lineHeight = 20.sp), softWrap = false)
                    }
                }
                Text(note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), color = info, fontSize = 12.sp)
                Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (canSkip) {
                            CheckboxRow(tr("Don't show this again"), skip, onCheckedChange = { skip = it })
                            if (skip) Text(tr("Turn it back on in Settings › Data Editor."), color = info, fontSize = 12.sp)
                        } else {
                            Text(tr("This connection asks before every change."), color = info, fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(onClick = onCancel) { Text(tr("Cancel")) }
                    Tooltip({ Text(tr("Submit ({0})", Shortcuts.Submit)) }) { DefaultButton(onClick = { onSubmit(skip) }) { Text(tr("Submit")) } }
                }
            }
        }
    }
}
