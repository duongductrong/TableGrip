package dev.datagrok.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.ButtonColors
import org.jetbrains.jewel.ui.component.styling.ButtonStyle
import org.jetbrains.jewel.ui.component.styling.ComboBoxColors
import org.jetbrains.jewel.ui.component.styling.ComboBoxStyle
import org.jetbrains.jewel.ui.component.styling.DropdownColors
import org.jetbrains.jewel.ui.component.styling.DropdownStyle
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.LinkColors
import org.jetbrains.jewel.ui.component.styling.LinkStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultComboBoxStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultDropdownStyle
import org.jetbrains.jewel.ui.component.styling.LocalLazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.LocalLinkStyle
import org.jetbrains.jewel.ui.component.styling.LocalMenuStyle
import org.jetbrains.jewel.ui.component.styling.LocalOutlinedButtonStyle
import org.jetbrains.jewel.ui.component.styling.LocalPopupContainerStyle
import org.jetbrains.jewel.ui.component.styling.LocalSimpleListItemStyleStyle
import org.jetbrains.jewel.ui.component.styling.LocalTextAreaStyle
import org.jetbrains.jewel.ui.component.styling.LocalTextFieldStyle
import org.jetbrains.jewel.ui.component.styling.LocalTooltipStyle
import org.jetbrains.jewel.ui.component.styling.MenuColors
import org.jetbrains.jewel.ui.component.styling.MenuItemColors
import org.jetbrains.jewel.ui.component.styling.MenuStyle
import org.jetbrains.jewel.ui.component.styling.PopupContainerColors
import org.jetbrains.jewel.ui.component.styling.PopupContainerStyle
import org.jetbrains.jewel.ui.component.styling.SimpleListItemColors
import org.jetbrains.jewel.ui.component.styling.SimpleListItemStyle
import org.jetbrains.jewel.ui.component.styling.LocalDefaultTabStyle
import org.jetbrains.jewel.ui.component.styling.LocalEditorTabStyle
import org.jetbrains.jewel.ui.component.styling.TabColors
import org.jetbrains.jewel.ui.component.styling.TabStyle
import org.jetbrains.jewel.ui.component.styling.TextAreaColors
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle
import org.jetbrains.jewel.ui.component.styling.TextFieldColors
import org.jetbrains.jewel.ui.component.styling.TextFieldStyle
import org.jetbrains.jewel.ui.component.styling.TooltipColors
import org.jetbrains.jewel.ui.component.styling.TooltipStyle
import org.jetbrains.jewel.ui.theme.comboBoxStyle
import org.jetbrains.jewel.ui.theme.defaultButtonStyle
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import org.jetbrains.jewel.ui.theme.editorTabStyle
import org.jetbrains.jewel.ui.theme.dropdownStyle
import org.jetbrains.jewel.ui.theme.linkStyle
import org.jetbrains.jewel.ui.theme.menuStyle
import org.jetbrains.jewel.ui.theme.outlinedButtonStyle
import org.jetbrains.jewel.ui.theme.popupContainerStyle
import org.jetbrains.jewel.ui.theme.simpleListItemStyle
import org.jetbrains.jewel.ui.theme.textAreaStyle
import org.jetbrains.jewel.ui.theme.textFieldStyle
import org.jetbrains.jewel.ui.theme.tooltipStyle
import org.jetbrains.jewel.ui.theme.treeStyle

/**
 * Jewel's buttons, fields, combo boxes, menus, popups, tooltips, trees and links in the theme's colours. Int UI hard-codes
 * its own palette into each of these, so a theme that isn't Int UI-based has to replace them; metrics and icons are
 * kept as Int UI made them. With no [controls] (Graphite) everything stays Int UI's.
 */
@Composable
internal fun ThemedControls(controls: ControlColors?, dark: Boolean, accent: Color, content: @Composable () -> Unit) {
    if (controls == null) {
        content()
        return
    }
    val c = controls
    val shadow = if (dark) Color(0x66000000) else Color(0x29000000)

    val primary = JewelTheme.defaultButtonStyle
    val outlined = JewelTheme.outlinedButtonStyle
    val textField = JewelTheme.textFieldStyle
    val textArea = JewelTheme.textAreaStyle
    val comboBox = JewelTheme.comboBoxStyle
    val dropdown = JewelTheme.dropdownStyle
    val menu = JewelTheme.menuStyle
    val popup = JewelTheme.popupContainerStyle
    val tooltip = JewelTheme.tooltipStyle
    val tree = JewelTheme.treeStyle
    val listItem = JewelTheme.simpleListItemStyle
    val link = JewelTheme.linkStyle
    val defaultTab = JewelTheme.defaultTabStyle
    val editorTab = JewelTheme.editorTabStyle

    val menuStyle =
        MenuStyle(
            isDark = dark,
            colors =
                MenuColors(
                    background = c.popupBackground,
                    border = c.popupBorder,
                    shadow = shadow,
                    itemColors =
                        MenuItemColors(
                            background = c.popupBackground,
                            backgroundDisabled = c.popupBackground,
                            backgroundFocused = c.selection,
                            backgroundPressed = c.selection,
                            backgroundHovered = c.selection,
                            content = c.text,
                            contentDisabled = c.textDisabled,
                            contentFocused = c.text,
                            contentPressed = c.text,
                            contentHovered = c.text,
                            iconTint = c.textMuted,
                            iconTintDisabled = c.textDisabled,
                            iconTintFocused = c.text,
                            iconTintPressed = c.text,
                            iconTintHovered = c.text,
                            keybindingTint = c.textMuted,
                            keybindingTintDisabled = c.textDisabled,
                            keybindingTintFocused = c.text,
                            keybindingTintPressed = c.text,
                            keybindingTintHovered = c.text,
                            separator = c.popupBorder,
                        ),
                ),
            metrics = menu.metrics,
            icons = menu.icons,
        )

    CompositionLocalProvider(
        LocalDefaultButtonStyle provides
            ButtonStyle(
                colors =
                    ButtonColors(
                        background = SolidColor(c.buttonBackground),
                        backgroundDisabled = SolidColor(Color.Unspecified),
                        backgroundFocused = SolidColor(c.buttonBackground),
                        backgroundPressed = SolidColor(c.buttonPressed),
                        backgroundHovered = SolidColor(c.buttonHover),
                        content = c.buttonForeground,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.buttonForeground,
                        contentPressed = c.buttonForeground,
                        contentHovered = c.buttonForeground,
                        border = SolidColor(c.buttonBackground),
                        borderDisabled = SolidColor(c.secondaryBorder),
                        borderFocused = SolidColor(c.focus),
                        borderPressed = SolidColor(c.buttonPressed),
                        borderHovered = SolidColor(c.buttonHover),
                    ),
                metrics = primary.metrics,
                focusOutlineAlignment = primary.focusOutlineAlignment,
            ),
        LocalOutlinedButtonStyle provides
            ButtonStyle(
                colors =
                    ButtonColors(
                        background = SolidColor(Color.Transparent),
                        backgroundDisabled = SolidColor(Color.Unspecified),
                        backgroundFocused = SolidColor(Color.Transparent),
                        backgroundPressed = SolidColor(c.hover),
                        backgroundHovered = SolidColor(c.hover),
                        content = c.text,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.text,
                        contentPressed = c.text,
                        contentHovered = c.text,
                        border = SolidColor(c.secondaryBorder),
                        borderDisabled = SolidColor(c.secondaryBorder.copy(alpha = c.secondaryBorder.alpha * 0.6f)),
                        borderFocused = SolidColor(c.focus),
                        borderPressed = SolidColor(c.textMuted),
                        borderHovered = SolidColor(c.textMuted),
                    ),
                metrics = outlined.metrics,
                focusOutlineAlignment = outlined.focusOutlineAlignment,
            ),
        LocalTextFieldStyle provides
            TextFieldStyle(
                colors =
                    TextFieldColors(
                        background = c.inputBackground,
                        backgroundDisabled = Color.Unspecified,
                        backgroundFocused = c.inputBackground,
                        backgroundPressed = c.inputBackground,
                        backgroundHovered = c.inputBackground,
                        content = c.text,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.text,
                        contentPressed = c.text,
                        contentHovered = c.text,
                        border = c.inputBorder,
                        borderDisabled = c.inputBorder,
                        borderFocused = c.focus,
                        borderPressed = c.inputBorder,
                        borderHovered = c.inputBorder,
                        caret = c.text,
                        caretDisabled = c.text,
                        caretFocused = c.text,
                        caretPressed = c.text,
                        caretHovered = c.text,
                        placeholder = c.textMuted,
                    ),
                metrics = textField.metrics,
                iconButtonStyle = textField.iconButtonStyle,
            ),
        LocalTextAreaStyle provides
            TextAreaStyle(
                colors =
                    TextAreaColors(
                        background = c.inputBackground,
                        backgroundDisabled = Color.Unspecified,
                        backgroundFocused = c.inputBackground,
                        backgroundPressed = c.inputBackground,
                        backgroundHovered = c.inputBackground,
                        content = c.text,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.text,
                        contentPressed = c.text,
                        contentHovered = c.text,
                        border = c.inputBorder,
                        borderDisabled = c.inputBorder,
                        borderFocused = c.focus,
                        borderPressed = c.inputBorder,
                        borderHovered = c.inputBorder,
                        caret = c.text,
                        caretDisabled = c.text,
                        caretFocused = c.text,
                        caretPressed = c.text,
                        caretHovered = c.text,
                        placeholder = c.textMuted,
                    ),
                metrics = textArea.metrics,
            ),
        LocalDefaultComboBoxStyle provides
            ComboBoxStyle(
                colors =
                    ComboBoxColors(
                        background = c.inputBackground,
                        nonEditableBackground = c.inputBackground,
                        backgroundDisabled = c.inputBackground,
                        backgroundFocused = c.inputBackground,
                        backgroundPressed = c.inputBackground,
                        backgroundHovered = c.inputBackground,
                        content = c.text,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.text,
                        contentPressed = c.text,
                        contentHovered = c.text,
                        border = c.inputBorder,
                        borderDisabled = c.inputBorder,
                        borderFocused = c.focus,
                        borderPressed = c.inputBorder,
                        borderHovered = c.inputBorder,
                    ),
                metrics = comboBox.metrics,
                icons = comboBox.icons,
            ),
        LocalDefaultDropdownStyle provides
            DropdownStyle(
                colors =
                    DropdownColors(
                        background = c.inputBackground,
                        backgroundDisabled = c.inputBackground,
                        backgroundFocused = c.inputBackground,
                        backgroundPressed = c.inputBackground,
                        backgroundHovered = c.inputBackground,
                        content = c.text,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.text,
                        contentPressed = c.text,
                        contentHovered = c.text,
                        border = c.inputBorder,
                        borderDisabled = c.inputBorder,
                        borderFocused = c.focus,
                        borderPressed = c.inputBorder,
                        borderHovered = c.inputBorder,
                        iconTint = c.textMuted,
                        iconTintDisabled = c.textDisabled,
                        iconTintFocused = c.textMuted,
                        iconTintPressed = c.textMuted,
                        iconTintHovered = c.textMuted,
                    ),
                metrics = dropdown.metrics,
                icons = dropdown.icons,
                menuStyle = menuStyle,
            ),
        LocalMenuStyle provides menuStyle,
        LocalPopupContainerStyle provides
            PopupContainerStyle(isDark = dark, colors = PopupContainerColors(background = c.popupBackground, border = c.popupBorder, shadow = shadow), metrics = popup.metrics),
        LocalTooltipStyle provides
            TooltipStyle(
                colors = TooltipColors(background = c.popupBackground, content = c.text, border = c.popupBorder, shadow = shadow),
                metrics = tooltip.metrics,
                autoHideBehavior = tooltip.autoHideBehavior,
            ),
        LocalLazyTreeStyle provides
            LazyTreeStyle(
                colors = listColors(tree.colors, c),
                metrics = tree.metrics,
                icons = tree.icons,
            ),
        LocalSimpleListItemStyleStyle provides SimpleListItemStyle(colors = listColors(listItem.colors, c), metrics = listItem.metrics),
        LocalLinkStyle provides
            LinkStyle(
                colors =
                    LinkColors(
                        content = c.link,
                        contentDisabled = c.textDisabled,
                        contentFocused = c.link,
                        contentPressed = c.link,
                        contentHovered = c.link,
                        contentVisited = c.link,
                    ),
                metrics = link.metrics,
                icons = link.icons,
                underlineBehavior = link.underlineBehavior,
            ),
        LocalDefaultTabStyle provides defaultTab.withColors(c, accent),
        LocalEditorTabStyle provides editorTab.withColors(c, accent),
        content = content,
    )
}

/** Int UI's tab with the theme's text and the accent under the selected tab. */
private fun TabStyle.withColors(c: ControlColors, accent: Color): TabStyle =
    TabStyle(
        colors =
            TabColors(
                background = colors.background,
                backgroundDisabled = colors.backgroundDisabled,
                backgroundPressed = c.hover,
                backgroundHovered = c.hover,
                backgroundSelected = colors.backgroundSelected,
                content = c.text,
                contentDisabled = c.textDisabled,
                contentPressed = c.text,
                contentHovered = c.text,
                contentSelected = c.text,
                underline = colors.underline,
                underlineDisabled = colors.underlineDisabled,
                underlinePressed = colors.underlinePressed,
                underlineHovered = colors.underlineHovered,
                underlineSelected = accent,
            ),
        metrics = metrics,
        icons = icons,
        contentAlpha = contentAlpha,
        scrollbarStyle = scrollbarStyle,
    )

/** Int UI's list colours with the theme's selection; content stays unspecified so rows keep the surrounding text colour. */
private fun listColors(base: SimpleListItemColors, c: ControlColors): SimpleListItemColors =
    SimpleListItemColors(
        background = base.background,
        backgroundActive = base.backgroundActive,
        backgroundSelected = c.selectionInactive,
        backgroundSelectedActive = c.selection,
        content = base.content,
        contentActive = base.contentActive,
        contentSelected = base.contentSelected,
        contentSelectedActive = base.contentSelectedActive,
    )
