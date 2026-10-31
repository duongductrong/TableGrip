package dev.datagrok.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.application
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.handleConsoleShortcut
import dev.datagrok.app.files.DataFileWindowView
import dev.datagrok.app.files.FileDropTarget
import dev.datagrok.app.files.FileOpenRequests
import dev.datagrok.app.search.DoubleShiftDetector
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.table.handleFilterShortcut
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.FollowThemeAppearance
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.core.activity.ActivitySupport
import dev.datagrok.core.agent.ChatStore
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.app.ui.PinchZoom
import dev.datagrok.app.ui.TitleBarView
import java.awt.Desktop
import kotlinx.coroutines.delay
import org.jetbrains.jewel.window.DecoratedWindow

/**
 * How long a start without arguments waits for macOS to say which files it was launched to open, before showing the
 * main window. Finder sends them as an Apple Event just after launch.
 */
private const val LaunchFileGrace = 400L

fun main(args: Array<String>) {
    // Before the UI, so the file the app was launched to open (from Finder or a file association) isn't missed.
    FileOpenRequests.install(args)
    runApp()
}

private fun runApp() = application {
    val appState = remember {
        AppState(chatStore = ChatStore()).also {
            it.session.restore()
            it.session.startAutosave()
            it.scheduledBackups.sync()
        }
    }
    val doubleShift = remember { DoubleShiftDetector() }
    // ⌘Q bypasses the window's close request on macOS; route it through the same unsaved-work check.
    DisposableEffect(Unit) {
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.APP_QUIT_HANDLER) }
        desktop?.setQuitHandler { _, response ->
            response.cancelQuit()
            appState.requestExit(::exitApplication)
        }
        onDispose { desktop?.setQuitHandler(null) }
    }
    // Data files opened from the file manager each get a window of their own.
    DisposableEffect(Unit) {
        FileOpenRequests.listen { appState.files.open(it) }
        onDispose { FileOpenRequests.listen(null) }
    }
    // Opening a file from Finder shows just that file; a plain start shows the main window.
    LaunchedEffect(Unit) {
        if (!FileOpenRequests.launchedWithFiles) delay(LaunchFileGrace)
        if (appState.files.windows.isEmpty()) appState.mainWindowVisible = true
    }
    // Quits once no window is left; the main window only hides while file windows remain.
    fun closedWindow() {
        if (appState.files.windows.isEmpty() && !appState.mainWindowVisible) appState.requestExit(::exitApplication)
    }
    DatagrokTheme(isDark = appState.theme.isDark(), settings = appState.settings) {
        appState.files.windows.forEach { file ->
            key(file) { DataFileWindowView(appState, file, onClosed = ::closedWindow) }
        }
        DecoratedWindow(
            onCloseRequest = {
                if (appState.files.windows.isEmpty()) appState.requestExit(::exitApplication) else appState.mainWindowVisible = false
            },
            state = appState.windowState,
            visible = appState.mainWindowVisible,
            title = "Datagrok",
            onPreviewKeyEvent = {
                // Settings → Keymap is recording a shortcut: every key goes to it.
                if (Shortcuts.recording) return@DecoratedWindow false
                if (doubleShift.onKey(it.key, it.type)) appState.goToOpen = true
                handleTabSwitch(appState, it)
            },
            onKeyEvent = { handleWindowShortcut(appState, it) },
        ) {
            FollowThemeAppearance(window)
            DisposableEffect(window) {
                val stop = PinchZoom.install(window.rootPane)
                onDispose { stop?.invoke() }
            }
            // Data files dropped anywhere on the window open as connections.
            DisposableEffect(window) {
                val previous = window.contentPane.dropTarget
                window.contentPane.dropTarget = FileDropTarget(appState.connections::openDataFiles)
                onDispose { window.contentPane.dropTarget = previous }
            }
            TitleBarView(appState)
            MainScreen(appState, dialogs = appState.mainWindowVisible)
        }
    }
}

/** Tab switching runs before focused components see the key, so text fields and the grid can't swallow ⌃Tab. */
private fun handleTabSwitch(app: AppState, event: KeyEvent): Boolean =
    when {
        Shortcuts.NextTab.matches(event) || Shortcuts.NextTabAlt.matches(event) -> true.also { app.workspace.selectRelative(1) }
        Shortcuts.PreviousTab.matches(event) || Shortcuts.PreviousTabAlt.matches(event) -> true.also { app.workspace.selectRelative(-1) }
        else -> false
    }

/** App-wide shortcuts, for keys the focused component didn't handle itself. */
private fun handleWindowShortcut(app: AppState, event: KeyEvent): Boolean {
    val workspace = app.workspace
    val selected = workspace.selected
    fun done(block: () -> Unit) = true.also { block() }
    return when {
        // Esc and ⌘W leave the settings screen rather than acting on the hidden tabs.
        app.settingsOpen && (Shortcuts.CloseTab.matches(event) || (event.type == KeyEventType.KeyDown && event.key == Key.Escape)) ->
            done { app.settingsOpen = false }
        Shortcuts.CloseTab.matches(event) -> selected?.let { workspace.requestClose(it); true } ?: false
        Shortcuts.GoToAnything.matches(event) -> done { app.goToOpen = true }
        Shortcuts.NewConsole.matches(event) -> app.currentConnection?.let { workspace.openConsole(it); true } ?: false
        Shortcuts.Explorer.matches(event) -> done { app.toggleTool(LeftTool.Explorer) }
        Shortcuts.History.matches(event) -> done { app.toggleTool(LeftTool.History) }
        Shortcuts.Agent.matches(event) -> done { app.agentOpen = !app.agentOpen }
        Shortcuts.AddToChat.matches(event) ->
            done {
                val mentioned = (selected as? ConsoleTab)?.mentionInChat() ?: (selected as? TableDataTab)?.mentionTable() ?: false
                if (!mentioned) app.agent.focusComposer()
            }
        Shortcuts.ToggleSidebar.matches(event) -> done { app.toggleSidebar() }
        Shortcuts.Queries.matches(event) -> done { app.toggleTool(LeftTool.Queries) }
        Shortcuts.Activity.matches(event) -> app.currentConnection?.takeUnless { ActivitySupport.of(it.type) == ActivitySupport.NotApplicable }?.let { workspace.openActivity(it); true } ?: false
        Shortcuts.Settings.matches(event) -> done { app.settingsOpen = true }
        Shortcuts.ShowShortcuts.matches(event) -> done { app.shortcutsOpen = true }
        selected is ConsoleTab -> handleConsoleShortcut(selected, event)
        selected is TableDataTab && Shortcuts.Reload.matches(event) -> done { selected.refresh() }
        selected is TableDataTab && Shortcuts.Save.matches(event) -> done { selected.submit() }
        selected is TableDataTab -> handleFilterShortcut(selected, event)
        else -> false
    }
}
