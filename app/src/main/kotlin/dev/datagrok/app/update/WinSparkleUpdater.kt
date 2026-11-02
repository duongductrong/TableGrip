package dev.datagrok.app.update

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.WString
import dev.datagrok.core.settings.Settings
import java.awt.EventQueue
import java.time.Instant
import org.slf4j.LoggerFactory

/** The parts of WinSparkle's C API (winsparkle.h, 0.9) the app uses. */
@Suppress("FunctionName")
internal interface WinSparkleLibrary : Library {
    fun win_sparkle_set_appcast_url(url: String)

    /** 1 if [key] is a valid base64 Ed25519 public key; WinSparkle then requires every update to be signed with it. */
    fun win_sparkle_set_eddsa_public_key(key: String): Int

    fun win_sparkle_set_app_details(company: WString, app: WString, version: WString)

    /** What `sparkle:version` in the feed is compared with. */
    fun win_sparkle_set_app_build_version(build: WString)

    fun win_sparkle_set_automatic_check_for_updates(state: Int)

    fun win_sparkle_set_update_check_interval(seconds: Int)

    /** Seconds since 1970 (time_t, 64-bit on x64), or -1 if there hasn't been a check. */
    fun win_sparkle_get_last_check_time(): Long

    fun win_sparkle_set_can_shutdown_callback(callback: CanShutdown)

    fun win_sparkle_set_shutdown_request_callback(callback: ShutdownRequest)

    fun win_sparkle_init()

    fun win_sparkle_cleanup()

    fun win_sparkle_check_update_with_ui()

    fun interface CanShutdown : Callback {
        fun invoke(): Int
    }

    fun interface ShutdownRequest : Callback {
        fun invoke()
    }
}

/**
 * WinSparkle downloads the MSI, checks its Ed25519 signature, asks whether the app can quit, runs the installer and
 * then asks the app to quit. It can't install silently: when it finds an update it always shows its dialog, so
 * "install automatically" only means checking automatically here.
 */
internal class WinSparkleUpdater(
    private val native: WinSparkleLibrary,
    private val canQuit: () -> Boolean,
    private val requestQuit: () -> Unit,
) : Updater {
    @Volatile private var started = false
    @Volatile private var failed: String? = null

    // Held here: JNA callbacks only stay alive while something references them.
    private val canShutdown = WinSparkleLibrary.CanShutdown { if (onUiThread(canQuit)) 1 else 0 }
    private val shutdownRequest = WinSparkleLibrary.ShutdownRequest { EventQueue.invokeLater(requestQuit) }

    override val unavailableReason: String?
        get() = failed

    override val lastCheck: Instant?
        get() = if (started) native.win_sparkle_get_last_check_time().takeIf { it > 0 }?.let(Instant::ofEpochSecond) else null

    override val quitting = false

    override fun start(settings: Settings) {
        if (native.win_sparkle_set_eddsa_public_key(AppVersion.updatePublicKey) != 1) {
            failed = "This build has no valid update signing key."
            log.warn("Not starting WinSparkle: datagrok.sparklePublicKey isn't a valid Ed25519 key")
            return
        }
        native.win_sparkle_set_appcast_url(Updater.feedUrl(beta = settings.beta))
        val version = AppVersion.current
        native.win_sparkle_set_app_details(WString("Datagrok"), WString("Datagrok"), WString(version.version))
        native.win_sparkle_set_app_build_version(WString(version.build.toString()))
        native.win_sparkle_set_can_shutdown_callback(canShutdown)
        native.win_sparkle_set_shutdown_request_callback(shutdownRequest)
        native.win_sparkle_set_automatic_check_for_updates(settings.checkForUpdates.flag)
        native.win_sparkle_set_update_check_interval(DaySeconds)
        native.win_sparkle_init()
        started = true
    }

    override fun configure(settings: Settings) {
        if (!started) return
        native.win_sparkle_set_appcast_url(Updater.feedUrl(beta = settings.beta))
        native.win_sparkle_set_automatic_check_for_updates(settings.checkForUpdates.flag)
    }

    override fun checkNow() {
        if (started) native.win_sparkle_check_update_with_ui()
    }

    override fun quit() = false

    override fun dispose() {
        if (started) native.win_sparkle_cleanup()
    }

    /** WinSparkle calls from its own thread; the app's state is read on the UI thread. */
    private fun onUiThread(check: () -> Boolean): Boolean {
        var result = false
        runCatching { EventQueue.invokeAndWait { result = check() } }.onFailure { log.warn("Couldn't ask whether Datagrok can quit", it) }
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(WinSparkleUpdater::class.java)
        private const val DaySeconds = 24 * 60 * 60
    }
}
