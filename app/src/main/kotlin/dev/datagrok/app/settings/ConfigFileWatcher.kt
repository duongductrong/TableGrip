package dev.datagrok.app.settings

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notices hand edits of settings.json and of the theme files, so they apply while the app runs, as in VS Code and Zed.
 * It compares modification times and sizes once a second rather than using a WatchService, which on macOS polls anyway
 * (every few seconds) and misses editors that save by renaming a temporary file.
 */
internal class ConfigFileWatcher(
    private val settingsFile: Path,
    private val themesDir: Path,
    /** Called on the UI thread. */
    private val onSettingsChanged: () -> Unit,
    /** Called on the UI thread. */
    private val onThemesChanged: () -> Unit,
    private val interval: Long = 1_000,
) {
    fun start(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.IO) {
            var settingsStamp = stamp(settingsFile)
            var themesStamp = themesStamp()
            while (isActive) {
                delay(interval)
                val s = stamp(settingsFile)
                if (s != settingsStamp) {
                    settingsStamp = s
                    withContext(Dispatchers.Main) { onSettingsChanged() }
                }
                val t = themesStamp()
                if (t != themesStamp) {
                    themesStamp = t
                    withContext(Dispatchers.Main) { onThemesChanged() }
                }
            }
        }

    private fun stamp(file: Path): Pair<Long, Long>? =
        runCatching { if (file.exists()) file.getLastModifiedTime().toMillis() to Files.size(file) else null }.getOrNull()

    private fun themesStamp(): List<Pair<String, Pair<Long, Long>?>> =
        runCatching {
            if (!themesDir.isDirectory()) emptyList()
            else themesDir.listDirectoryEntries().filter { it.extension.equals("json", ignoreCase = true) }.sorted().map { it.fileName.toString() to stamp(it) }
        }.getOrDefault(emptyList())
}

/** Opens [path] with the system's default app: a JSON file in the user's editor, a folder in the file manager. */
internal fun openInSystem(path: Path) {
    val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.OPEN) }
    if (desktop != null) desktop.open(path.toFile())
    else {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            "mac" in os -> listOf("open", path.toString())
            "win" in os -> listOf("explorer", path.toString())
            else -> listOf("xdg-open", path.toString())
        }
        ProcessBuilder(command).start()
    }
}
