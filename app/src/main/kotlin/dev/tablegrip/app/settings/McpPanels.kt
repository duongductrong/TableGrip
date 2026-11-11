package dev.tablegrip.app.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.copyToClipboard
import dev.tablegrip.core.agent.cli.ShellEnvironment
import dev.tablegrip.core.i18n.tr
import dev.tablegrip.mcp.AuditEntry
import dev.tablegrip.mcp.AuditLog
import dev.tablegrip.mcp.McpSetup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/** The `tablegrip` command and the AI apps it can be added to, for Settings › MCP. */
@Composable
internal fun McpSetupPanel(app: AppState) {
    val setup = remember { McpSetup() }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var shellPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { shellPath = runCatching { ShellEnvironment.path() }.getOrNull() }
    val installed = remember(refresh) { setup.installedCommand() }
    val current = remember(refresh) { setup.commandIsCurrent() }

    fun act(action: () -> String) =
        scope.launch {
            message = withContext(Dispatchers.IO) { runCatching(action).getOrElse { it.message ?: it.toString() } }
            refresh++
        }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!app.settings.mcpEnabled) {
            Text(tr("Turn on the switch above first: until then the server answers, but shares nothing."), color = JewelTheme.globalColors.text.warning, fontSize = 12.sp)
        }
        // The command
        Text(tr("Command line"), fontWeight = FontWeight.SemiBold)
        if (setup.launcher == null) {
            Text(tr("Running from source: use scripts/tablegrip mcp. The installed app can add the tablegrip command here."), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        installed == null -> tr("The tablegrip command isn't installed.")
                        !current -> tr("{0} points to another copy of TableGrip.", installed)
                        else -> tr("Installed as {0}.", installed)
                    },
                    Modifier.weight(1f),
                )
                OutlinedButton(onClick = { act { tr("Installed {0}.", setup.installCommand()) } }) {
                    Text(if (installed == null) tr("Install command") else tr("Reinstall"))
                }
                if (installed != null) Link(tr("Remove"), onClick = { act { tr("Removed {0}.", setup.removeCommand() ?: "") } })
            }
            if (installed != null && shellPath != null && !setup.isOnPath(installed.parent, shellPath!!)) {
                Text(tr("{0} isn't on your shell's PATH: add it to run tablegrip from a terminal.", installed.parent), color = JewelTheme.globalColors.text.warning, fontSize = 12.sp)
            }
            Text(tr("Lets terminals and AI apps start the server with: tablegrip mcp"), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
        }

        // Apps
        Spacer(Modifier.width(1.dp))
        Text(tr("AI apps"), fontWeight = FontWeight.SemiBold)
        McpSetup.Client.entries.forEach { client ->
            ClientRow(setup, client, refresh, enabled = setup.launcher != null) { act { tr("Added TableGrip to {0} ({1}). Restart it to load the server.", client.displayName, setup.configure(client)) } }
        }
        AppCommandRow(tr("Claude Code"), setup.claudeCodeCommand(), tr("Run this in a terminal (needs the tablegrip command)."))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Other apps"), Modifier.weight(1f))
            Link(tr("Copy config"), onClick = {
                copyToClipboard(setup.snippet())
                message = tr("Copied. Paste it into the app's MCP settings.")
            })
        }
        message?.let { Text(it, color = JewelTheme.globalColors.text.info, fontSize = 12.sp) }
    }
}

@Composable
private fun ClientRow(setup: McpSetup, client: McpSetup.Client, refresh: Int, enabled: Boolean, add: () -> Unit) {
    var confirming by remember(client) { mutableStateOf(false) }
    val configured = remember(client, refresh) { setup.isConfigured(client) }
    val file = setup.configFile(client)
    Column(
        Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(client.displayName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(if (configured) tr("Added") else tr("Not added"), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
            OutlinedButton(onClick = { confirming = !confirming }, enabled = enabled) { Text(if (configured) tr("Update…") else tr("Add…")) }
        }
        if (confirming) {
            Text(tr("Adds this entry to {0}, keeping its other servers. The file is first copied to {1}.", file, file.fileName.toString() + ".bak"), fontSize = 12.sp)
            SelectionContainer { Text(setup.snippet(client), fontSize = 12.sp, style = JewelTheme.editorTextStyle) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultButton(onClick = { confirming = false; add() }) { Text(tr("Add")) }
                OutlinedButton(onClick = { confirming = false }) { Text(tr("Cancel")) }
            }
        }
    }
}

@Composable
private fun AppCommandRow(name: String, command: String, hint: String) {
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Link(if (copied) tr("Copied") else tr("Copy"), onClick = { copyToClipboard(command); copied = true })
        }
        SelectionContainer { Text(command, fontSize = 12.sp, style = JewelTheme.editorTextStyle) }
        Text(hint, color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
    }
}

/** The latest tool calls from MCP clients, newest first, from `~/.tablegrip/mcp.log.jsonl`. */
@Composable
internal fun McpActivityPanel(@Suppress("UNUSED_PARAMETER") app: AppState) {
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    LaunchedEffect(refresh) { entries = withContext(Dispatchers.IO) { AuditLog().recent(50) } }
    val time = remember { DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link(tr("Refresh"), onClick = { refresh++ })
        }
        if (entries.isEmpty()) Text(tr("No AI app has used TableGrip yet."), color = JewelTheme.globalColors.text.info)
        entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(time.format(Instant.ofEpochMilli(entry.time)), Modifier.width(110.dp), color = JewelTheme.globalColors.text.info, fontSize = 12.sp)
                Text(entry.client, Modifier.width(110.dp), fontSize = 12.sp)
                Text(listOfNotNull(entry.tool, entry.connection).joinToString(" · "), Modifier.width(220.dp), fontSize = 12.sp)
                Text(
                    if (entry.ok) tr("{0} ms", entry.millis) else entry.error.orEmpty().take(160),
                    color = if (entry.ok) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
