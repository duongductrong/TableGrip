package dev.tablegrip.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.foundation.modifier.onHover
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.LocalMenuController
import org.jetbrains.jewel.ui.component.MenuContent
import org.jetbrains.jewel.ui.component.MenuItemState
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.theme.menuStyle

/**
 * A submenu row for a Jewel `PopupMenu` that looks like Jewel's own `submenu` but doesn't take the pointer.
 *
 * Jewel opens a nested menu as a focusable popup, and a focusable popup layer captures the pointer: while it is open the
 * parent menu's items stop hovering, so reaching another item means dismissing the submenu first. This one opens on
 * hover as a non-focusable popup, so the pointer keeps moving over the parent menu. It closes shortly after the pointer
 * rests on another item; the grace period lets a diagonal move cross a neighbouring row on its way into the submenu.
 * Opened from the keyboard (→, Enter or Space) it is focusable instead, so the arrow keys reach its items and ← or Esc
 * go back.
 *
 * The row always keeps the icon column, matching menus whose other items have icons.
 */
fun MenuScope.hoverSubmenu(
    iconKey: IconKey? = null,
    enabled: Boolean = true,
    submenu: MenuScope.() -> Unit,
    content: @Composable () -> Unit,
) = passiveItem { HoverSubmenuItem(iconKey, enabled, submenu, content) }

/** How long the submenu stays open once the pointer has left both it and its row. */
private const val CloseDelayMillis = 300L

@OptIn(InternalJewelApi::class) // MenuContent: Jewel's own menu body, so the submenu looks like every other menu.
@Suppress("DEPRECATION") // MenuItemState and the colour lookups are only "being made private"; Jewel's items use them.
@Composable
private fun HoverSubmenuItem(
    iconKey: IconKey?,
    enabled: Boolean,
    submenu: MenuScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val style = JewelTheme.menuStyle
    val metrics = style.metrics.itemMetrics
    val colors = style.colors.itemColors
    val parentController = LocalMenuController.current
    val inputModes = LocalInputModeManager.current
    val interaction = remember { MutableInteractionSource() }
    val rowHovered by interaction.collectIsHoveredAsState()
    val rowFocused by interaction.collectIsFocusedAsState()
    val rowFocus = remember { FocusRequester() }
    var subHovered by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    var byKeyboard by remember { mutableStateOf(false) }

    LaunchedEffect(rowHovered, subHovered, enabled) {
        when {
            !enabled -> open = false
            rowHovered -> {
                // Like Jewel's items, the hovered row takes focus, so the item hovered before it stops looking current.
                rowFocus.requestFocus()
                byKeyboard = false
                open = true
            }
            subHovered -> Unit
            open && !byKeyboard -> {
                delay(CloseDelayMillis)
                open = false
            }
        }
    }

    val itemState = MenuItemState.of(selected = false, enabled = enabled, focused = rowFocused || open, hovered = rowHovered)
    val contentColor = colors.contentFor(itemState).value
    val background = colors.backgroundFor(itemState).value
    val openFromKeyboard = {
        byKeyboard = true
        open = true
    }

    Box(
        Modifier
            .fillMaxWidth()
            .focusRequester(rowFocus)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled) {
                if (inputModes.inputMode == InputMode.Keyboard) openFromKeyboard() else open = true
            }
            .onKeyEvent {
                if (enabled && it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                    openFromKeyboard()
                    true
                } else {
                    false
                }
            },
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(color = contentColor)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.minHeight)
                    .drawBehind {
                        val corner = metrics.selectionCornerSize.toPx(size, this)
                        val left = metrics.outerPadding.calculateLeftPadding(layoutDirection).toPx()
                        val right = metrics.outerPadding.calculateRightPadding(layoutDirection).toPx()
                        val top = metrics.outerPadding.calculateTopPadding().toPx()
                        val bottom = metrics.outerPadding.calculateBottomPadding().toPx()
                        drawRoundRect(
                            color = background,
                            topLeft = Offset(left, top),
                            size = Size(size.width - left - right, size.height - top - bottom),
                            cornerRadius = CornerRadius(corner, corner),
                        )
                    }
                    .padding(metrics.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconModifier = Modifier.size(metrics.iconSize)
                if (iconKey != null) Icon(iconKey, contentDescription = null, modifier = iconModifier) else Box(iconModifier)
                Box(Modifier.weight(1f)) { content() }
                Icon(
                    key = style.icons.submenuChevron,
                    contentDescription = null,
                    modifier = Modifier.size(metrics.iconSize),
                    tint = colors.iconTintFor(itemState).value,
                )
            }
        }

        if (open) {
            val close: () -> Unit = {
                open = false
                subHovered = false
                rowFocus.requestFocus()
            }
            val controller = remember(parentController) {
                parentController.submenuController { close(); true }
            }
            // Recreated when the input mode flips, since a popup's focusability is fixed once shown.
            key(byKeyboard) {
                SubmenuPopup(style, focusable = byKeyboard, onClose = close, onHover = { subHovered = it }) {
                    CompositionLocalProvider(LocalMenuController provides controller) {
                        MenuContent(style = style, content = submenu)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmenuPopup(
    style: MenuStyle,
    focusable: Boolean,
    onClose: () -> Unit,
    onHover: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val inputModes = LocalInputModeManager.current
    val controller = LocalMenuController.current
    val position = remember(style, density) { BesideAnchor(style, density) }
    Popup(
        popupPositionProvider = position,
        properties = PopupProperties(focusable = focusable),
        onDismissRequest = if (focusable) onClose else null,
        onKeyEvent = {
            if (!focusable || it.type != KeyEventType.KeyDown) return@Popup false
            when (it.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                Key.DirectionLeft -> onClose()
                Key.Escape -> controller.closeAll(InputMode.Keyboard, true)
                else -> return@Popup false
            }
            true
        },
    ) {
        Box(Modifier.onHover(onHover)) { content() }
        if (focusable) {
            val popupFocus = LocalFocusManager.current
            LaunchedEffect(Unit) {
                inputModes.requestInputMode(InputMode.Keyboard)
                popupFocus.moveFocus(FocusDirection.Next)
            }
        }
    }
}

/** Places the submenu beside its row, top-aligned, flipping to the other side when it doesn't fit — as Jewel does. */
private class BesideAnchor(private val style: MenuStyle, private val density: Density) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        with(density) {
            val margin = style.metrics.menuMargin
            val bounds = IntRect(
                left = margin.calculateLeftPadding(layoutDirection).roundToPx(),
                top = margin.calculateTopPadding().roundToPx(),
                right = windowSize.width - margin.calculateRightPadding(layoutDirection).roundToPx(),
                bottom = windowSize.height - margin.calculateBottomPadding().roundToPx(),
            )
            val offset = style.metrics.submenuMetrics.offset
            val dx = offset.x.roundToPx() * if (layoutDirection == LayoutDirection.Ltr) 1 else -1
            val y = anchorBounds.top + offset.y.roundToPx()
            val rightRoom = bounds.right - anchorBounds.right - dx
            val leftRoom = anchorBounds.left - dx - bounds.left
            val x = if (rightRoom >= popupContentSize.width || rightRoom >= leftRoom) anchorBounds.right + dx
            else anchorBounds.left - dx - popupContentSize.width
            IntOffset(
                x.coerceIn(bounds.left, (bounds.right - popupContentSize.width).coerceAtLeast(bounds.left)),
                y.coerceIn(bounds.top, (bounds.bottom - popupContentSize.height).coerceAtLeast(bounds.top)),
            )
        }
}
