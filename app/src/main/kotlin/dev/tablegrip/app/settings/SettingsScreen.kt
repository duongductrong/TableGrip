package dev.tablegrip.app.settings

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.parseHexColor
import dev.tablegrip.app.ui.toHex
import kotlinx.coroutines.flow.drop
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Settings as a screen in the main window, in place of the explorer and editor tabs: searchable page list on the left,
 * the selected page on the right. The title bar names the screen; changes apply as they are made, so there is nothing
 * to confirm when leaving (Esc or the settings button closes it).
 */
@Composable
internal fun SettingsScreen(app: AppState) {
    val form = remember(app) { SettingsForm({ app.settings }) { updated -> app.updateSettings { updated } } }
    val query = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val needle = query.text.toString()
    val visible = SettingsPages.all.filter { it.matches(needle) }
    val page = visible.firstOrNull { it.id == app.settingsPage } ?: visible.firstOrNull()

    Row(Modifier.fillMaxSize()) {
        Sidebar(visible, page, query, focus, errorPages = SettingsPages.all.filter { p -> p.fields.any { it in form.errors } }) {
            app.settingsPage = it.id
        }
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (page == null) {
                Text(tr("No settings match \"{0}\"", needle.trim()), Modifier.align(Alignment.Center), color = JewelTheme.globalColors.text.info)
            } else {
                PageContent(page, app, form, needle)
            }
        }
    }
}

@Composable
private fun Sidebar(
    pages: List<SettingsPage>,
    selected: SettingsPage?,
    query: TextFieldState,
    focus: FocusRequester,
    errorPages: List<SettingsPage>,
    onSelect: (SettingsPage) -> Unit,
) {
    val islands = LocalIslands.current
    Column(Modifier.width(230.dp).fillMaxHeight().background(islands.frame).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextField(
            query,
            Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(tr("Search settings")) },
            leadingIcon = { Icon(AllIconsKeys.Actions.Search, null, Modifier.padding(end = 6.dp)) },
        )
        Spacer(Modifier.height(8.dp))
        VerticallyScrollableContainer(Modifier.weight(1f).fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                pages.groupBy { it.group }.forEach { (group, groupPages) ->
                    Text(
                        group,
                        Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                        color = JewelTheme.globalColors.text.info,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    groupPages.forEach { page -> SidebarItem(page, page == selected, hasError = page in errorPages) { onSelect(page) } }
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(page: SettingsPage, selected: Boolean, hasError: Boolean, onClick: () -> Unit) {
    val islands = LocalIslands.current
    Row(
        Modifier.fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) islands.accent.copy(alpha = if (JewelTheme.isDark) 0.28f else 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(page.title, Modifier.weight(1f), maxLines = 1)
        if (hasError) Icon(AllIconsKeys.General.Error, tr("Has errors"))
    }
}

@Composable
private fun PageContent(page: SettingsPage, app: AppState, form: SettingsForm, query: String) {
    VerticallyScrollableContainer(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${page.group}  ›  ", color = JewelTheme.globalColors.text.info)
                Text(page.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Link(tr("Reset to defaults"), onClick = { form.reset(page) })
            }
            Text(page.description, color = JewelTheme.globalColors.text.info)
            SettingsFileError(app)
            page.sections.forEach { section ->
                Spacer(Modifier.height(10.dp))
                section.title?.let {
                    Text(it, fontWeight = FontWeight.SemiBold)
                    Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(bottom = 4.dp))
                }
                section.fields.forEach { field -> FieldRow(field, app, form, highlighted = query.isNotBlank() && field.label.contains(query.trim(), ignoreCase = true)) }
                section.panel?.invoke(app)
            }
        }
    }
}

@Composable
private fun FieldRow(field: SettingField, app: AppState, form: SettingsForm, highlighted: Boolean) {
    val islands = LocalIslands.current
    val error = form.errors[field]
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlighted) islands.accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.width(180.dp))
            when (field) {
                is ChoiceField<*> -> ChoiceControl(field, app, form)
                is IntField -> {
                    val text = form.text(field)
                    // Typing applies the value once it is valid; the first emission is the current text.
                    LaunchedEffect(field) { snapshotFlow { text.text.toString() }.drop(1).collect { form.commit(field) } }
                    TextField(text, Modifier.width(110.dp), outline = if (error != null) Outline.Error else Outline.None)
                    field.unit?.let { Text(it, Modifier.padding(start = 8.dp), color = JewelTheme.globalColors.text.info) }
                }
                is StringField -> {
                    val text = form.text(field)
                    LaunchedEffect(field) { snapshotFlow { text.text.toString() }.drop(1).collect { form.commit(field) } }
                    TextField(text, Modifier.weight(1f), placeholder = { Text(field.placeholder) }, outline = if (error != null) Outline.Error else Outline.None)
                }
                is BooleanField -> Checkbox(field.get(app.settings), onCheckedChange = { form.toggle(field, it) })
                is ColorField -> ColorControl(field, app, form, error != null)
            }
        }
        val note = error ?: field.hint
        if (note != null) {
            Text(
                note,
                Modifier.padding(start = 180.dp),
                color = if (error != null) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ColorControl(field: ColorField, app: AppState, form: SettingsForm, invalid: Boolean) {
    val text = form.text(field)
    LaunchedEffect(field) { snapshotFlow { text.text.toString() }.drop(1).collect { form.commit(field) } }
    val fallback = field.fallback(app.settings)
    val current = parseHexColor(field.get(app.settings)) ?: fallback
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(current, selected = false, size = 22)
        TextField(text, Modifier.width(110.dp), placeholder = { Text(fallback.toHex()) }, outline = if (invalid) Outline.Error else Outline.None)
        field.swatches.forEach { color ->
            Swatch(color, selected = color == current, size = 18) { form.pick(field, if (color == fallback) null else color) }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, size: Int, onClick: (() -> Unit)? = null) {
    val ring = if (selected) JewelTheme.globalColors.text.normal else JewelTheme.globalColors.borders.normal
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color)
            .border(if (selected) 2.dp else 1.dp, ring, RoundedCornerShape(5.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    )
}

@Composable
private fun <T> ChoiceControl(field: ChoiceField<T>, app: AppState, form: SettingsForm) {
    ListComboBox(
        items = field.options.map(field.optionLabel),
        selectedIndex = field.options.indexOf(field.get(app.settings)).coerceAtLeast(0),
        onSelectedItemChange = { form.choose(field, field.options[it]) },
        modifier = Modifier.width(200.dp),
    )
}
