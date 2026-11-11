package dev.tablegrip.app.update

import dev.tablegrip.core.i18n.tr
import com.sun.jna.Library
import com.sun.jna.Native
import dev.tablegrip.core.settings.Settings
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile
import org.slf4j.LoggerFactory

/** Which releases the app is offered. */
enum class UpdateChannel(val label: String) {
    Stable(tr("Stable")),
    Beta(tr("Beta (pre-releases too)")),
    ;

    companion object {
        fun of(name: String): UpdateChannel = entries.firstOrNull { it.name == name } ?: Stable
    }
}

/** What the last check from Settings → Updates found. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    data object UpToDate : UpdateStatus

    /** A newer release; [version] is null where the updater doesn't say which (WinSparkle). */
    data class Available(val version: String?) : UpdateStatus

    /** Downloaded and verified: it installs when the app quits, or now with [Updater.quit] and `relaunch`. */
    data class Ready(val version: String?) : UpdateStatus

    data class Failed(val reason: String) : UpdateStatus

    companion object {
        /** From the codes TableGripUpdater.m's dg_updater_status returns. */
        internal fun of(code: Int, detail: String): UpdateStatus {
            val version = detail.ifBlank { null }
            return when (code) {
                1 -> Checking
                2 -> UpToDate
                3 -> Available(version)
                4 -> Ready(version)
                5 -> Failed(detail.ifBlank { tr("The check failed.") })
                else -> Idle
            }
        }
    }
}

/**
 * Keeps the app up to date: Sparkle on macOS, WinSparkle on Windows. On Linux, and in builds that weren't packaged (`:app:run`), it does
 * nothing and says why in [unavailableReason].
 */
interface Updater {
    /** Why this copy can't update itself, or null when it can. */
    val unavailableReason: String?

    /** When the last check finished, if there was one. */
    val lastCheck: Instant?

    /** Starts checking, as [settings] say. Blocks briefly: call it off the UI thread. */
    fun start(settings: Settings)

    /** Applies changed update settings. */
    fun configure(settings: Settings)

    /**
     * "Check for Updates…": the updater's own window opens at once with the check's progress, then offers the next
     * version with its release notes, or says there's nothing new. [status] follows along.
     */
    fun checkNow()

    /** What the last [check] found. */
    val status: UpdateStatus

    /** Checks without a window of its own; the result shows in [status]. On Windows, a found update still opens WinSparkle's window. */
    fun check()

    /** Downloads and installs the update [status] says is available, through the updater's window. */
    fun install()

    /**
     * Quits the app in a way that lets a downloaded update install, after the user agreed to quit; with [relaunch], an
     * update that's [UpdateStatus.Ready] installs right away and the app starts again. Returns false if the caller
     * should quit the usual way.
     */
    fun quit(relaunch: Boolean = false): Boolean

    /** True once [quit] started quitting: the system's quit request that follows must go through, not be asked again. */
    val quitting: Boolean

    /** Stops the updater as the app quits. */
    fun dispose() {}

    companion object {
        private val log = LoggerFactory.getLogger(Updater::class.java)

        /**
         * The updater for this copy of the app. [canQuit] says whether quitting now would lose nothing (Windows asks
         * before running the installer); [requestQuit] quits the usual way, asking about unsaved work.
         */
        fun create(canQuit: () -> Boolean = { true }, requestQuit: () -> Unit = {}): Updater {
            val os = System.getProperty("os.name").orEmpty()
            val launcher = System.getProperty("jpackage.app-path")
            return when {
                os.startsWith("Mac") -> {
                    launcher ?: return Unavailable(FromSource)
                    val shim = Path.of(launcher).parent.resolveSibling("Frameworks").resolve("libTableGripUpdater.dylib")
                    load(shim) { SparkleUpdater(Native.load(shim.toString(), SparkleLibrary::class.java)) }
                }
                os.startsWith("Windows") -> {
                    launcher ?: return Unavailable(FromSource)
                    val dll = System.getProperty("compose.application.resources.dir")?.let { Path.of(it, "WinSparkle.dll") } ?: return Unavailable(WithoutUpdater)
                    load(dll) { WinSparkleUpdater(Native.load(dll.toString(), WinSparkleLibrary::class.java), canQuit, requestQuit) }
                }
                else -> Unavailable(tr("Updates for {0} are downloaded from the TableGrip releases page.", os.ifBlank { tr("this system") }))
            }
        }

        private fun load(library: Path, create: () -> Updater): Updater {
            if (!library.isRegularFile()) return Unavailable(WithoutUpdater)
            return runCatching(create).getOrElse {
                log.warn("Couldn't load the updater from $library", it)
                Unavailable(tr("The updater couldn't be loaded."))
            }
        }

        private val FromSource = tr("This copy runs from source, not an installed app.")
        private val WithoutUpdater = tr("This build was made without the updater.")

        /**
         * The appcast for this OS and architecture. Windows has one with pre-releases and one without ([beta]), since
         * WinSparkle has no channels; macOS has one, and Sparkle picks the channel. `TABLEGRIP_UPDATE_FEED` points
         * elsewhere for testing; it must still be HTTPS unless it's on this machine, and every signature is still checked.
         */
        fun feedUrl(
            os: String = System.getProperty("os.name").orEmpty(),
            arch: String = System.getProperty("os.arch").orEmpty(),
            beta: Boolean = false,
            override: String? = System.getenv("TABLEGRIP_UPDATE_FEED"),
        ): String {
            override?.takeIf { isAllowedFeed(it) }?.let { return it }
            val target =
                when {
                    os.startsWith("Mac") -> if (arch == "aarch64") "macos-arm64" else "macos-x64"
                    os.startsWith("Windows") -> if (beta) "windows-x64-beta" else "windows-x64"
                    else -> "linux-x64"
                }
            return "$FeedBase/$target.xml"
        }

        fun isAllowedFeed(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return when (uri.scheme) {
                "https" -> !uri.host.isNullOrBlank()
                "http" -> uri.host == "localhost" || uri.host == "127.0.0.1"
                else -> false
            }
        }

        const val FeedBase = "https://raw.githubusercontent.com/duongductrong/TableGrip/master/appcast"
    }
}

private class Unavailable(override val unavailableReason: String) : Updater {
    override val lastCheck: Instant? = null
    override val quitting = false
    override val status: UpdateStatus = UpdateStatus.Idle

    override fun start(settings: Settings) {}

    override fun configure(settings: Settings) {}

    override fun checkNow() {}

    override fun check() {}

    override fun install() {}

    override fun quit(relaunch: Boolean) = false
}

/** The C functions of app/src/macos/updater/TableGripUpdater.m. */
@Suppress("FunctionName")
internal interface SparkleLibrary : Library {
    fun dg_updater_start(feed: String, automaticChecks: Int, automaticDownloads: Int, beta: Int): Int

    fun dg_updater_configure(feed: String, automaticChecks: Int, automaticDownloads: Int, beta: Int)

    fun dg_updater_check()

    fun dg_updater_last_check(): Double

    fun dg_updater_probe()

    fun dg_updater_status(buffer: ByteArray, size: Int): Int

    fun dg_updater_install_now(): Int

    fun dg_updater_terminate()
}

private class SparkleUpdater(private val native: SparkleLibrary) : Updater {
    @Volatile private var started = false
    @Volatile private var failed = false
    @Volatile override var quitting = false
        private set

    override val unavailableReason: String?
        get() = if (failed) tr("The updater didn't start. See the log for why.") else null

    override val lastCheck: Instant?
        get() = native.dg_updater_last_check().takeIf { it > 0 }?.let { Instant.ofEpochMilli((it * 1000).toLong()) }

    override fun start(settings: Settings) {
        val ok = native.dg_updater_start(Updater.feedUrl(), settings.checkForUpdates.flag, settings.installUpdatesAutomatically.flag, settings.beta.flag) == 1
        started = ok
        failed = !ok
    }

    override fun configure(settings: Settings) {
        if (started) native.dg_updater_configure(Updater.feedUrl(), settings.checkForUpdates.flag, settings.installUpdatesAutomatically.flag, settings.beta.flag)
    }

    override fun checkNow() {
        if (started) native.dg_updater_check()
    }

    override val status: UpdateStatus
        get() {
            if (!started) return UpdateStatus.Idle
            val buffer = ByteArray(512)
            val code = native.dg_updater_status(buffer, buffer.size)
            return UpdateStatus.of(code, String(buffer, 0, buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size, Charsets.UTF_8))
        }

    override fun check() {
        if (started) native.dg_updater_probe()
    }

    // Sparkle's own window downloads, verifies and installs it.
    override fun install() = checkNow()

    override fun quit(relaunch: Boolean): Boolean {
        if (!started) return false
        quitting = true
        if (!(relaunch && native.dg_updater_install_now() == 1)) native.dg_updater_terminate()
        return true
    }
}

internal val Boolean.flag: Int
    get() = if (this) 1 else 0

internal val Settings.beta: Boolean
    get() = UpdateChannel.of(updateChannel) == UpdateChannel.Beta
