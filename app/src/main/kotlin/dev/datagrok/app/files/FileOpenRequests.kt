package dev.datagrok.app.files

import java.awt.Desktop
import java.awt.EventQueue
import java.io.File

/**
 * Files the OS asks Datagrok to open: on the command line (Windows and Linux file associations pass them as arguments)
 * and, on macOS, as Apple Events from Finder (double-click, Open With, a file dropped on the Dock icon). [install] runs
 * before the UI starts so the file that launched the app isn't missed; files that arrive before [listen] are kept.
 */
object FileOpenRequests {
    private val pending = mutableListOf<File>()
    private var listener: ((List<File>) -> Unit)? = null

    /** Whether files came with the launch itself, as arguments; macOS's arrive a moment later as events instead. */
    var launchedWithFiles = false
        private set

    fun install(args: Array<String>) {
        val files = args.map(::File).filter { it.isFile }
        launchedWithFiles = files.isNotEmpty()
        deliver(files)
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.APP_OPEN_FILE) }
        desktop?.setOpenFileHandler { event -> deliver(event.files.filter { it.isFile }) }
    }

    /** Hands [onFiles] what's pending, then every later request, on the UI thread; null stops. */
    fun listen(onFiles: ((List<File>) -> Unit)?) {
        val waiting =
            synchronized(this) {
                listener = onFiles
                pending.toList().also { pending.clear() }
            }
        if (onFiles != null && waiting.isNotEmpty()) EventQueue.invokeLater { onFiles(waiting) }
    }

    private fun deliver(files: List<File>) {
        if (files.isEmpty()) return
        val target =
            synchronized(this) {
                listener ?: run {
                    pending += files
                    null
                }
            }
        // Apple Events come in on the AppKit thread; Compose state may only change on the UI thread.
        target?.let { EventQueue.invokeLater { it(files) } }
    }
}
