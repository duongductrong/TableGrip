package dev.tablegrip.app.tools

import dev.tablegrip.core.i18n.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tablegrip.app.AppState
import dev.tablegrip.core.tools.InstalledTool
import dev.tablegrip.core.tools.Tool
import dev.tablegrip.core.tools.ToolLibrary
import dev.tablegrip.core.tools.ToolPackage
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A download waiting for the user to agree to it; [then] runs once it is installed. */
class ToolDownloadRequest(val pkg: ToolPackage, val reason: String?, val then: () -> Unit)

/**
 * The client tools behind backup and restore: what's on this machine, what the user chose, and downloads. Nothing is
 * downloaded without the user confirming [pending] first, whatever the driver settings say.
 */
class ToolsViewModel(private val app: AppState, root: Path? = null) {
    val library: ToolLibrary = if (root != null) ToolLibrary(root, { app.settings.toolPaths }) else ToolLibrary(customPaths = { app.settings.toolPaths })

    /** Bumped whenever tools change on disk, so views read them again. */
    var revision by mutableIntStateOf(0)
        private set

    /** Bytes downloaded and total, by package id, while downloading. */
    val progress = mutableStateMapOf<String, Pair<Long, Long>>()
    val failures = mutableStateMapOf<String, String>()
    var pending by mutableStateOf<ToolDownloadRequest?>(null)
    private val jobs = mutableMapOf<String, Job>()

    suspend fun find(tool: Tool): List<InstalledTool> = withContext(Dispatchers.IO) { library.find(tool) }

    suspend fun best(tool: Tool, serverMajor: Int?): InstalledTool? = withContext(Dispatchers.IO) { library.best(tool, serverMajor) }

    /** Asks the user to download [pkg]; [reason] says what needs it, e.g. "Backing up Orders needs pg_dump 17 or newer." */
    fun requestDownload(pkg: ToolPackage, reason: String? = null, then: () -> Unit = {}) {
        pending = ToolDownloadRequest(pkg, reason, then)
    }

    fun confirmDownload() {
        val request = pending ?: return
        pending = null
        download(request.pkg, request.then)
    }

    private fun download(pkg: ToolPackage, then: () -> Unit) {
        if (pkg.id in jobs) return
        failures.remove(pkg.id)
        progress[pkg.id] = 0L to pkg.downloadBytes
        jobs[pkg.id] =
            app.scope.launch {
                try {
                    var shown = -1L
                    library.install(pkg) { done, total ->
                        // Hundreds of MB in 256 KB reads: only tell the UI when the percentage moves.
                        val percent = if (total > 0) done * 100 / total else 0
                        if (percent != shown) {
                            shown = percent
                            app.scope.launch { if (pkg.id in progress) progress[pkg.id] = done to total }
                        }
                    }
                    app.status = tr("Installed {0} tools {1}", pkg.family.displayName, pkg.version)
                    revision++
                    then()
                } catch (e: CancellationException) {
                    app.status = tr("Download of {0} tools cancelled", pkg.family.displayName)
                } catch (e: Exception) {
                    failures[pkg.id] = e.message ?: e.toString()
                } finally {
                    jobs.remove(pkg.id)
                    progress.remove(pkg.id)
                }
            }
    }

    fun cancelDownload(pkg: ToolPackage) {
        jobs[pkg.id]?.cancel()
    }

    fun uninstall(pkg: ToolPackage) {
        app.scope.launch {
            runCatching { library.uninstall(pkg) }.onFailure { failures[pkg.id] = it.message ?: it.toString() }
            revision++
        }
    }

    /** Uses [path] for [tool] from now on, whatever else is installed. */
    fun choose(tool: Tool, path: Path) {
        app.updateSettings { it.copy(toolPaths = it.toolPaths + (tool.name to path.toString())) }
        revision++
    }

    /** Back to finding [tool] automatically. */
    fun forget(tool: Tool) {
        app.updateSettings { it.copy(toolPaths = it.toolPaths - tool.name) }
        revision++
    }

    fun refresh() {
        revision++
    }
}
