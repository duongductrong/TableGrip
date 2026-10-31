package dev.datagrok.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.datagrok.app.console.ConsoleTab
import dev.datagrok.app.console.handleConsoleShortcut
import dev.datagrok.app.search.DoubleShiftDetector
import dev.datagrok.app.table.TableDataTab
import dev.datagrok.app.table.handleFilterShortcut
import dev.datagrok.app.ui.DatagrokTheme
import dev.datagrok.app.ui.Shortcuts
import dev.datagrok.core.activity.ActivitySupport
import dev.datagrok.app.ui.MainScreen
import dev.datagrok.app.ui.PinchZoom
import dev.datagrok.app.ui.TitleBarView
import java.awt.Desktop
import org.jetbrains.jewel.window.DecoratedWindow

fun main() = application {
    val appState = remember {
        AppState().also {
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
    DatagrokTheme(isDark = appState.theme.isDark()) {
        DecoratedWindow(
            onCloseRequest = { appState.requestExit(::exitApplication) },
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
            title = "Datagrok",
            onPreviewKeyEvent = {
                if (doubleShift.onKey(it.key, it.type)) appState.goToOpen = true
                handleTabSwitch(appState, it)
            },
            onKeyEvent = { handleWindowShortcut(appState, it) },
        ) {
            DisposableEffect(window) {
                val stop = PinchZoom.install(window.rootPane)
                onDispose { stop?.invoke() }
            }
            // Data files dropped anywhere on the window open as connections.
            DisposableEffect(window) {
                val previous = window.contentPane.dropTarget
                window.contentPane.dropTarget = DataFileDropTarget(appState)
                onDispose { window.contentPane.dropTarget = previous }
            }
            TitleBarView(appState)
            MainScreen(appState)
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
        selected is ConsoleTab && Shortcuts.AddToChat.matches(event) -> selected.mentionInChat()
        selected is TableDataTab && Shortcuts.AddToChat.matches(event) -> selected.mentionTable()
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

/** Accepts files dragged from the file manager and opens the data files among them. */
private class DataFileDropTarget(private val app: AppState) : java.awt.dnd.DropTarget() {
    override fun dragEnter(event: java.awt.dnd.DropTargetDragEvent) {
        if (event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) event.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY) else event.rejectDrag()
    }

    @Synchronized
    override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
        if (!event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) return event.rejectDrop()
        event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
        val files = runCatching { (event.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<java.io.File>() }.getOrDefault(emptyList())
        event.dropComplete(files.isNotEmpty())
        // The drop arrives on the AWT thread, which is also Compose's UI thread on desktop.
        app.connections.openDataFiles(files.filter { it.isFile })
    }
}
