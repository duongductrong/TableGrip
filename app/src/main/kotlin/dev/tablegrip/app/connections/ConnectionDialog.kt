package dev.tablegrip.app.connections

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
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionKind
import dev.tablegrip.core.connection.ConnectionString
import dev.tablegrip.core.connection.DataFileOptions
import dev.tablegrip.core.connection.DatabaseFiles
import dev.tablegrip.core.datafile.DataFileFormats
import dev.tablegrip.core.datafile.DataFileStore
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.connection.JdbcUrls
import dev.tablegrip.core.connection.Paradigm
import dev.tablegrip.core.connection.ParsedConnection
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.SshAuth
import dev.tablegrip.core.connection.SshConfig
import dev.tablegrip.core.drivers.DriverResolution
import dev.tablegrip.core.imports.SshHost
import dev.tablegrip.core.imports.SshHosts
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private class ConnectionForm(existing: ConnectionConfig?, group: String = "") {
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
    val group = TextFieldState(existing?.group ?: group)
    private val favorite = existing?.favorite ?: false
    var readOnly by mutableStateOf(existing?.readOnly ?: false)
    var confirmWrites by mutableStateOf(existing?.confirmWrites ?: false)
    /** Null follows the app-wide setting. */
    var aiAccess by mutableStateOf(existing?.aiAccess)

    /** How a data file is read; null fields are detected. */
    var delimiter by mutableStateOf(existing?.dataFile?.delimiter)
    var charset by mutableStateOf(existing?.dataFile?.charset)
    var hasHeader by mutableStateOf(existing?.dataFile?.hasHeader ?: true)

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

    /** A CSV, Excel or JSON file opened as tables: a file and how to read it, nothing JDBC. */
    val dataFile: Boolean
        get() = type.isDataFile

    /** Takes a chosen or pasted data file, switching to the type its extension names. */
    fun useDataFile(path: String) {
        DataFileFormats.detect(Path.of(path))?.let { changeType(it.type) }
        database.setTextAndPlaceCursorAtEnd(path)
    }

    fun changeType(newType: DatabaseType) {
        type = newType
        if (driver !in newType.drivers) driver = newType.drivers.first()
        if (urlOnly) useUrl = true
    }

    /** Fills the tunnel from a `Host` of `~/.ssh/config`, as `ssh alias` would connect. */
    fun applySshHost(entry: SshHost) {
        sshHost.setTextAndPlaceCursorAtEnd(entry.hostName)
        sshPort.setTextAndPlaceCursorAtEnd((entry.port ?: 22).toString())
        entry.user?.let { sshUser.setTextAndPlaceCursorAtEnd(it) }
        sshAuth = SshAuth.PrivateKey
        sshKeyPath.setTextAndPlaceCursorAtEnd(entry.identityFile.orEmpty())
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
        if (dataFile) return validateDataFile()
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

    private fun validateDataFile(): String? {
        val raw = database.text.toString().trim()
        if (raw.isEmpty()) return "File is required."
        val path = runCatching { DataFileStore.pathOf(raw) }.getOrNull() ?: return "That isn't a valid path."
        if (!java.nio.file.Files.isRegularFile(path)) return "$path doesn't exist or isn't a file."
        val format = DataFileFormats.detect(path)
        return when {
            format != null && format.type != type -> "${path.fileName} looks like a ${format.type.displayName}; choose that type, or rename the file."
            charset != null && runCatching { java.nio.charset.Charset.forName(charset) }.isFailure -> "Unknown encoding \"$charset\"."
            else -> null
        }
    }

    /** Why the chosen file isn't a database of this type (checked by content), or null. */
    private fun fileMismatch(): String? =
        if (isServer || dataFile) null else runCatching { Path.of(database.text.toString().trim()) }.getOrNull()?.let { DatabaseFiles.mismatch(it, type) }

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
            properties = if (dataFile) emptyMap() else parseProperties().getOrDefault(emptyMap()),
            url = url.text.toString().trim().takeIf { useUrl && it.isNotEmpty() && !dataFile },
            dataFile = if (dataFile) DataFileOptions(delimiter = delimiter, hasHeader = hasHeader, charset = charset) else null,
            environment = environment,
            group = group.text.toString().trim(),
            favorite = favorite,
            readOnly = readOnly,
            confirmWrites = confirmWrites,
            aiAccess = aiAccess,
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

/** Shows typed secrets as dots; the field still holds the real text. */
internal val MaskPassword = OutputTransformation { replace(0, length, "•".repeat(length)) }

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
    val form = remember(request) { ConnectionForm(request.existing, request.group) }
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
        if (runCatching { DataFileFormats.detect(DataFileStore.pathOf(text)) }.getOrNull() != null) {
            form.useDataFile(text.trim())
            form.connectionString.setTextAndPlaceCursorAtEnd("")
            onMessage(Message("Opens ${form.database.text.toString().substringAfterLast('/')} as a ${form.type.displayName}.", isError = false))
            return
        }
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
                placeholder = { Text("postgres://user:pass@host/db, mongodb+srv://…, jdbc:…, path.db, data.csv") },
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
    if (form.dataFile) {
        DataFileFields(form)
        return
    }
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
    val sshHosts by produceState(emptyList<SshHost>()) { value = withContext(Dispatchers.IO) { runCatching { SshHosts.read() }.getOrDefault(emptyList()) } }
    if (sshHosts.isNotEmpty()) {
        var picked by remember { mutableStateOf(0) }
        FormRow("From config") {
            ListComboBox(
                items = listOf("Choose a host in ~/.ssh/config…") + sshHosts.map { if (it.hostName == it.alias) it.alias else "${it.alias}  (${it.hostName})" },
                selectedIndex = picked,
                onSelectedItemChange = { index ->
                    picked = index
                    sshHosts.getOrNull(index - 1)?.let(form::applySshHost)
                },
                modifier = Modifier.width(320.dp),
            )
        }
    }
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
    FormRow("Group") { TextField(form.group, Modifier.width(240.dp), placeholder = { Text("e.g. a project or client") }) }
    Spacer(Modifier.height(4.dp))
    CheckboxRow("Read-only: refuse statements that change data or schema", form.readOnly, onCheckedChange = { form.readOnly = it })
    CheckboxRow("Confirm before running statements that change data or schema", form.confirmWrites, onCheckedChange = { form.confirmWrites = it })
    Text("Dangerous statements (DELETE/UPDATE without WHERE, DROP, TRUNCATE) always ask.", color = JewelTheme.globalColors.text.info)
    Spacer(Modifier.height(4.dp))
    FormRow("AI agents") {
        val choices = listOf(null) + AiAccess.entries
        ListComboBox(
            items = choices.map { it?.label ?: "Use default (Settings › Agent)" },
            selectedIndex = choices.indexOf(form.aiAccess),
            onSelectedItemChange = { form.aiAccess = choices[it] },
            modifier = Modifier.width(240.dp),
        )
    }
    Text(
        when {
            form.aiAccess != null -> form.aiAccess!!.description
            form.environment == Environment.Production -> "Follows Settings › Agent, but never shares data from a production connection."
            else -> "Follows Settings › Agent."
        },
        color = JewelTheme.globalColors.text.info,
    )
    if (form.aiAccess == AiAccess.SchemaAndSamples && form.environment == Environment.Production) {
        Text("Values from this production database may be sent to the agent's provider.", color = JewelTheme.globalColors.text.warning)
    }
    if (form.dataFile) {
        Text("Data files are read into a working copy; every committed change is written back to the file.", color = JewelTheme.globalColors.text.info)
        return
    }
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
        DriverId.PostgreSQL to "sslmode=verify-full\ncurrentSchema=app\nApplicationName=TableGrip",
        DriverId.MySQL to "sslMode=REQUIRED\nallowPublicKeyRetrieval=true\nserverTimezone=UTC",
        DriverId.MariaDB to "sslMode=verify-full\nallowPublicKeyRetrieval=true",
        DriverId.SQLite to "foreign_keys=true\nbusy_timeout=5000",
        DriverId.Oracle to "internal_logon=sysdba\noracle.net.ssl_server_dn_match=true\noracle.jdbc.timezoneAsRegion=false",
        DriverId.Firebird to "encoding=WIN1252  (the database's character set)\nwireCrypt=REQUIRED\ncreateDatabaseIfNotExist=true",
        DriverId.Trino to "SSL=true\nroles=system:admin\nsessionProperties=query_max_run_time:10m",
        DriverId.MongoDB to "authSource=admin\ntls=true\nreplicaSet=rs0",
        DriverId.Etcd to "ssl=true\nverifyPeer=false  (self-signed certificates)",
        DriverId.Lettuce to "ssl=true\nmode=cluster  (hosts are seed nodes)\nmode=sentinel + sentinelMaster=mymaster",
    )

/** The file and how to read it: delimiter and encoding for CSV, whether the first row is the header. */
@Composable
private fun DataFileFields(form: ConnectionForm) {
    FormRow("File") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(form.database, Modifier.weight(1f), placeholder = { Text(form.type.databaseHint.orEmpty()) })
            OutlinedButton(onClick = { chooseDataFile()?.let(form::useDataFile) }) { Text("Browse…") }
        }
    }
    if (form.type == DatabaseType.CsvFile) {
        FormRow("Delimiter") {
            ListComboBox(
                items = Delimiters.map { it.second },
                selectedIndex = Delimiters.indexOfFirst { it.first == form.delimiter }.coerceAtLeast(0),
                onSelectedItemChange = { form.delimiter = Delimiters[it].first },
                modifier = Modifier.width(180.dp),
            )
        }
        FormRow("Encoding") {
            ListComboBox(
                items = Encodings.map { it ?: "Detect" },
                selectedIndex = Encodings.indexOf(form.charset).coerceAtLeast(0),
                onSelectedItemChange = { form.charset = Encodings[it] },
                modifier = Modifier.width(180.dp),
            )
        }
    }
    if (form.type != DatabaseType.JsonFile) {
        CheckboxRow("The first row names the columns", form.hasHeader, onCheckedChange = { form.hasHeader = it })
    }
    Text(
        "Opens as tables you can query with SQL and edit. Committed changes are saved to the file straight away, " +
            "keeping its format; changes made to the file elsewhere are picked up.",
        color = JewelTheme.globalColors.text.info,
    )
}

private val Delimiters = listOf(null to "Detect", ',' to "Comma ,", ';' to "Semicolon ;", '\t' to "Tab", '|' to "Pipe |")
private val Encodings = listOf(null, "UTF-8", "windows-1252", "ISO-8859-1", "UTF-16LE", "UTF-16BE", "Shift_JIS", "GB18030", "windows-1258")

/** A file chooser that shows the files a data file profile opens. */
private fun chooseDataFile(): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a Data File", java.awt.FileDialog.LOAD)
    val extensions = DataFileFormats.all.flatMap { it.extensions }.toSet()
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it).path }
}

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
            Text(
                if (config.type.isDataFile) "Data files open in a SQLite working copy, and ${driver.displayName} isn't on this computer yet. It's a one-time download."
                else "“${config.name}” uses ${driver.displayName}, which isn't on this computer yet.",
            )
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

/** Names a group: one to move connections into (blank takes them out of their group), or a group's new name. */
@Composable
internal fun GroupDialog(state: ConnectionsViewModel, request: GroupRequest) {
    val renaming = request.renaming
    val group = remember(request) { TextFieldState(renaming ?: if (request.newGroup) "" else request.configs.singleOrNull()?.group.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        val name = group.text.toString()
        if (renaming != null) state.renameGroup(renaming, name)
        else if (!request.newGroup || name.isNotBlank()) state.moveToGroup(request.configs, name)
    }
    val title =
        when {
            renaming != null -> "Rename Group"
            request.newGroup -> "New Group"
            else -> "Move to Group"
        }
    AppDialog(onCloseRequest = { state.groupRequest = null }, size = DpSize(440.dp, 210.dp), title = title, resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) true.also { submit() } else false
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val placeholder = if (renaming != null || request.newGroup) "e.g. a project or client" else "Leave blank for no group"
            FormRow("Group") { TextField(group, Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text(placeholder) }) }
            val existing = state.groups.filter { it != renaming && it != request.configs.singleOrNull()?.group }
            if (existing.isNotEmpty() && renaming == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Existing:", color = JewelTheme.globalColors.text.info)
                    existing.take(6).forEach { name -> Link(name, onClick = { group.setTextAndPlaceCursorAtEnd(name) }) }
                }
            } else if (request.configs.size > 1) {
                Text("${request.configs.size} connections", color = JewelTheme.globalColors.text.info)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.groupRequest = null }) { Text("Cancel") }
                DefaultButton(onClick = ::submit) { Text(if (renaming != null) "Rename" else if (request.newGroup) "Create" else "Move") }
            }
        }
    }
}
