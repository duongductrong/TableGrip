package dev.tablegrip.mcp

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.Properties
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * `tablegrip mcp`: the MCP server on stdin/stdout. Called by the app's `main` before anything touches AWT or Compose,
 * so no window or Dock icon appears and nothing but protocol messages reaches stdout.
 */
object McpMain {
    /** Runs the server until the client closes stdin; returns the exit code. */
    fun run(args: List<String>): Int {
        if (args.any { it == "-h" || it == "--help" }) {
            System.out.print(McpOptions.Usage)
            return 0
        }
        val options =
            try {
                McpOptions.parse(args)
            } catch (e: IllegalArgumentException) {
                System.err.println(e.message)
                return 2
            }
        options.home?.let { System.setProperty("tablegrip.home", it) }
        System.setProperty("java.awt.headless", "true")
        System.setProperty("apple.awt.UIElement", "true")
        // The protocol owns stdout. Anything else that prints (a driver, a library) goes to stderr instead.
        val protocolOut = FileOutputStream(FileDescriptor.out)
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
        if (System.getProperty("logback.configurationFile") == null) System.setProperty("logback.configurationFile", "logback-mcp.xml")
        return serve(options, FileInputStream(FileDescriptor.`in`), protocolOut)
    }

    /** Serves one client over [input] and [output]; tests call this with pipes. */
    fun serve(options: McpOptions, input: InputStream, output: OutputStream, workspace: Workspace = Workspace(options)): Int {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            runBlocking {
                val peer = JsonRpcPeer(input, output, scope)
                val session = McpSession(workspace, peer)
                val server = McpServer(session, version())
                workspace.startReaper(scope)
                peer.serve(server)
            }
            0
        } finally {
            scope.cancel()
            workspace.close()
        }
    }

    /** The app's version from the resource the build writes, or `dev` when run from an IDE. */
    fun version(): String =
        runCatching {
            McpMain::class.java.classLoader.getResourceAsStream("tablegrip-version.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull() ?: "dev"

    @JvmStatic
    fun main(args: Array<String>) {
        val arguments = args.toList().let { if (it.firstOrNull() == "mcp") it.drop(1) else it }
        exitProcess(run(arguments))
    }
}
