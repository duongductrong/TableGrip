package dev.datagrok.app.connections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.drivers.DriverLibrary
import dev.datagrok.core.drivers.DriverNotInstalledException
import dev.datagrok.core.drivers.DriverResolution
import dev.datagrok.core.drivers.LicenseTier
import dev.datagrok.core.settings.Settings
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

sealed interface DownloadState {
    data class Running(val done: Long, val total: Long) : DownloadState

    data class Failed(val message: String) : DownloadState
}

/**
 * The [DriverLibrary] as the UI sees it: what each driver needs, and downloads in progress. Downloads outlive the dialog
 * that started them. Must be used from the UI thread.
 */
class DriverDownloads(private val scope: CoroutineScope, val library: DriverLibrary, private val settings: () -> Settings) {
    private val states = mutableStateMapOf<DriverId, DownloadState>()
    private val jobs = mutableMapOf<DriverId, Deferred<Unit>>()
    private val resolutions = mutableMapOf<DriverId, DriverResolution>()

    /** Follows the library, so composables reading [resolve] update when a driver is installed or its jars change. */
    private var revision by mutableIntStateOf(0)

    init {
        scope.launch {
            library.revision.collect {
                resolutions.clear()
                revision = it
            }
        }
    }

    fun state(driver: DriverId): DownloadState? = states[driver]

    fun resolve(driver: DriverId): DriverResolution {
        @Suppress("UNUSED_EXPRESSION") revision
        return resolutions.getOrPut(driver) { library.resolve(driver) }
    }

    /** Like [resolve], but a [DriverId.Generic] profile's driver is its own jars. */
    fun resolve(config: ConnectionConfig): DriverResolution {
        if (config.effectiveDriver != DriverId.Generic) return resolve(config.effectiveDriver)
        @Suppress("UNUSED_EXPRESSION") revision
        return library.resolve(config)
    }

    /** Starts downloading [driver], or joins the download already running. */
    fun download(driver: DriverId): Deferred<Unit> =
        jobs.getOrPut(driver) {
            states[driver] = DownloadState.Running(0, library.locked(driver)?.size ?: 0)
            scope.async {
                try {
                    var shownPercent = -1L
                    library.install(driver) { done, total ->
                        // Progress arrives per 64 KB; a state write per percent is plenty.
                        val percent = if (total == 0L) 100 else done * 100 / total
                        if (percent != shownPercent) {
                            shownPercent = percent
                            scope.launch { if (driver in jobs) states[driver] = DownloadState.Running(done, total) }
                        }
                    }
                    states.remove(driver)
                    Unit
                } catch (e: CancellationException) {
                    states.remove(driver)
                    throw e
                } catch (e: Throwable) {
                    log.warn("Failed to download {}", driver, e)
                    states[driver] = DownloadState.Failed(e.message ?: e.toString())
                    throw e
                } finally {
                    jobs.remove(driver)
                }
            }
        }

    /**
     * Makes [driver] usable before connecting: waits for its download if one is running, starts one if settings allow
     * it without asking, and otherwise throws [DriverNotInstalledException] so the caller can ask.
     */
    suspend fun ensure(config: ConnectionConfig) {
        val driver = config.effectiveDriver
        if (driver == DriverId.Generic) {
            if (library.resolve(config) !is DriverResolution.Ready) throw DriverNotInstalledException(driver)
            return
        }
        jobs[driver]?.let { return it.await() }
        when (val resolution = library.resolve(driver)) {
            is DriverResolution.Ready -> Unit
            is DriverResolution.Download ->
                if (settings().autoDownloadDrivers && resolution.locked.tier == LicenseTier.Permissive) download(driver).await()
                else throw DriverNotInstalledException(driver)
            is DriverResolution.BringYourOwn -> throw DriverNotInstalledException(driver)
        }
    }

    suspend fun useJars(driver: DriverId, jars: List<Path>) {
        library.useJars(driver, jars)
        states.remove(driver)
    }

    suspend fun forgetJars(driver: DriverId) = library.forgetJars(driver)

    suspend fun importFrom(folder: Path): List<DriverId> = library.importFrom(folder).onEach { states.remove(it) }

    suspend fun uninstall(driver: DriverId) {
        library.uninstall(driver)
        states.remove(driver)
    }

    suspend fun removeOldVersions(driver: DriverId): List<String> = library.removeOldVersions(driver)

    /** Re-reads the library, e.g. after files changed on disk behind Datagrok's back. */
    fun refresh() {
        resolutions.clear()
        revision++
    }

    private companion object {
        val log = LoggerFactory.getLogger(DriverDownloads::class.java)
    }
}
