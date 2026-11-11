package dev.tablegrip.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpSetupTest {
    private val home: Path = Files.createTempDirectory("tablegrip-setup")
    private val launcher: Path = home.resolve("Applications/TableGrip.app/Contents/MacOS/TableGrip").also { it.parent.createDirectories(); it.writeText("") }
    private val setup = McpSetup(launcher, home, McpSetup.Os.Linux) { null }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `the tablegrip command runs the installed app`() {
        assertNull(setup.installedCommand())
        val command = setup.installCommand()
        assertEquals(home.resolve(".local/bin/tablegrip"), command)
        assertTrue(command.isExecutable())
        assertTrue("exec \"$launcher\" \"\$@\"" in command.readText())
        assertEquals(command, setup.installedCommand())
        assertTrue(setup.commandIsCurrent())
        assertTrue(setup.isOnPath(home.resolve(".local/bin"), "/usr/bin${File.pathSeparator}${home.resolve(".local/bin")}"))
        assertEquals(command, setup.removeCommand())
        assertFalse(command.exists())
    }

    @Test
    fun `someone else's tablegrip is never replaced`() {
        val theirs = home.resolve(".local/bin/tablegrip").also { it.parent.createDirectories(); it.writeText("#!/bin/sh\necho mine\n") }
        // /usr/local/bin is the fallback; skip the check where this machine lets the test write there.
        if (!Files.isWritable(Path.of("/usr/local/bin"))) assertFailsWith<IllegalStateException> { setup.installCommand() }
        assertEquals("#!/bin/sh\necho mine\n", theirs.readText())
    }

    @Test
    fun `windows gets a cmd file in WindowsApps`() {
        val windows = McpSetup(launcher, home, McpSetup.Os.Windows) { if (it == "LOCALAPPDATA") home.resolve("AppData/Local").toString() else null }
        val command = windows.installCommand()
        assertEquals("tablegrip.cmd", command.fileName.toString())
        assertTrue(command.startsWith(home.resolve("AppData/Local/Microsoft/WindowsApps")))
        assertTrue("\"$launcher\" %*" in command.readText())
        assertEquals(launcher, windows.serverCommand())
    }

    @Test
    fun `configuring an app keeps its other servers and a backup`() {
        val file = setup.configFile(McpSetup.Client.ClaudeDesktop)
        file.parent.createDirectories()
        val before = """{ "mcpServers": { "other": { "command": "x" } }, "theme": "dark" }"""
        file.writeText(before)
        val command = setup.installCommand()
        assertFalse(setup.isConfigured(McpSetup.Client.ClaudeDesktop))
        setup.configure(McpSetup.Client.ClaudeDesktop)
        val config = Json.parseToJsonElement(file.readText()).jsonObject
        val servers = config["mcpServers"]!!.jsonObject
        assertEquals("x", servers["other"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(command.toString(), servers["tablegrip"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("mcp", servers["tablegrip"]!!.jsonObject["args"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("dark", config["theme"]!!.jsonPrimitive.content)
        assertEquals(before, file.resolveSibling(file.fileName.toString() + ".bak").readText())
        assertTrue(setup.isConfigured(McpSetup.Client.ClaudeDesktop))
    }

    @Test
    fun `vs code gets its own shape and unreadable files are left alone`() {
        setup.configure(McpSetup.Client.VsCode, launcher)
        val entry = Json.parseToJsonElement(setup.configFile(McpSetup.Client.VsCode).readText()).jsonObject["servers"]!!.jsonObject["tablegrip"]!!.jsonObject
        assertEquals("stdio", entry["type"]!!.jsonPrimitive.content)

        val cursor = setup.configFile(McpSetup.Client.Cursor).also { it.parent.createDirectories(); it.writeText("{ not json") }
        assertFailsWith<IllegalStateException> { setup.configure(McpSetup.Client.Cursor, launcher) }
        assertEquals("{ not json", cursor.readText())
    }

    @Test
    fun `claude code is set up with tablegrip mcp`() {
        assertEquals("claude mcp add --scope user tablegrip -- tablegrip mcp", setup.claudeCodeCommand())
    }
}
