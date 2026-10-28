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
import androidx.compose.foundation.text.input.KeyboardActionHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.datagrok.app.ui.AppDialog
import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.ConnectionKind
import dev.datagrok.core.connection.ConnectionString
import dev.datagrok.core.connection.DatabaseFiles
import dev.datagrok.core.connection.DatabaseType
import dev.datagrok.core.connection.DriverId
import dev.datagrok.core.connection.JdbcUrls
import dev.datagrok.core.connection.Paradigm
import dev.datagrok.core.connection.ParsedConnection
import dev.datagrok.core.connection.Environment
import dev.datagrok.core.connection.SshAuth
import dev.datagrok.core.connection.SshConfig
import dev.datagrok.core.drivers.DriverResolution
import java.nio.file.Path
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
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import org.jetbrains.jewel.ui.component.Link
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
    var driver by mutableStateOf(existing?.effectiveDriver ?: DatabaseType.PostgreSQL.drivers.first())
    /** Connect with a raw JDBC URL instead of host/port/database. */
    var useUrl by mutableStateOf(existing?.url != null || existing?.type?.connection == ConnectionKind.Url)
    /** Jars of a [DriverId.Generic] driver, as paths. */
    var driverJars by mutableStateOf(existing?.driverJars.orEmpty())
    val url = TextFieldState(existing?.url.orEmpty())
    val properties = TextFieldState(existing?.properties.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" })
    val connectionString = TextFieldState()

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
        get() = type.isServer

    /** Reached through JDBC; key-value and document stores have no database field, URL form or JDBC URL preview. */
    val relational: Boolean
        get() = type.paradigm == Paradigm.Relational

    /** MongoDB and the like: a database field and a connection string, but nothing JDBC. */
    val document: Boolean
        get() = type.paradigm == Paradigm.Document

    /** The engine has no host/file form: only a JDBC URL. */
    val urlOnly: Boolean
        get() = type.connection == ConnectionKind.Url

    fun changeType(newType: DatabaseType) {
        type = newType
        if (driver !in newType.drivers) driver = newType.drivers.first()
        if (urlOnly) useUrl = true
    }

    /** Fills the form from a pasted connection string; returns warnings to show. */
    fun apply(parsed: ParsedConnection): List<String> {
        changeType(parsed.type)
        parsed.driver?.takeIf { it in parsed.type.drivers }?.let { driver = it }
        useUrl = parsed.url != null || urlOnly
        parsed.url?.let { url.setTextAndPlaceCursorAtEnd(it) }
        parsed.host?.let { host.setTextAndPlaceCursorAtEnd(it) }
        port.setTextAndPlaceCursorAtEnd(parsed.port?.toString().orEmpty())
        parsed.database?.let { database.setTextAndPlaceCursorAtEnd(it) }
        parsed.user?.let { user.setTextAndPlaceCursorAtEnd(it) }
        parsed.password?.let { password.setTextAndPlaceCursorAtEnd(it) }
        if (parsed.properties.isNotEmpty()) {
            val merged = parseProperties().getOrDefault(emptyMap()) + parsed.properties
            properties.setTextAndPlaceCursorAtEnd(merged.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
        return parsed.warnings
    }

    /** Moves a password typed into the URL to the password field, so it ends up in the keychain, not the profile. */
    fun moveUrlPassword() {
        if (!useUrl) return
        val (clean, secret) = ConnectionString.stripSecrets(url.text.toString().trim())
        url.setTextAndPlaceCursorAtEnd(clean)
        if (secret != null) password.setTextAndPlaceCursorAtEnd(secret)
    }

    /** `key=value` per line; blank lines and `#` comments are ignored. */
    fun parseProperties(): Result<Map<String, String>> = runCatching {
        properties.text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
            require('=' in line) { "Driver property \"$line\" must look like key=value." }
            line.substringBefore('=').trim() to line.substringAfter('=').trim()
        }
    }

    fun validate(drivers: DriverDownloads): String? {
        val port = port.text.toString()
        parseProperties().exceptionOrNull()?.let { return it.message }
        if (useUrl && document) {
            val raw = url.text.toString().trim()
            return when {
                raw.isEmpty() -> "Connection string is required."
                !raw.startsWith("mongodb://") && !raw.startsWith("mongodb+srv://") -> "A connection string starts with mongodb:// or mongodb+srv://."
                ssh -> "SSH tunnels need host and port, not a connection string."
                else -> null
            }
        }
        if (useUrl) {
            val raw = url.text.toString().trim()
            return when {
                raw.isEmpty() -> "JDBC URL is required."
                !raw.startsWith("jdbc:") -> "A JDBC URL starts with jdbc: — paste other formats into Connection string."
                ssh && runCatching { JdbcUrls.build(toConfig(), via = "127.0.0.1" to 1) }.isFailure -> "SSH tunnels need a single-host URL."
                // Only checkable once the driver is here; until then the driver banner asks for it.
                drivers.library.resolve(toConfig()) is DriverResolution.Ready && !drivers.library.load(toConfig()).acceptsURL(JdbcUrls.build(toConfig())) ->
                    "${driver.displayName} doesn't accept this URL."
                else -> null
            }
        }
        return when {
            relational && database.text.isBlank() -> if (isServer) "Database is required." else "Database file is required."
            fileMismatch() != null -> fileMismatch()
            isServer && host.text.isBlank() -> "Host is required."
            ssh && ',' in host.text -> "SSH tunnels need a single host."
            isServer && port.isNotEmpty() && port.toIntOrNull() !in 1..65535 -> "Port must be between 1 and 65535."
            ssh && sshHost.text.isBlank() -> "SSH host is required."
            ssh && sshUser.text.isBlank() -> "SSH user is required."
            ssh && sshPort.text.toString().toIntOrNull() !in 1..65535 -> "SSH port must be between 1 and 65535."
            else -> null
        }
    }

    /** Why the chosen file isn't a database of this type (checked by content), or null. */
    private fun fileMismatch(): String? =
        if (isServer) null else runCatching { Path.of(database.text.toString().trim()) }.getOrNull()?.let { DatabaseFiles.mismatch(it, type) }

    private val ssh: Boolean
        get() = useSsh && isServer

    fun toConfig(): ConnectionConfig {
        val database = database.text.toString().trim()
        val fallbackName =
            when {
                useUrl -> url.text.toString().substringAfter("//").substringBefore('?').substringAfterLast('@').ifEmpty { type.displayName }
                isServer -> "${host.text.trim()}/$database"
                else -> database.substringAfterLast('/')
            }
        return ConnectionConfig(
            id = id,
            name = name.text.toString().trim().ifEmpty { fallbackName },
            type = type,
            host = if (isServer) host.text.toString().trim() else "",
            port = if (isServer) port.text.toString().toIntOrNull() else null,
            database = database,
            user = if (isServer) user.text.toString().trim() else "",
            driver = driver,
            driverJars = if (driver == DriverId.Generic) driverJars else emptyList(),
            properties = parseProperties().getOrDefault(emptyMap()),
            url = url.text.toString().trim().takeIf { useUrl && it.isNotEmpty() },
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
    AppDialog(
        onCloseRequest = state::closeDialog,
        size = DpSize(640.dp, 600.dp),
        title = if (request.existing == null) "New Connection" else "Edit Connection",
        resizable = false,
    ) {
        ConnectionDialogContent(state, request)
    }
}

/** The dialog body, separate from its window so it can be rendered in tests. */
@Composable
internal fun ConnectionDialogContent(state: ConnectionsViewModel, request: ConnectionDialogRequest) {
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
        form.moveUrlPassword()
        form.validate(state.drivers)?.let {
            message = Message(it, isError = true)
            return
        }
        busy = true
        message = Message(if (test) "Connecting…" else "Saving…", isError = false)
        scope.launch {
            val config = form.toConfig()
            val password = form.password.text.toString()
            val sshSecret = form.sshSecret.text.toString()
            message =
                if (test) {
                    state.test(config, password, sshSecret).fold(
                        onSuccess = { outcome ->
                            val detected = outcome.suggested
                            when {
                                detected != null ->
                                    Message("Connection succeeded. This server is ${detected.displayName}.", isError = false, action = "Use ${detected.displayName}" to { form.changeType(detected) })
                                outcome.server != null -> Message("Connected to ${outcome.server}.", isError = false)
                                else -> Message("Connection succeeded.", isError = false)
                            }
                        },
                        onFailure = { Message(it.message ?: it.toString(), isError = true) },
                    )
                } else {
                    state.save(config, password, sshSecret).exceptionOrNull()?.let { Message(it.message ?: it.toString(), isError = true) }
                }
            busy = false
        }
    }

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
                DialogTab.General -> GeneralTab(form, state.drivers) { message = it }
                DialogTab.Ssh -> SshTab(form)
                DialogTab.Options -> OptionsTab(form)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.text, color = if (it.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info, maxLines = 3)
                    it.action?.let { (label, act) ->
                        Link(label, onClick = {
                            act()
                            message = null
                        })
                    }
                }
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

@Composable
private fun GeneralTab(form: ConnectionForm, drivers: DriverDownloads, onMessage: (Message?) -> Unit) {
    fun parse() {
        val text = form.connectionString.text.toString()
        if (text.isBlank()) return
        onMessage(
            runCatching { ConnectionString.parse(text) }
                .fold(
                    onSuccess = { parsed ->
                        val warnings = form.apply(parsed)
                        form.connectionString.setTextAndPlaceCursorAtEnd("")
                        Message((listOf("Filled in from the connection string.") + warnings).joinToString("\n"), isError = false)
                    },
                    onFailure = { Message(it.message ?: "Couldn't parse the connection string.", isError = true) },
                )
        )
    }
    FormRow("Paste") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                form.connectionString,
                Modifier.weight(1f),
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text("postgres://user:pass@host/db, mongodb+srv://…, jdbc:…, path.db") },
                onKeyboardAction = KeyboardActionHandler { parse() },
            )
            OutlinedButton(onClick = ::parse) { Text("Parse") }
        }
    }
    FormRow("Name") { TextField(form.name, Modifier.fillMaxWidth(), placeholder = { Text("Optional") }) }
    FormRow("Type") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ListComboBox(
                items = DatabaseType.entries.map { it.displayName },
                selectedIndex = DatabaseType.entries.indexOf(form.type),
                onSelectedItemChange = { form.changeType(DatabaseType.entries[it]) },
                modifier = Modifier.width(180.dp),
            )
            val choices = form.type.drivers
            ListComboBox(
                items = choices.map { it.displayName },
                selectedIndex = choices.indexOf(form.driver).coerceAtLeast(0),
                onSelectedItemChange = { form.driver = choices[it] },
                modifier = Modifier.weight(1f),
                enabled = choices.size > 1,
            )
        }
    }
    DriverBanner(
        form.driver,
        drivers,
        Modifier.fillMaxWidth(),
        onSwitch = { form.driver = it },
        onMessage = { onMessage(Message(it, isError = true)) },
        profileJars = form.driverJars,
        onProfileJars = { form.driverJars = it },
    )
    if (!form.urlOnly && (form.relational || form.document)) FormRow("Connect by") {
        ListComboBox(
            items = listOf(if (form.isServer) "Host and port" else "File", if (form.document) "Connection string" else "JDBC URL"),
            selectedIndex = if (form.useUrl) 1 else 0,
            onSelectedItemChange = { index ->
                form.useUrl = index == 1
                // Start the URL from the current fields, so switching modes doesn't lose them.
                if (form.useUrl && form.url.text.isBlank()) {
                    val start =
                        if (form.document) "mongodb://${form.host.text.toString().trim().ifEmpty { "localhost" }}:${form.port.text.toString().trim().ifEmpty { "27017" }}/${form.database.text.toString().trim()}"
                        else runCatching { JdbcUrls.build(form.toConfig().copy(url = null)) }.getOrNull()
                    start?.let { form.url.setTextAndPlaceCursorAtEnd(it) }
                }
            },
            modifier = Modifier.width(180.dp),
        )
    }
    if (form.useUrl) {
        val example =
            when {
                form.document -> "mongodb+srv://user@cluster0.example.net/shop"
                form.urlOnly -> "jdbc:firebirdsql://host:3050/employee"
                else -> "jdbc:mysql:replication://primary,replica/db"
            }
        FormRow("URL") { TextField(form.url, Modifier.fillMaxWidth(), textStyle = JewelTheme.editorTextStyle, placeholder = { Text(example) }) }
        if (form.isServer || form.urlOnly) {
            FormRow("User") { TextField(form.user, Modifier.fillMaxWidth()) }
            FormRow("Password") {
                TextField(form.password, Modifier.fillMaxWidth(), outputTransformation = MaskPassword, placeholder = { Text("Saved in the system keychain") })
            }
        }
        return
    }
    if (form.isServer) {
        FormRow("Host") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(form.host, Modifier.weight(1f), placeholder = { Text("host, or host1:port1,host2:port2") })
                Text("Port")
                TextField(form.port, Modifier.width(80.dp), placeholder = { Text(form.type.defaultPort.toString()) })
            }
        }
        if (form.relational) FormRow("Database") { TextField(form.database, Modifier.fillMaxWidth()) }
        if (form.document) FormRow("Database") { TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text("Optional: opened first, and the default auth source") }) }
        FormRow("User") { TextField(form.user, Modifier.fillMaxWidth()) }
        FormRow("Password") {
            TextField(form.password, Modifier.fillMaxWidth(), outputTransformation = MaskPassword, placeholder = { Text("Saved in the system keychain") })
        }
    } else {
        FormRow("File") {
            TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text(form.type.databaseHint ?: "/path/to/database.sqlite or :memory:") })
        }
    }
    val preview = if (form.relational) runCatching { JdbcUrls.build(form.toConfig()) }.getOrNull() else null
    if (preview != null) {
        FormRow("JDBC URL") {
            Text(preview, color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Spacer(Modifier.height(4.dp))
    Text("Driver properties (${form.driver.displayName})")
    TextArea(
        form.properties,
        Modifier.fillMaxWidth().height(96.dp),
        textStyle = JewelTheme.editorTextStyle,
        placeholder = { Text(PropertyExamples[form.driver].orEmpty()) },
    )
}

/** Commonly needed properties per driver, using each driver's documented names. */
private val PropertyExamples =
    mapOf(
        DriverId.PostgreSQL to "sslmode=verify-full\ncurrentSchema=app\nApplicationName=Datagrok",
        DriverId.MySQL to "sslMode=REQUIRED\nallowPublicKeyRetrieval=true\nserverTimezone=UTC",
        DriverId.MariaDB to "sslMode=verify-full\nallowPublicKeyRetrieval=true",
        DriverId.SQLite to "foreign_keys=true\nbusy_timeout=5000",
        DriverId.MongoDB to "authSource=admin\ntls=true\nreplicaSet=rs0",
        DriverId.Lettuce to "ssl=true\nmode=cluster  (hosts are seed nodes)\nmode=sentinel + sentinelMaster=mymaster",
    )

/** Native file chooser; returns the picked path or null. */
private fun chooseFile(title: String): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.directory = System.getProperty("user.home") + "/.ssh"
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it).path }
}

private data class Message(val text: String, val isError: Boolean, val action: Pair<String, () -> Unit>? = null)

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
    AppDialog(
        onCloseRequest = { state.pendingDelete = null },
        size = DpSize(420.dp, 160.dp),
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

/**
 * Shown when a saved connection can't connect because its driver isn't on this machine (a new install, another
 * computer, or a profile using MySQL's own driver). Connects as soon as the driver is ready.
 */
@Composable
internal fun MissingDriverDialog(state: ConnectionsViewModel, config: ConnectionConfig) {
    var driver by remember(config) { mutableStateOf(config.effectiveDriver) }
    var jars by remember(config) { mutableStateOf(config.driverJars) }
    var error by remember(config) { mutableStateOf<String?>(null) }
    val updated = config.copy(driver = driver, driverJars = if (driver == DriverId.Generic) jars else config.driverJars)
    val ready = state.drivers.resolve(updated) is DriverResolution.Ready
    fun connect() {
        if (updated != config) state.updateDriver(updated)
        state.retryAfterDriver(updated)
    }
    // The user's download or jar was the whole point: connect without another click once it lands.
    LaunchedEffect(ready) { if (ready) connect() }
    AppDialog(
        onCloseRequest = { state.driverPrompt = null },
        size = DpSize(520.dp, 240.dp),
        title = "Driver Needed",
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("“${config.name}” uses ${driver.displayName}, which isn't on this computer yet.")
            DriverBanner(
                driver,
                state.drivers,
                Modifier.fillMaxWidth(),
                onSwitch = { driver = it },
                onMessage = { error = it },
                profileJars = jars,
                onProfileJars = { jars = it },
            )
            error?.let { Text(it, color = JewelTheme.globalColors.text.error, maxLines = 3) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.driverPrompt = null }) { Text("Cancel") }
                DefaultButton(onClick = ::connect, enabled = ready) { Text("Connect") }
            }
        }
    }
}
