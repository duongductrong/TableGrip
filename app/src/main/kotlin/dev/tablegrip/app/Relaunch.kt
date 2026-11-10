package dev.tablegrip.app

import java.nio.file.Path
import org.slf4j.LoggerFactory

/** Starts TableGrip again once this copy has quit, the way it was started. */
object Relaunch {
    private val log = LoggerFactory.getLogger(Relaunch::class.java)

    /** Arranges the new start for when the JVM exits; call it just before quitting. */
    fun afterExit() {
        val command = command() ?: return log.warn("Can't tell how TableGrip was started; not starting it again")
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { ProcessBuilder(command).inheritIO().start() }.onFailure { log.warn("Couldn't start TableGrip again", it) }
            },
        )
    }

    /**
     * An installed app is started by its launcher, which jpackage names in `jpackage.app-path`; on macOS through
     * `open -n` on the bundle, so the Dock shows it as the app. `:app:run` repeats this JVM's own command line.
     */
    private fun command(): List<String>? {
        val launcher = System.getProperty("jpackage.app-path")
        if (launcher != null) {
            val bundle = Path.of(launcher).toAbsolutePath().toString().substringBefore(".app/Contents/", missingDelimiterValue = "")
            return if (bundle.isNotEmpty()) listOf("/usr/bin/open", "-n", "$bundle.app") else listOf(launcher)
        }
        val info = ProcessHandle.current().info()
        val java = info.command().orElse(null) ?: return null
        return listOf(java) + info.arguments().orElse(emptyArray())
    }
}
