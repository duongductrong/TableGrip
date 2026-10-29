package dev.datagrok.core.agent.cli

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The user's own PATH, which is where their CLI agents live.
 *
 * An app launched from Finder or the Dock inherits launchd's PATH (`/usr/bin:/bin`), while these tools sit in Homebrew,
 * `~/.local/bin`, an nvm shim… A login shell alone is not enough either: installers append their PATH lines to `.zshrc`,
 * which only *interactive* shells source. So the PATH is read once from an interactive login shell and cached; agents
 * then run directly with it, never through that shell, whose rc files may print greetings into stdout.
 */
object ShellEnvironment {
    /** Brackets the value, so whatever the rc files print around it is discarded. */
    private const val MARKER = "__DATAGROK_PATH__"
    /** An rc file that blocks on the network or a prompt must not hang discovery. */
    private const val TIMEOUT_SECONDS = 5L

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val mutex = Mutex()
    private var cached: String? = null

    suspend fun path(rescan: Boolean = false): String =
        mutex.withLock {
            if (rescan) cached = null
            cached ?: withContext(Dispatchers.IO) { read() }.also { cached = it }
        }

    /** Where [binary] lives on the user's PATH, or null when it isn't installed. */
    suspend fun locate(binary: String, rescan: Boolean = false): Path? {
        val names = if (isWindows) listOf("$binary.exe", "$binary.cmd", "$binary.bat", binary) else listOf(binary)
        return path(rescan).split(File.pathSeparatorChar).asSequence()
            .filter { it.isNotBlank() }
            .flatMap { directory -> names.asSequence().map { Path.of(directory, it) } }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private fun read(): String {
        val inherited = System.getenv("PATH").orEmpty()
        if (isWindows) return inherited
        val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/zsh"
        val fromShell =
            runCatching {
                val process =
                    ProcessBuilder(shell, "-i", "-l", "-c", "printf '%s%s' '$MARKER' \"\$PATH\"")
                        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                // Read on another thread: an rc file that prints more than the pipe holds blocks until someone reads,
                // and one that never exits must still be cut off by the timeout.
                var output = ""
                val reader = thread(isDaemon = true) { output = process.inputStream.bufferedReader().readText() }
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
                reader.join(1_000)
                output.substringAfterLast(MARKER, "").trim()
            }.getOrDefault("")
        return listOf(fromShell, inherited).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
    }
}
