package dev.datagrok.app.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.connection.SshAuth
import dev.datagrok.core.connection.SshConfig
import java.util.UUID
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** Form state for creating or editing a connection profile. */
private class ConnectionForm(existing: ConnectionConfig?) {
    val id = existing?.id ?: UUID.randomUUID().toString()
    var type by mutableStateOf(existing?.type ?: DatabaseType.PostgreSQL)
    val name = TextFieldState(existing?.name.orEmpty())
    val host = TextFieldState(existing?.host ?: "localhost")
    val port = TextFieldState(existing?.port?.toString().orEmpty())
    val database = TextFieldState(existing?.database.orEmpty())
    val user = TextFieldState(existing?.user.orEmpty())
    val password = TextFieldState()

    var environment by mutableStateOf(existing?.environment ?: Environment.None)
    var readOnly by mutableStateOf(existing?.readOnly ?: false)
    var confirmWrites by mutableStateOf(existing?.confirmWrites ?: false)

    var useSsh by mutableStateOf(existing?.ssh != null)
    val sshHost = TextFieldState(existing?.ssh?.host.orEmpty())
    val sshPort = TextFieldState(existing?.ssh?.port?.toString() ?: "22")
    val sshUser = TextFieldState(existing?.ssh?.user ?: System.getProperty("user.name"))
    var sshAuth by mutableStateOf(existing?.ssh?.auth ?: SshAuth.PrivateKey)
    val sshKeyPath = TextFieldState(existing?.ssh?.privateKeyPath.orEmpty())
    /** SSH password, or the private key's passphrase. */
    val sshSecret = TextFieldState()

    val isServer: Boolean
        get() = type.defaultPort != null

    fun validate(): String? {
        val port = port.text.toString()
        return when {
            database.text.isBlank() -> if (isServer) "Database is required." else "Database file is required."
            isServer && host.text.isBlank() -> "Host is required."
            isServer && port.isNotEmpty() && port.toIntOrNull() !in 1..65535 -> "Port must be between 1 and 65535."
            ssh && sshHost.text.isBlank() -> "SSH host is required."
            ssh && sshUser.text.isBlank() -> "SSH user is required."
            ssh && sshPort.text.toString().toIntOrNull() !in 1..65535 -> "SSH port must be between 1 and 65535."
            else -> null
        }
    }

    private val ssh: Boolean
        get() = useSsh && isServer

    fun toConfig(): ConnectionConfig {
        val database = database.text.toString().trim()
        val fallbackName = if (isServer) "${host.text.trim()}/$database" else database.substringAfterLast('/')
        return ConnectionConfig(
            id = id,
            name = name.text.toString().trim().ifEmpty { fallbackName },
            type = type,
            host = if (isServer) host.text.toString().trim() else "",
            port = if (isServer) port.text.toString().toIntOrNull() else null,
            database = database,
            user = if (isServer) user.text.toString().trim() else "",
            environment = environment,
            readOnly = readOnly,
            confirmWrites = confirmWrites,
            ssh =
                if (!ssh) null
                else
                    SshConfig(
                        host = sshHost.text.toString().trim(),
                        port = sshPort.text.toString().toInt(),
                        user = sshUser.text.toString().trim(),
                        auth = sshAuth,
                        privateKeyPath = sshKeyPath.text.toString().trim().ifEmpty { null }.takeIf { sshAuth == SshAuth.PrivateKey },
                    ),
        )
    }
}

private val MaskPassword = OutputTransformation { replace(0, length, "•".repeat(length)) }

private enum class DialogTab { General, Ssh, Options }

@Composable
internal fun ConnectionDialog(state: ConnectionsViewModel, request: ConnectionDialogRequest) {
    val form = remember(request) { ConnectionForm(request.existing) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Message?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(DialogTab.General) }

    LaunchedEffect(request) {
        val id = request.existing?.id ?: return@LaunchedEffect
        state.storedPassword(id)?.let { form.password.setTextAndPlaceCursorAtEnd(it) }
        state.storedSshSecret(id)?.let { form.sshSecret.setTextAndPlaceCursorAtEnd(it) }
    }

    fun submit(test: Boolean) {
        form.validate()?.let {
            message = Message(it, isError = true)
            return
        }
        busy = true
        message = Message(if (test) "Connecting…" else "Saving…", isError = false)
        scope.launch {
            val config = form.toConfig()
            val password = form.password.text.toString()
            val sshSecret = form.sshSecret.text.toString()
            val result = if (test) state.test(config, password, sshSecret) else state.save(config, password, sshSecret)
            message =
                result.fold(
                    onSuccess = { Message("Connection succeeded.", isError = false).takeIf { test } },
                    onFailure = { Message(it.message ?: it.toString(), isError = true) },
                )
            busy = false
        }
    }

    DialogWindow(
        onCloseRequest = state::closeDialog,
        state = rememberDialogState(size = DpSize(580.dp, 520.dp)),
        title = if (request.existing == null) "New Connection" else "Edit Connection",
        resizable = false,
    ) {
        Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
            TabStrip(
                tabs =
                    DialogTab.entries
                        .filter { it != DialogTab.Ssh || form.isServer }
                        .map { entry ->
                            TabData.Default(
                                selected = entry == tab,
                                content = { tabState ->
                                    SimpleTabContent(
                                        when (entry) {
                                            DialogTab.General -> "General"
                                            DialogTab.Ssh -> if (form.useSsh) "SSH ✓" else "SSH"
                                            DialogTab.Options -> "Options"
                                        },
                                        tabState,
                                    )
                                },
                                closable = false,
                                onClick = { tab = entry },
                            )
                        },
                style = JewelTheme.defaultTabStyle,
            )
            Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (if (tab == DialogTab.Ssh && !form.isServer) DialogTab.General else tab) {
                    DialogTab.General -> GeneralTab(form)
                    DialogTab.Ssh -> SshTab(form)
                    DialogTab.Options -> OptionsTab(form)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let {
                    Text(it.text, color = if (it.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info, maxLines = 3)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { submit(test = true) }, enabled = !busy) { Text("Test Connection") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = state::closeDialog) { Text("Cancel") }
                    DefaultButton(onClick = { submit(test = false) }, enabled = !busy) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun GeneralTab(form: ConnectionForm) {
    FormRow("Name") { TextField(form.name, Modifier.fillMaxWidth(), placeholder = { Text("Optional") }) }
    FormRow("Type") {
        ListComboBox(
            items = DatabaseType.entries.map { it.displayName },
            selectedIndex = form.type.ordinal,
            onSelectedItemChange = { form.type = DatabaseType.entries[it] },
            modifier = Modifier.width(180.dp),
        )
    }
    if (form.isServer) {
        FormRow("Host") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(form.host, Modifier.weight(1f))
                Text("Port")
                TextField(form.port, Modifier.width(80.dp), placeholder = { Text(form.type.defaultPort.toString()) })
            }
        }
        FormRow("Database") { TextField(form.database, Modifier.fillMaxWidth()) }
        FormRow("User") { TextField(form.user, Modifier.fillMaxWidth()) }
        FormRow("Password") {
            TextField(form.password, Modifier.fillMaxWidth(), outputTransformation = MaskPassword, placeholder = { Text("Saved in the system keychain") })
        }
        if (form.useSsh) Text("Host and port are resolved from the SSH server.", color = JewelTheme.globalColors.text.info)
    } else {
        FormRow("File") {
            TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text("/path/to/database.sqlite or :memory:") })
        }
    }
}

@Composable
private fun SshTab(form: ConnectionForm) {
    CheckboxRow("Connect through an SSH tunnel", form.useSsh, onCheckedChange = { form.useSsh = it })
    if (!form.useSsh) return
    FormRow("Host") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(form.sshHost, Modifier.weight(1f), placeholder = { Text("bastion.example.com") })
            Text("Port")
            TextField(form.sshPort, Modifier.width(80.dp))
        }
    }
    FormRow("User") { TextField(form.sshUser, Modifier.fillMaxWidth()) }
    FormRow("Auth") {
        ListComboBox(
            items = listOf("Key pair", "Password"),
            selectedIndex = if (form.sshAuth == SshAuth.PrivateKey) 0 else 1,
            onSelectedItemChange = { form.sshAuth = if (it == 0) SshAuth.PrivateKey else SshAuth.Password },
            modifier = Modifier.width(180.dp),
        )
    }
    if (form.sshAuth == SshAuth.PrivateKey) {
        FormRow("Key file") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(form.sshKeyPath, Modifier.weight(1f), placeholder = { Text("Default keys in ~/.ssh") })
                OutlinedButton(onClick = { chooseFile("Private key")?.let { form.sshKeyPath.setTextAndPlaceCursorAtEnd(it) } }) { Text("Browse…") }
            }
        }
        FormRow("Passphrase") {
            TextField(form.sshSecret, Modifier.fillMaxWidth(), outputTransformation = MaskPassword, placeholder = { Text("If the key has one") })
        }
    } else {
        FormRow("Password") { TextField(form.sshSecret, Modifier.fillMaxWidth(), outputTransformation = MaskPassword) }
    }
    Text(
        "The server's host key is trusted on first connect and pinned; a changed key is refused.",
        color = JewelTheme.globalColors.text.info,
    )
}

@Composable
private fun OptionsTab(form: ConnectionForm) {
    FormRow("Environment") {
        ListComboBox(
            items = Environment.entries.map { it.label },
            selectedIndex = form.environment.ordinal,
            onSelectedItemChange = {
                form.environment = Environment.entries[it]
                // Production defaults to asking before writes; the user can still turn it off.
                if (form.environment == Environment.Production) form.confirmWrites = true
            },
            modifier = Modifier.width(180.dp),
        )
    }
    Text("Colours the connection everywhere it appears, so production stands out.", color = JewelTheme.globalColors.text.info)
    Spacer(Modifier.height(4.dp))
    CheckboxRow("Read-only: refuse statements that change data or schema", form.readOnly, onCheckedChange = { form.readOnly = it })
    CheckboxRow("Confirm before running statements that change data or schema", form.confirmWrites, onCheckedChange = { form.confirmWrites = it })
    Text("Dangerous statements (DELETE/UPDATE without WHERE, DROP, TRUNCATE) always ask.", color = JewelTheme.globalColors.text.info)
}

/** Native file chooser; returns the picked path or null. */
private fun chooseFile(title: String): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.directory = System.getProperty("user.home") + "/.ssh"
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it).path }
}

private data class Message(val text: String, val isError: Boolean)

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

/** Asks before deleting a connection profile and its stored password. */
@Composable
internal fun DeleteConnectionDialog(state: ConnectionsViewModel, config: ConnectionConfig) {
    DialogWindow(
        onCloseRequest = { state.pendingDelete = null },
        state = rememberDialogState(size = DpSize(420.dp, 160.dp)),
        title = "Delete Connection",
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Delete “${config.name}”? Its saved password will be removed from the keychain too.")
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.pendingDelete = null }) { Text("Cancel") }
                DefaultButton(onClick = { state.delete(config) }) { Text("Delete") }
            }
        }
    }
}
