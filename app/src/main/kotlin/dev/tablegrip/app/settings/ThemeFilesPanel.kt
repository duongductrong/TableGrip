package dev.tablegrip.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.theme.ThemeRegistry
import dev.tablegrip.core.i18n.I18n
import dev.tablegrip.core.i18n.Language
import dev.tablegrip.core.i18n.tr
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/**
 * Where settings and themes live as files, for editing by hand: settings.json applies as soon as it is saved, and theme
 * files in the themes folder show up in the pickers above. Lists theme files that couldn't be read, and why.
 */
@Composable
internal fun ThemeFilesPanel(app: AppState) {
    val errors = ThemeRegistry.errors
    Column(Modifier.padding(start = 4.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Link(tr("Open settings.json"), onClick = app::openSettingsFile)
            Link(tr("Open themes folder"), onClick = app::openThemesFolder)
            Link(tr("Customize current theme"), onClick = { app.customizeTheme(app.theme.isDark()) })
        }
        Text(
            tr("\"Customize current theme\" copies its file into the themes folder; edits apply when you save. A theme file may also set just the keys it changes."),
            color = JewelTheme.globalColors.text.info,
        )
        errors.forEach { error ->
            Text("${error.file.fileName}: ${error.message}", color = JewelTheme.globalColors.text.error)
        }
    }
}

/** Shown above every settings page while settings.json can't be read. */
@Composable
internal fun SettingsFileError(app: AppState) {
    val error = app.settingsError ?: return
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("settings.json has an error, so TableGrip is using defaults and won't save changes until it's fixed:"), color = JewelTheme.globalColors.text.error)
        Text(error, color = JewelTheme.globalColors.text.error)
        Link(tr("Open settings.json"), onClick = app::openSettingsFile)
    }
}

/** Under the language picker: a new language shows after a restart, which this offers once one is chosen. */
@Composable
internal fun LanguagePanel(app: AppState) {
    if (Language.resolve(app.settings.language) == I18n.current) return
    Row(Modifier.padding(start = 4.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Restart TableGrip to show it in the new language."), color = JewelTheme.globalColors.text.info)
        Link(tr("Restart Now"), onClick = app.restartApp)
    }
}
