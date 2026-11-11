package dev.tablegrip.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Setting up `tablegrip mcp` on this computer: the `tablegrip` command for the terminal, and the MCP entry in AI apps'
 * config files. UI-free so it can be tested; Settings › MCP drives it.
 */
class McpSetup(
    /** The installed app's executable; null when running from source. */
    val launcher: Path? = System.getProperty("jpackage.app-path")?.let(Path::of)?.takeIf { it.exists() },
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val os: Os = Os.current,
    private val env: (String) -> String? = System::getenv,
) {
    enum class Os {
        Mac,
        Windows,
        Linux;

        companion object {
            val current: Os
                get() = System.getProperty("os.name").let { name ->
                    when {
                        name.startsWith("Mac") -> Mac
                        name.startsWith("Windows") -> Windows
                        else -> Linux
                    }
                }
        }
    }

    /** AI apps whose config TableGrip can write. */
    enum class Client(val displayName: String, internal val serversKey: String) {
        ClaudeDesktop("Claude Desktop", "mcpServers"),
        Cursor("Cursor", "mcpServers"),
        VsCode("VS Code", "servers"),
        Windsurf("Windsurf", "mcpServers"),
    }

    // region The tablegrip command

    val commandName: String
        get() = if (os == Os.Windows) "tablegrip.cmd" else "tablegrip"

    /** Folders the command can go in, best first: ones usually on PATH and writable without admin rights. */
    fun commandFolders(): List<Path> =
        when (os) {
            Os.Mac -> listOf(Path.of("/usr/local/bin"), Path.of("/opt/homebrew/bin"), home.resolve(".local/bin"))
            Os.Linux -> listOf(home.resolve(".local/bin"), Path.of("/usr/local/bin"))
            // On every user's PATH by default, and theirs to write.
            Os.Windows -> listOfNotNull(env("LOCALAPPDATA")?.let { Path.of(it, "Microsoft", "WindowsApps") }, home.resolve("AppData/Local/Microsoft/WindowsApps"))
        }.distinct()

    /** The installed `tablegrip` command, if one of [commandFolders] has TableGrip's. */
    fun installedCommand(): Path? =
        commandFolders().map { it.resolve(commandName) }.firstOrNull { file -> file.isRegularFile() && runCatching { Marker in file.readText() }.getOrDefault(false) }

    /** Whether the installed command points at this app (it may point at an older install that moved). */
    fun commandIsCurrent(): Boolean {
        val launcher = launcher ?: return false
        val installed = installedCommand() ?: return false
        return runCatching { installed.readText() == script(launcher) }.getOrDefault(false)
    }

    /** The command's text: it runs the installed app with the arguments given. */
    fun script(launcher: Path): String =
        if (os == Os.Windows) "@echo off\r\nrem $Marker. Runs the installed app; `tablegrip mcp` starts its MCP server.\r\n\"$launcher\" %*\r\n"
        else "#!/bin/sh\n# $Marker. Runs the installed app; `tablegrip mcp` starts its MCP server.\nexec \"$launcher\" \"\$@\"\n"

    /**
     * Writes the command into the first folder of [commandFolders] it may write to, replacing an older one of
     * TableGrip's, never a file of someone else's. Returns where it went.
     */
    fun installCommand(): Path {
        val launcher = launcher ?: throw IllegalStateException("Only the installed app can add the tablegrip command; from source, use scripts/tablegrip.")
        val target =
            commandFolders().firstNotNullOfOrNull { folder ->
                val file = folder.resolve(commandName)
                val ours = !file.exists() || runCatching { Marker in file.readText() }.getOrDefault(false)
                val writable = if (folder.isDirectory()) Files.isWritable(folder) else folder.startsWith(home)
                file.takeIf { ours && writable }
            } ?: throw IllegalStateException("No folder on the PATH can be written to: ${commandFolders().joinToString()}.")
        target.parent.createDirectories()
        val temp = Files.createTempFile(target.parent, ".tablegrip", ".tmp")
        temp.writeText(script(launcher))
        if (os != Os.Windows) temp.toFile().setExecutable(true, false)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    /** Removes the command, if it is TableGrip's. */
    fun removeCommand(): Path? = installedCommand()?.also(Files::delete)

    /** Whether [folder] is on [path] (the login shell's PATH, which GUI apps don't inherit on macOS). */
    fun isOnPath(folder: Path, path: String): Boolean =
        path.split(File.pathSeparatorChar).any { it.isNotBlank() && runCatching { Path.of(it).normalize() == folder.normalize() }.getOrDefault(false) }

    // endregion

    // region AI apps

    /** What an app runs to start the server: the `tablegrip` command if installed (except on Windows, where apps need an .exe), else the app itself. */
    fun serverCommand(): Path? =
        if (os == Os.Windows) launcher else installedCommand() ?: launcher

    fun configFile(client: Client): Path =
        when (client) {
            Client.ClaudeDesktop -> when (os) {
                Os.Mac -> home.resolve("Library/Application Support/Claude/claude_desktop_config.json")
                Os.Windows -> appData().resolve("Claude/claude_desktop_config.json")
                Os.Linux -> home.resolve(".config/Claude/claude_desktop_config.json")
            }
            Client.Cursor -> home.resolve(".cursor/mcp.json")
            Client.VsCode -> when (os) {
                Os.Mac -> home.resolve("Library/Application Support/Code/User/mcp.json")
                Os.Windows -> appData().resolve("Code/User/mcp.json")
                Os.Linux -> home.resolve(".config/Code/User/mcp.json")
            }
            Client.Windsurf -> home.resolve(".codeium/windsurf/mcp_config.json")
        }

    private fun appData(): Path = env("APPDATA")?.let(Path::of) ?: home.resolve("AppData/Roaming")

    /** The entry TableGrip adds, as it will appear under the app's servers key. */
    fun entry(client: Client, command: Path): JsonObject =
        buildJsonObject {
            if (client == Client.VsCode) put("type", "stdio")
            put("command", command.toString())
            put("args", JsonArray(listOf(JsonPrimitive("mcp"))))
        }

    /** The snippet to paste by hand, for apps TableGrip doesn't know. */
    fun snippet(client: Client = Client.ClaudeDesktop, command: Path? = serverCommand()): String {
        val entry = entry(client, command ?: Path.of("tablegrip"))
        return Pretty.encodeToString(JsonElement.serializer(), buildJsonObject { put(client.serversKey, buildJsonObject { put(ServerName, entry) }) })
    }

    /** For Claude Code, which keeps its config to itself: the command to run in a terminal. */
    fun claudeCodeCommand(): String = "claude mcp add --scope user $ServerName -- ${if (os == Os.Windows) "\"${serverCommand() ?: "tablegrip"}\"" else "tablegrip"} mcp"

    /** Whether [client]'s config already has a `tablegrip` server. */
    fun isConfigured(client: Client): Boolean =
        runCatching {
            val file = configFile(client)
            file.isRegularFile() && (Lenient.parseToJsonElement(file.readText()) as? JsonObject)?.get(client.serversKey)?.let { it as? JsonObject }?.containsKey(ServerName) == true
        }.getOrDefault(false)

    /**
     * Adds or updates the `tablegrip` server in [client]'s config, keeping everything else, after copying the file to
     * `<name>.bak`. Refuses to touch a file it can't parse. Returns the file written.
     */
    fun configure(client: Client, command: Path = serverCommand() ?: throw IllegalStateException("Only the installed app can be added to AI apps; from source, use scripts/tablegrip.")): Path {
        val file = configFile(client)
        val existing =
            if (file.isRegularFile()) {
                val text = file.readText()
                if (text.isBlank()) JsonObject(emptyMap())
                else runCatching { Lenient.parseToJsonElement(text) }.getOrNull() as? JsonObject
                    ?: throw IllegalStateException("$file isn't a JSON object TableGrip can read, so it was left alone. Add the entry by hand.")
            } else {
                JsonObject(emptyMap())
            }
        val servers = (existing[client.serversKey] as? JsonObject).orEmpty() + (ServerName to entry(client, command))
        val updated = JsonObject(existing + (client.serversKey to JsonObject(servers)))
        file.parent.createDirectories()
        if (file.isRegularFile()) Files.copy(file, file.resolveSibling(file.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
        val temp = Files.createTempFile(file.parent, ".tablegrip", ".tmp")
        temp.writeText(Pretty.encodeToString(JsonElement.serializer(), updated) + "\n")
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    // endregion

    companion object {
        const val ServerName = "tablegrip"
        internal const val Marker = "TableGrip command line"

        @OptIn(ExperimentalSerializationApi::class)
        private val Pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

        @OptIn(ExperimentalSerializationApi::class)
        private val Lenient = Json { allowComments = true; allowTrailingComma = true; isLenient = true }
    }
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
