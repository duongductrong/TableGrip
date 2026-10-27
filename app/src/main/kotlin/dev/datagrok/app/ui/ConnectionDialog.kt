package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.datagrok.app.AppState
import dev.datagrok.app.ConnectionDialogRequest
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.DatabaseType
import java.util.UUID
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
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

    val isServer: Boolean
        get() = type.defaultPort != null

    fun validate(): String? {
        val port = port.text.toString()
        return when {
            database.text.isBlank() -> if (isServer) "Database is required." else "Database file is required."
            isServer && host.text.isBlank() -> "Host is required."
            isServer && port.isNotEmpty() && port.toIntOrNull() !in 1..65535 -> "Port must be between 1 and 65535."
            else -> null
        }
    }

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
        )
    }
}

private val MaskPassword = OutputTransformation { replace(0, length, "•".repeat(length)) }

@Composable
internal fun ConnectionDialog(state: AppState, request: ConnectionDialogRequest) {
    val form = remember(request) { ConnectionForm(request.existing) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Message?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        val id = request.existing?.id ?: return@LaunchedEffect
        state.storedPassword(id)?.let { form.password.setTextAndPlaceCursorAtEnd(it) }
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
            val result = if (test) state.testConnection(config, password) else state.saveConnection(config, password)
            message =
                result.fold(
                    onSuccess = { Message("Connection succeeded.", isError = false).takeIf { test } },
                    onFailure = { Message(it.message ?: it.toString(), isError = true) },
                )
            busy = false
        }
    }

    DialogWindow(
        onCloseRequest = state::closeConnectionDialog,
        state = rememberDialogState(size = DpSize(520.dp, 440.dp)),
        title = if (request.existing == null) "New Connection" else "Edit Connection",
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                    TextField(
                        form.password,
                        Modifier.fillMaxWidth(),
                        outputTransformation = MaskPassword,
                        placeholder = { Text("Saved in the system keychain") },
                    )
                }
            } else {
                FormRow("File") {
                    TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text("/path/to/database.sqlite or :memory:") })
                }
            }

            Spacer(Modifier.weight(1f))
            message?.let {
                Text(it.text, color = if (it.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { submit(test = true) }, enabled = !busy) { Text("Test Connection") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = state::closeConnectionDialog) { Text("Cancel") }
                DefaultButton(onClick = { submit(test = false) }, enabled = !busy) { Text("Save") }
            }
        }
    }
}

private data class Message(val text: String, val isError: Boolean)

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

/** Asks before deleting a connection profile and its stored password. */
@Composable
internal fun DeleteConnectionDialog(state: AppState, config: ConnectionConfig) {
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
                DefaultButton(onClick = { state.deleteConnection(config) }) { Text("Delete") }
            }
        }
    }
}
