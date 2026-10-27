package dev.datagrok.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.datagrok.app.AppState
import dev.datagrok.app.AppTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import org.jetbrains.jewel.window.utils.clientRegion

@Composable
internal fun DecoratedWindowScope.TitleBarView(state: AppState) {
    TitleBar(Modifier.newFullscreenControls()) {
        Text(state.activeConnection?.let { "$title — ${it.name}" } ?: title)

        Row(Modifier.align(Alignment.End)) {
            Tooltip({ Text(if (state.theme.isDark()) "Switch to light theme" else "Switch to dark theme") }) {
                IconButton(
                    onClick = { state.theme = state.theme.next() },
                    modifier = Modifier.size(40.dp).padding(5.dp).clientRegion("switch_theme"),
                ) {
                    Icon(
                        key = if (state.theme == AppTheme.Dark) AllIconsKeys.Actions.Lightning else AllIconsKeys.Actions.ProfileBlue,
                        contentDescription = "Theme",
                    )
                }
            }
        }
    }
}
