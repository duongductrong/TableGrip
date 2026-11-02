package dev.datagrok.app.update

import com.sun.jna.Library
import com.sun.jna.Native
import dev.datagrok.core.settings.Settings
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile
import org.slf4j.LoggerFactory

/** Which releases the app is offered. */
enum class UpdateChannel(val label: String) {
    Stable("Stable"),
    Beta("Beta (pre-releases too)"),
    ;

    companion object {
        fun of(name: String): UpdateChannel = entries.firstOrNull { it.name == name } ?: Stable
    }
}

/**
 * Keeps the app up to date: Sparkle on macOS. Elsewhere, and in builds that weren't packaged (`:app:run`), it does
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

    /** "Check for Updates…": the updater's own window shows the result, also when there's nothing new. */
    fun checkNow()

    /**
     * Quits the app in a way that lets a downloaded update install, after the user agreed to quit. Returns false if the
     * caller should quit the usual way.
     */
    fun quit(): Boolean

    /** True once [quit] started quitting: the system's quit request that follows must go through, not be asked again. */
    val quitting: Boolean

    companion object {
        private val log = LoggerFactory.getLogger(Updater::class.java)

        fun create(): Updater {
            val os = System.getProperty("os.name").orEmpty()
            if (!os.startsWith("Mac")) return Unavailable("Updates for ${os.ifBlank { "this system" }} are downloaded from the Datagrok releases page.")
            val launcher = System.getProperty("jpackage.app-path") ?: return Unavailable("This copy runs from source, not an installed app.")
            val shim = Path.of(launcher).parent.resolveSibling("Frameworks").resolve("libDatagrokUpdater.dylib")
            if (!shim.isRegularFile()) return Unavailable("This build was made without the updater.")
            return runCatching { SparkleUpdater(Native.load(shim.toString(), SparkleLibrary::class.java)) }
                .getOrElse {
                    log.warn("Couldn't load the updater from $shim", it)
                    Unavailable("The updater couldn't be loaded.")
                }
        }

        /**
         * The appcast for this OS and architecture. `DATAGROK_UPDATE_FEED` points elsewhere for testing; it must still
         * be HTTPS unless it's on this machine, and Sparkle still checks every signature.
         */
        fun feedUrl(os: String = System.getProperty("os.name").orEmpty(), arch: String = System.getProperty("os.arch").orEmpty(), override: String? = System.getenv("DATAGROK_UPDATE_FEED")): String {
            override?.takeIf { isAllowedFeed(it) }?.let { return it }
            val target =
                when {
                    os.startsWith("Mac") -> if (arch == "aarch64") "macos-arm64" else "macos-x64"
                    os.startsWith("Windows") -> "windows-x64"
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

        const val FeedBase = "https://raw.githubusercontent.com/duongductrong/datagrok/master/appcast"
    }
}

private class Unavailable(override val unavailableReason: String) : Updater {
    override val lastCheck: Instant? = null
    override val quitting = false

    override fun start(settings: Settings) {}

    override fun configure(settings: Settings) {}

    override fun checkNow() {}

    override fun quit() = false
}

/** The C functions of app/src/macos/updater/DatagrokUpdater.m. */
@Suppress("FunctionName")
internal interface SparkleLibrary : Library {
    fun dg_updater_start(feed: String, automaticChecks: Int, automaticDownloads: Int, beta: Int): Int

    fun dg_updater_configure(feed: String, automaticChecks: Int, automaticDownloads: Int, beta: Int)

    fun dg_updater_check()

    fun dg_updater_last_check(): Double

    fun dg_updater_terminate()
}

private class SparkleUpdater(private val native: SparkleLibrary) : Updater {
    @Volatile private var started = false
    @Volatile private var failed = false
    @Volatile override var quitting = false
        private set

    override val unavailableReason: String?
        get() = if (failed) "The updater didn't start. See the log for why." else null

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

    override fun quit(): Boolean {
        if (!started) return false
        quitting = true
        native.dg_updater_terminate()
        return true
    }

    private val Boolean.flag: Int
        get() = if (this) 1 else 0

    private val Settings.beta: Boolean
        get() = UpdateChannel.of(updateChannel) == UpdateChannel.Beta
}
