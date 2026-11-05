package dev.tablegrip.core.agent.cli

import dev.tablegrip.core.agent.AgentAdapter
import dev.tablegrip.core.agent.AgentDescriptor
import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentException
import dev.tablegrip.core.agent.AgentModel
import dev.tablegrip.core.agent.AgentRequest
import dev.tablegrip.core.agent.AgentSource
import dev.tablegrip.core.agent.AgentTransport
import dev.tablegrip.core.agent.toPrompt
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The CLI agents installed on this machine. */
class CliAgentSource(private val presets: List<CliAgentPreset> = CliAgentPreset.entries) : AgentSource {
    override suspend fun discover(rescan: Boolean): List<AgentAdapter> {
        ShellEnvironment.path(rescan)
        return presets.mapNotNull { preset -> ShellEnvironment.locate(preset.binary)?.let { CliAgentAdapter(preset, it) } }
    }
}

/**
 * Runs a CLI agent headless, one process per turn, and streams its stdout as the answer.
 *
 * The process runs the binary directly with the user's PATH (Node-based CLIs need it to find `node`), stdin closed and
 * the variables that turn a terminal tool into a quiet one. Stopping escalates: destroy first so the agent can clean up,
 * then a forced kill for the ones that ignore it.
 */
class CliAgentAdapter(private val preset: CliAgentPreset, private val executable: Path) : AgentAdapter {
    override val descriptor = AgentDescriptor(preset.id, preset.title, AgentTransport.Cli, executable.toString())

    override val choosesModel: Boolean
        get() = preset.choosesModel

    private val modelsLock = Mutex()
    private var listed: List<AgentModel>? = null

    /**
     * Asked once per adapter, so a rescan asks again (a CLI may have been logged in or updated since). A list that
     * fails or comes back empty isn't kept, so the next look tries again.
     */
    override suspend fun models(): List<AgentModel> {
        if (!preset.choosesModel) return emptyList()
        if (!preset.models.runs) return preset.models.parse("")
        return modelsLock.withLock {
            listed ?: listModels().also { if (it.isNotEmpty()) listed = it }
        }
    }

    private suspend fun listModels(): List<AgentModel> =
        withContext(Dispatchers.IO) {
            val process =
                runCatching { command(preset.models.arguments, workingDirectory = null).start() }.getOrNull() ?: return@withContext emptyList()
            try {
                val stderr = async { process.errorStream.bufferedReader().readBounded(STDERR_CAP) }
                val stdout = withTimeoutOrNull(MODELS_TIMEOUT_SECONDS * 1_000) {
                    async { process.inputStream.bufferedReader().readBounded(MODELS_CAP) }.await()
                }
                stderr.cancel()
                stdout?.let { runCatching { preset.models.parse(it) }.getOrDefault(emptyList()) }.orEmpty()
            } finally {
                withContext(NonCancellable) { terminate(process) }
            }
        }

    override fun send(request: AgentRequest): Flow<AgentEvent> =
        channelFlow {
            send(AgentEvent.Thinking)
            val process = withContext(Dispatchers.IO) { start(request) }
            var timedOut = false
            val watchdog = launch {
                delay(preset.timeoutSeconds * 1_000)
                timedOut = true
                terminate(process)
            }
            val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readBounded(STDERR_CAP) }
            val decoder = preset.output.decoder(preset.title)
            val reader = launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { stdout ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = stdout.read(buffer)
                        if (read < 0) break
                        decoder.consume(String(buffer, 0, read)).forEach { send(it) }
                    }
                }
            }
            try {
                // Cancelling the collector cancels this join; the finally then kills the process, which ends the reader.
                reader.join()
                // Stdout closing comes a moment before the exit; let it finish rather than kill it on its way out.
                withContext(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
            } finally {
                watchdog.cancel()
                withContext(Dispatchers.IO + NonCancellable) { terminate(process) }
            }

            if (timedOut) throw AgentException("No answer from ${preset.title} in ${preset.timeoutSeconds}s.")
            val status = process.exitValue()
            val errors = stderr.await()
            fun failed(): Nothing = throw AgentException(diagnostic(errors).ifEmpty { "${preset.title} exited with code $status." })
            val text =
                try {
                    decoder.finish(status, errors)
                } catch (_: CliFailure) {
                    failed()
                }
            when {
                // Several of these agents exit non-zero on a warning they also print: only an empty run is a failure.
                text.isEmpty() && status != 0 -> failed()
                text.isEmpty() -> throw AgentException("${preset.title} returned nothing.")
            }
            send(AgentEvent.Completed(text))
        }

    private suspend fun start(request: AgentRequest): Process {
        val directory = request.workingDirectory?.also { Files.createDirectories(it) }
        val builder = command(preset.arguments(request.toPrompt(), request.model), directory)
        return try {
            builder.start()
        } catch (e: Exception) {
            throw AgentException("Couldn't start ${preset.title} (${preset.binary}).", e)
        }
    }

    /** The binary with [arguments], stdin closed and the variables that turn a terminal tool into a quiet one. */
    private suspend fun command(arguments: List<String>, workingDirectory: Path?): ProcessBuilder {
        val builder =
            ProcessBuilder(listOf(executable.toString()) + arguments)
                .redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
        workingDirectory?.let { builder.directory(it.toFile()) }
        builder.environment().apply {
            put("PATH", ShellEnvironment.path())
            put("CI", "1")
            put("TERM", "dumb")
            put("NO_COLOR", "1")
            put("FORCE_COLOR", "0")
            put("PAGER", "cat")
            put("GIT_PAGER", "cat")
        }
        return builder
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        const val STDERR_CAP = 64 * 1024
        /** Codex's catalog carries each model's whole system prompt. */
        const val MODELS_CAP = 8 * 1024 * 1024
        /** Some list over the network; a slow one just leaves the model to be typed. */
        const val MODELS_TIMEOUT_SECONDS = 30L
        val Ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

        fun java.io.Reader.readBounded(cap: Int): String {
            val out = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                // Keep draining past the cap: a child blocked on a full stderr pipe never exits.
                if (out.length < cap) out.append(buffer, 0, minOf(read, cap - out.length))
            }
            return out.toString()
        }

        /**
         * One useful line out of a CLI's stderr. Not simply the first: some print an ASCII-art banner or their whole
         * usage text, so a usage dump is named for what it is and otherwise the last line naming a problem wins.
         */
        fun diagnostic(text: String): String {
            // Some CLIs colour their errors even with NO_COLOR set.
            val lines = text.replace(Ansi, "").lines().map { it.trim() }.filter { line -> line.isNotEmpty() && line.count { it.isLetterOrDigit() } * 2 >= line.length }
            if (lines.isEmpty()) return ""
            if (lines.any { it.startsWith("Usage:", ignoreCase = true) || it == "Commands:" || it == "Options:" }) {
                return "The agent rejected its command line — its CLI may be out of date."
            }
            val markers = listOf("error", "unknown", "invalid", "not found", "no such", "failed", "missing", "denied", "unauthorized", "forbidden", "expired", "login")
            val named = lines.lastOrNull { line -> markers.any { line.contains(it, ignoreCase = true) } }
            return (named ?: lines.last()).take(240)
        }
    }
}
