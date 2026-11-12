package dev.tablegrip.app.connections

import dev.tablegrip.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.tablegrip.core.connection.AgentWrites
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
import dev.tablegrip.core.connection.ClientApi
import dev.tablegrip.core.connection.EngineFamily
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
import org.jetbrains.jewel.ui.Outline
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
    /** Asks for the password on every connect; the field then only serves Test Connection. */
    var askPassword by mutableStateOf(existing?.askPassword ?: false)

    /** TLS, for drivers with an [SslSpec]; its properties are taken out of [properties] and put back on save. */
    var sslMode by mutableStateOf<SslMode?>(null)
    val sslCa = TextFieldState()
    val sslCert = TextFieldState()
    val sslKey = TextFieldState()

    val sslSpec: SslSpec?
        get() = SslSpecs[driver].takeIf { isServer && !dataFile }

    val ssl: SslSettings
        get() = SslSettings(sslMode?.takeIf { sslSpec?.modes?.containsKey(it) == true }, sslCa.text.toString(), sslCert.text.toString(), sslKey.text.toString())

    init {
        absorbSsl()
    }

    fun stateOf(field: Field): TextFieldState =
        when (field) {
            Field.Url -> url
            Field.Database -> database
            Field.Host -> host
            Field.Port -> port
            Field.SshHost -> sshHost
            Field.SshUser -> sshUser
            Field.SshPort -> sshPort
            Field.SslCa -> sslCa
            Field.SslCert -> sslCert
            Field.SslKey -> sslKey
            Field.Properties -> properties
        }

    /** Moves the TLS properties the driver's [SslSpec] knows from the properties text to the SSL tab. */
    fun absorbSsl() {
        val spec = sslSpec ?: return
        val all = parseProperties().getOrNull() ?: return
        val (settings, rest) = SslSettings.extract(spec, all)
        if (!settings.isSet) return
        settings.mode?.let { sslMode = it }
        if (settings.ca.isNotEmpty()) sslCa.setTextAndPlaceCursorAtEnd(settings.ca)
        if (settings.cert.isNotEmpty()) sslCert.setTextAndPlaceCursorAtEnd(settings.cert)
        if (settings.key.isNotEmpty()) sslKey.setTextAndPlaceCursorAtEnd(settings.key)
        properties.setTextAndPlaceCursorAtEnd(rest.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    var environment by mutableStateOf(existing?.environment ?: Environment.None)
    val group = TextFieldState(existing?.group ?: group)
    private val favorite = existing?.favorite ?: false
    var readOnly by mutableStateOf(existing?.readOnly ?: false)
    var confirmWrites by mutableStateOf(existing?.confirmWrites ?: false)
    /** Null follows the app-wide setting. */
    var aiAccess by mutableStateOf(existing?.aiAccess)
    var agentWrites by mutableStateOf(existing?.agentWrites ?: AgentWrites.None)

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
        get() = type.api == ClientApi.Relational

    /** MongoDB and the like: a database field and a connection string, but nothing JDBC. */
    val document: Boolean
        get() = type.api == ClientApi.Document

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
            absorbSsl()
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
            require('=' in line) { tr("Driver property \"{0}\" must look like key=value.", line) }
            line.substringBefore('=').trim() to line.substringAfter('=').trim()
        }
    }

    fun validate(drivers: DriverDownloads): Invalid? {
        if (dataFile) return validateDataFile()
        val port = port.text.toString()
        parseProperties().exceptionOrNull()?.let { return Invalid(it.message.orEmpty(), Field.Properties) }
        if (useUrl && document) {
            val raw = url.text.toString().trim()
            return when {
                raw.isEmpty() -> Invalid(tr("Connection string is required."), Field.Url)
                !raw.startsWith("mongodb://") && !raw.startsWith("mongodb+srv://") -> Invalid(tr("A connection string starts with mongodb:// or mongodb+srv://."), Field.Url)
                ssh -> Invalid(tr("SSH tunnels need host and port, not a connection string."), Field.Url)
                else -> null
            }
        }
        if (useUrl) {
            val raw = url.text.toString().trim()
            return when {
                raw.isEmpty() -> Invalid(tr("JDBC URL is required."), Field.Url)
                !raw.startsWith("jdbc:") -> Invalid(tr("A JDBC URL starts with jdbc: — paste other formats into Connection string."), Field.Url)
                ssh && runCatching { JdbcUrls.build(toConfig(), via = "127.0.0.1" to 1) }.isFailure -> Invalid(tr("SSH tunnels need a single-host URL."), Field.Url)
                // Only checkable once the driver is here; until then the driver banner asks for it.
                drivers.library.resolve(toConfig()) is DriverResolution.Ready && !drivers.library.load(toConfig()).acceptsURL(JdbcUrls.build(toConfig())) ->
                    Invalid(tr("{0} doesn't accept this URL.", driver.displayName), Field.Url)
                else -> validateSsl()
            }
        }
        return when {
            relational && database.text.isBlank() -> Invalid(if (isServer) tr("Database is required.") else tr("Database file is required."), Field.Database)
            type.family == EngineFamily.InfluxDB && database.text.isBlank() -> Invalid(tr("Organization is required."), Field.Database)
            fileMismatch() != null -> Invalid(fileMismatch()!!, Field.Database)
            isServer && host.text.isBlank() -> Invalid(tr("Host is required."), Field.Host)
            ssh && ',' in host.text -> Invalid(tr("SSH tunnels need a single host."), Field.Host)
            isServer && port.isNotEmpty() && port.toIntOrNull() !in 1..65535 -> Invalid(tr("Port must be between 1 and 65535."), Field.Port)
            ssh && sshHost.text.isBlank() -> Invalid(tr("SSH host is required."), Field.SshHost)
            ssh && sshUser.text.isBlank() -> Invalid(tr("SSH user is required."), Field.SshUser)
            ssh && sshPort.text.toString().toIntOrNull() !in 1..65535 -> Invalid(tr("SSH port must be between 1 and 65535."), Field.SshPort)
            else -> validateSsl()
        }
    }

    /** Certificate files named on the SSL tab must be there, or the driver fails with a far vaguer message. */
    private fun validateSsl(): Invalid? {
        val spec = sslSpec ?: return null
        fun missing(state: TextFieldState) = state.text.toString().trim().let { it.isNotEmpty() && !java.nio.file.Files.isRegularFile(runCatching { Path.of(it) }.getOrNull() ?: return true) }
        return when {
            spec.caKey != null && missing(sslCa) -> Invalid(tr("CA certificate {0} doesn't exist.", sslCa.text.toString().trim()), Field.SslCa)
            spec.certKey != null && missing(sslCert) -> Invalid(tr("Client certificate {0} doesn't exist.", sslCert.text.toString().trim()), Field.SslCert)
            spec.keyKey != null && missing(sslKey) -> Invalid(tr("Client key {0} doesn't exist.", sslKey.text.toString().trim()), Field.SslKey)
            spec.certKey != null && sslCert.text.isNotBlank() != sslKey.text.isNotBlank() -> Invalid(tr("A client certificate needs its key, and a key its certificate."), if (sslCert.text.isBlank()) Field.SslCert else Field.SslKey)
            else -> null
        }
    }

    private fun validateDataFile(): Invalid? {
        val raw = database.text.toString().trim()
        if (raw.isEmpty()) return Invalid(tr("File is required."), Field.Database)
        val path = runCatching { DataFileStore.pathOf(raw) }.getOrNull() ?: return Invalid(tr("That isn't a valid path."), Field.Database)
        if (!java.nio.file.Files.isRegularFile(path)) return Invalid(tr("{0} doesn't exist or isn't a file.", path), Field.Database)
        val format = DataFileFormats.detect(path)
        return when {
            format != null && format.type != type -> Invalid(tr("{0} looks like a {1}; choose that type, or rename the file.", path.fileName, format.type.displayName), Field.Database)
            charset != null && runCatching { java.nio.charset.Charset.forName(charset) }.isFailure -> Invalid(tr("Unknown encoding \"{0}\".", charset), null)
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
            properties = if (dataFile) emptyMap() else parseProperties().getOrDefault(emptyMap()) + sslSpec?.let(ssl::toProperties).orEmpty(),
            url = url.text.toString().trim().takeIf { useUrl && it.isNotEmpty() && !dataFile },
            dataFile = if (dataFile) DataFileOptions(delimiter = delimiter, hasHeader = hasHeader, charset = charset) else null,
            environment = environment,
            group = group.text.toString().trim(),
            favorite = favorite,
            readOnly = readOnly,
            confirmWrites = confirmWrites,
            aiAccess = aiAccess,
            agentWrites = agentWrites,
            askPassword = askPassword && isServer,
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

private enum class DialogTab { General, Ssh, Ssl, Options }

/** A form field a validation error can point at, and the tab it's on. */
private enum class Field(val tab: DialogTab) {
    Url(DialogTab.General), Database(DialogTab.General), Host(DialogTab.General), Port(DialogTab.General),
    SshHost(DialogTab.Ssh), SshUser(DialogTab.Ssh), SshPort(DialogTab.Ssh),
    SslCa(DialogTab.Ssl), SslCert(DialogTab.Ssl), SslKey(DialogTab.Ssl),
    Properties(DialogTab.Options),
}

/** Why the form can't be saved, and the field to fix (null when it isn't a single field). */
private data class Invalid(val message: String, val field: Field?)

/** The field the last failed validation pointed at, outlined until it's edited; read by [outlineOf]. */
private val LocalInvalidField = androidx.compose.runtime.compositionLocalOf<Field?> { null }

@Composable
private fun outlineOf(field: Field): Outline = if (LocalInvalidField.current == field) Outline.Error else Outline.None

@Composable
internal fun ConnectionDialog(state: ConnectionsViewModel, request: ConnectionDialogRequest) {
    AppDialog(
        onCloseRequest = state::closeDialog,
        size = DpSize(640.dp, 600.dp),
        title = if (request.existing == null) tr("New Connection") else tr("Edit Connection"),
        resizable = false,
    ) {
        ConnectionDialogContent(state, request)
    }
}

/** The dialog body, separate from its window so it can be rendered in tests. */
@Composable
internal fun ConnectionDialogContent(state: ConnectionsViewModel, request: ConnectionDialogRequest) {
    val form = remember(request) { ConnectionForm(request.existing, request.group).also { f -> request.type?.let(f::changeType) } }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Message?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(DialogTab.General) }
    /** The field the last validation blamed, and its text then: outlined until the user changes it. */
    var invalid by remember { mutableStateOf<Pair<Field, String>?>(null) }

    LaunchedEffect(request) {
        val id = request.existing?.id ?: return@LaunchedEffect
        state.storedPassword(id)?.let { form.password.setTextAndPlaceCursorAtEnd(it) }
        state.storedSshSecret(id)?.let { form.sshSecret.setTextAndPlaceCursorAtEnd(it) }
    }

    fun submit(test: Boolean) {
        form.moveUrlPassword()
        invalid = null
        form.validate(state.drivers)?.let {
            message = Message(it.message, isError = true)
            // Take the user to the field: an SSH error found while on General would otherwise be hard to place.
            it.field?.let { field ->
                tab = field.tab
                invalid = field to form.stateOf(field).text.toString()
            }
            return
        }
        busy = true
        message = Message(if (test) tr("Connecting…") else tr("Saving…"), isError = false)
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
                                    Message(tr("Connection succeeded. This server is {0}.", detected.displayName), isError = false, action = tr("Use {0}", detected.displayName) to { form.changeType(detected) })
                                outcome.server != null -> Message(tr("Connected to {0}.", outcome.server), isError = false)
                                else -> Message(tr("Connection succeeded."), isError = false)
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
                    .filter { (it != DialogTab.Ssh || form.isServer) && (it != DialogTab.Ssl || form.sslSpec != null) }
                    .map { entry ->
                        TabData.Default(
                            selected = entry == tab,
                            content = { tabState ->
                                SimpleTabContent(
                                    when (entry) {
                                        DialogTab.General -> tr("General")
                                        DialogTab.Ssh -> if (form.useSsh) tr("SSH ✓") else tr("SSH")
                                        DialogTab.Ssl -> if (form.ssl.isSet) tr("SSL ✓") else tr("SSL")
                                        DialogTab.Options -> tr("Options")
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
        // Scrolls when a tab holds more than fits, so nothing at the bottom (driver properties) is cut off.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val invalidField = invalid?.let { (field, text) -> field.takeIf { form.stateOf(it).text.toString() == text } }
            val shown =
                when {
                    tab == DialogTab.Ssh && !form.isServer -> DialogTab.General
                    tab == DialogTab.Ssl && form.sslSpec == null -> DialogTab.General
                    else -> tab
                }
            androidx.compose.runtime.CompositionLocalProvider(LocalInvalidField provides invalidField) {
                when (shown) {
                    DialogTab.General -> GeneralTab(form, state.drivers) { message = it }
                    DialogTab.Ssh -> SshTab(form)
                    DialogTab.Ssl -> SslTab(form)
                    DialogTab.Options -> OptionsTab(form)
                }
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
                OutlinedButton(onClick = { submit(test = true) }, enabled = !busy) { Text(tr("Test Connection")) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = state::closeDialog) { Text(tr("Cancel")) }
                DefaultButton(onClick = { submit(test = false) }, enabled = !busy) { Text(tr("Save")) }
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
            onMessage(Message(tr("Opens {0} as a {1}.", form.database.text.toString().substringAfterLast('/'), form.type.displayName), isError = false))
            return
        }
        onMessage(
            runCatching { ConnectionString.parse(text) }
                .fold(
                    onSuccess = { parsed ->
                        val warnings = form.apply(parsed)
                        form.connectionString.setTextAndPlaceCursorAtEnd("")
                        Message((listOf(tr("Filled in from the connection string.")) + warnings).joinToString("\n"), isError = false)
                    },
                    onFailure = { Message(it.message ?: tr("Couldn't parse the connection string."), isError = true) },
                )
        )
    }
    FormRow(tr("Paste")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                form.connectionString,
                Modifier.weight(1f),
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text("postgres://user:pass@host/db, mongodb+srv://…, jdbc:…, path.db, data.csv") },
                onKeyboardAction = KeyboardActionHandler { parse() },
            )
            OutlinedButton(onClick = ::parse) { Text(tr("Parse")) }
        }
    }
    FormRow(tr("Name")) { TextField(form.name, Modifier.fillMaxWidth(), placeholder = { Text(tr("Optional")) }) }
    FormRow(tr("Type")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EngineComboBox(form.type, onChange = form::changeType, modifier = Modifier.width(180.dp))
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
        // MariaDB Connector/J stands in for MySQL's own driver, except on engines it can't talk to (OceanBase).
        onSwitch = { driver: DriverId -> form.driver = driver }.takeIf { DriverId.MariaDB in form.type.drivers },
        onMessage = { onMessage(Message(it, isError = true)) },
        profileJars = form.driverJars,
        onProfileJars = { form.driverJars = it },
    )
    if (form.dataFile) {
        DataFileFields(form)
        return
    }
    if (!form.urlOnly && (form.relational || form.document)) FormRow(tr("Connect by")) {
        ListComboBox(
            items = listOf(if (form.isServer) tr("Host and port") else tr("File"), if (form.document) tr("Connection string") else tr("JDBC URL")),
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
        FormRow(tr("URL")) { TextField(form.url, Modifier.fillMaxWidth(), textStyle = JewelTheme.editorTextStyle, outline = outlineOf(Field.Url), placeholder = { Text(example) }) }
        if (form.isServer || form.urlOnly) {
            FormRow(tr("User")) { TextField(form.user, Modifier.fillMaxWidth()) }
            PasswordRows(form, tr("Password"))
        }
        return
    }
    if (form.isServer) {
        FormRow(tr("Host")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(form.host, Modifier.weight(1f), outline = outlineOf(Field.Host), placeholder = { Text(tr("host, or host1:port1,host2:port2")) })
                Text(tr("Port"))
                TextField(form.port, Modifier.width(80.dp), outline = outlineOf(Field.Port), placeholder = { Text(form.type.defaultPort.toString()) })
            }
        }
        if (form.relational) FormRow(tr("Database")) { TextField(form.database, Modifier.fillMaxWidth(), outline = outlineOf(Field.Database)) }
        if (form.type.family == EngineFamily.Neo4j) FormRow(tr("Database")) { TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text("neo4j") }) }
        if (form.type.family == EngineFamily.Milvus) FormRow(tr("Database")) { TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text("default") }) }
        if (form.type.family == EngineFamily.Chroma) FormRow(tr("Database")) { TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text("default_tenant/default_database") }) }
        if (form.type.family == EngineFamily.Cassandra) FormRow(tr("Keyspace")) { TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text(tr("Optional: opened first")) }) }
        if (form.type.family == EngineFamily.InfluxDB) FormRow(tr("Organization")) { TextField(form.database, Modifier.fillMaxWidth(), outline = outlineOf(Field.Database)) }
        if (form.document) FormRow(tr("Database")) { TextField(form.database, Modifier.fillMaxWidth(), placeholder = { Text(tr("Optional: opened first, and the default auth source")) }) }
        // Meilisearch and InfluxDB sign in with a key or token alone, DynamoDB with an AWS access key.
        val keyOnly = form.type.family in setOf(EngineFamily.Meilisearch, EngineFamily.InfluxDB, EngineFamily.InfluxDB3, EngineFamily.Qdrant, EngineFamily.Consul, EngineFamily.Weaviate, EngineFamily.Chroma) || form.type == DatabaseType.Libsql
        val aws = form.type.family == EngineFamily.DynamoDB
        if (!keyOnly) FormRow(if (aws) tr("Access key ID") else tr("User")) { TextField(form.user, Modifier.fillMaxWidth()) }
        PasswordRows(form, if (keyOnly) tr("API key") else if (aws) tr("Secret access key") else tr("Password"))
    } else {
        DatabaseFileRow(form)
    }
    val preview = if (form.relational) runCatching { JdbcUrls.build(form.toConfig()) }.getOrNull() else null
    if (preview != null) {
        FormRow(tr("JDBC URL")) {
            Text(preview, color = JewelTheme.globalColors.text.info, style = JewelTheme.editorTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SshTab(form: ConnectionForm) {
    CheckboxRow(tr("Connect through an SSH tunnel"), form.useSsh, onCheckedChange = { form.useSsh = it })
    if (!form.useSsh) return
    val sshHosts by produceState(emptyList<SshHost>()) { value = withContext(Dispatchers.IO) { runCatching { SshHosts.read() }.getOrDefault(emptyList()) } }
    if (sshHosts.isNotEmpty()) {
        var picked by remember { mutableStateOf(0) }
        FormRow(tr("From config")) {
            ListComboBox(
                items = listOf(tr("Choose a host in ~/.ssh/config…")) + sshHosts.map { if (it.hostName == it.alias) it.alias else "${it.alias}  (${it.hostName})" },
                selectedIndex = picked,
                onSelectedItemChange = { index ->
                    picked = index
                    sshHosts.getOrNull(index - 1)?.let(form::applySshHost)
                },
                modifier = Modifier.width(320.dp),
            )
        }
    }
    FormRow(tr("Host")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(form.sshHost, Modifier.weight(1f), outline = outlineOf(Field.SshHost), placeholder = { Text("bastion.example.com") })
            Text(tr("Port"))
            TextField(form.sshPort, Modifier.width(80.dp), outline = outlineOf(Field.SshPort))
        }
    }
    FormRow(tr("User")) { TextField(form.sshUser, Modifier.fillMaxWidth(), outline = outlineOf(Field.SshUser)) }
    FormRow(tr("Auth")) {
        ListComboBox(
            items = listOf(tr("Key pair"), tr("Password")),
            selectedIndex = if (form.sshAuth == SshAuth.PrivateKey) 0 else 1,
            onSelectedItemChange = { form.sshAuth = if (it == 0) SshAuth.PrivateKey else SshAuth.Password },
            modifier = Modifier.width(180.dp),
        )
    }
    if (form.sshAuth == SshAuth.PrivateKey) {
        FormRow(tr("Key file")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(form.sshKeyPath, Modifier.weight(1f), placeholder = { Text(tr("Default keys in ~/.ssh")) })
                OutlinedButton(onClick = { chooseFile(tr("Private key"), directory = System.getProperty("user.home") + "/.ssh")?.let { form.sshKeyPath.setTextAndPlaceCursorAtEnd(it) } }) { Text(tr("Browse…")) }
            }
        }
        FormRow(tr("Passphrase")) { SecretField(form.sshSecret, Modifier.fillMaxWidth(), placeholder = tr("If the key has one")) }
    } else {
        FormRow(tr("Password")) { SecretField(form.sshSecret, Modifier.fillMaxWidth()) }
    }
    Text(
        tr("The server's host key is trusted on first connect and pinned; a changed key is refused."),
        color = JewelTheme.globalColors.text.info,
    )
}

@Composable
private fun OptionsTab(form: ConnectionForm) {
    FormRow(tr("Environment")) {
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
    Text(tr("Colours the connection everywhere it appears, so production stands out."), color = JewelTheme.globalColors.text.info)
    FormRow(tr("Group")) { TextField(form.group, Modifier.width(240.dp), placeholder = { Text(tr("e.g. a project or client")) }) }
    Spacer(Modifier.height(4.dp))
    CheckboxRow(tr("Read-only: refuse statements that change data or schema"), form.readOnly, onCheckedChange = { form.readOnly = it })
    CheckboxRow(tr("Confirm before running statements that change data or schema"), form.confirmWrites, onCheckedChange = { form.confirmWrites = it })
    Text(tr("Dangerous statements (DELETE/UPDATE without WHERE, DROP, TRUNCATE) always ask."), color = JewelTheme.globalColors.text.info)
    Spacer(Modifier.height(4.dp))
    FormRow(tr("AI agents")) {
        val choices = listOf(null) + AiAccess.entries
        ListComboBox(
            items = choices.map { it?.label ?: tr("Use default (Settings › Agent)") },
            selectedIndex = choices.indexOf(form.aiAccess),
            onSelectedItemChange = { form.aiAccess = choices[it] },
            modifier = Modifier.width(240.dp),
        )
    }
    Text(
        when {
            form.aiAccess != null -> form.aiAccess!!.description
            form.environment == Environment.Production -> tr("Follows Settings › Agent, but never shares data from a production connection.")
            else -> tr("Follows Settings › Agent.")
        },
        color = JewelTheme.globalColors.text.info,
    )
    if (form.aiAccess == AiAccess.SchemaAndSamples && form.environment == Environment.Production) {
        Text(tr("Values from this production database may be sent to the agent's provider."), color = JewelTheme.globalColors.text.warning)
    }
    if (!form.dataFile) {
        FormRow(tr("Agent writes")) {
            ListComboBox(
                items = AgentWrites.entries.map { it.label },
                selectedIndex = form.agentWrites.ordinal,
                onSelectedItemChange = { form.agentWrites = AgentWrites.entries[it] },
                enabled = !form.readOnly,
                modifier = Modifier.width(240.dp),
            )
        }
        Text(
            if (form.readOnly) tr("Read-only connections never let agents write.")
            else tr("What AI apps connected over MCP may change. They also need the data shared above.") + " " + form.agentWrites.description,
            color = JewelTheme.globalColors.text.info,
        )
        if (!form.readOnly && form.agentWrites.allowsData && form.environment == Environment.Production) {
            Text(tr("Every write an agent makes on this production connection asks first."), color = JewelTheme.globalColors.text.warning)
        }
    }
    if (form.dataFile) {
        Text(tr("Data files are read into a working copy; every committed change is written back to the file."), color = JewelTheme.globalColors.text.info)
        return
    }
    Spacer(Modifier.height(4.dp))
    Text(tr("Driver properties ({0})", form.driver.displayName))
    if (form.sslSpec != null) Text(tr("TLS has its own tab; what it sets wins over the same property here."), color = JewelTheme.globalColors.text.info)
    TextArea(
        form.properties,
        Modifier.fillMaxWidth().height(96.dp),
        outline = outlineOf(Field.Properties),
        textStyle = JewelTheme.editorTextStyle,
        placeholder = { Text(PropertyExamples[form.driver].orEmpty()) },
    )
}

/**
 * The password, with a way to see what was typed, and for servers the choice to be asked on every connect instead of
 * keeping it in the keychain.
 */
@Composable
private fun PasswordRows(form: ConnectionForm, label: String) {
    FormRow(label) {
        SecretField(
            form.password,
            Modifier.fillMaxWidth(),
            placeholder = if (form.askPassword) tr("Only used by Test Connection; asked for on connect") else tr("Saved in the system keychain"),
        )
    }
    if (form.isServer) {
        FormRow("") {
            CheckboxRow(tr("Ask for the password on every connect; don't save it"), form.askPassword, onCheckedChange = { form.askPassword = it })
        }
    }
}

/** A masked field with a Show/Hide toggle, for passwords, keys and passphrases. */
@Composable
internal fun SecretField(state: TextFieldState, modifier: Modifier = Modifier, placeholder: String? = null, onEnter: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    TextField(
        state,
        modifier,
        outputTransformation = if (visible) null else MaskPassword,
        placeholder = placeholder?.let { { Text(it) } },
        onKeyboardAction = onEnter?.let { KeyboardActionHandler { it() } },
        trailingIcon = {
            if (state.text.isNotEmpty()) {
                Link(if (visible) tr("Hide") else tr("Show"), onClick = { visible = !visible }, Modifier.padding(start = 4.dp))
            }
        },
    )
}

/** Engines that create their database file on first connect, so "New…" just picks where. */
private val CreatesFile = setOf(DatabaseType.SQLite, DatabaseType.DuckDB, DatabaseType.H2, DatabaseType.HSQLDB)

/** The database file of an embedded engine: typed, picked, or (for engines that create one) a new file. */
@Composable
private fun DatabaseFileRow(form: ConnectionForm) {
    FormRow(tr("File")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                form.database,
                Modifier.weight(1f),
                outline = outlineOf(Field.Database),
                placeholder = { Text(form.type.databaseHint ?: tr("/path/to/database.sqlite or :memory:")) },
            )
            OutlinedButton(onClick = { chooseDatabaseFile(form.type, create = false)?.let { form.database.setTextAndPlaceCursorAtEnd(it) } }) { Text(tr("Browse…")) }
            if (form.type in CreatesFile) {
                OutlinedButton(onClick = { chooseDatabaseFile(form.type, create = true)?.let { form.database.setTextAndPlaceCursorAtEnd(it) } }) { Text(tr("New…")) }
            }
        }
    }
    if (form.type in CreatesFile && form.database.text.isNotBlank() && !form.database.text.startsWith(":") && !form.database.text.startsWith("mem:")) {
        val path = runCatching { Path.of(databaseFileOnDisk(form.type, form.database.text.toString().trim())) }.getOrNull()
        if (path != null && !java.nio.file.Files.exists(path)) {
            Text(tr("{0} doesn't exist yet; it's created on first connect.", path.fileName), color = JewelTheme.globalColors.text.info)
        }
    }
}

/** H2 and HSQLDB take the path without the extension their files get. */
private fun databaseFileOnDisk(type: DatabaseType, path: String): String =
    when (type) {
        DatabaseType.H2 -> "$path.mv.db"
        DatabaseType.HSQLDB -> "$path.script"
        else -> path
    }

/** Native chooser for an embedded engine's database: a file to open, a new file's location, or Derby's directory. */
private fun chooseDatabaseFile(type: DatabaseType, create: Boolean): String? {
    val directories = type == DatabaseType.Derby
    if (directories) System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, if (create) tr("New {0} Database", type.displayName) else tr("Choose a {0} Database", type.displayName), if (create) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
        if (create) dialog.file = when (type) {
            DatabaseType.DuckDB -> "database.duckdb"
            DatabaseType.SQLite -> "database.sqlite"
            else -> "database"
        }
        dialog.isVisible = true
        val picked = dialog.file?.let { java.io.File(dialog.directory, it).path } ?: return null
        return when (type) {
            DatabaseType.H2 -> picked.removeSuffix(".mv.db")
            DatabaseType.HSQLDB -> picked.removeSuffix(".script").removeSuffix(".properties")
            else -> picked
        }
    } finally {
        if (directories) System.clearProperty("apple.awt.fileDialogForDirectories")
    }
}

/** TLS for drivers with an [SslSpec]: the mode, and the PEM files it verifies with or presents. */
@Composable
private fun SslTab(form: ConnectionForm) {
    val spec = form.sslSpec ?: return
    FormRow(tr("Mode")) {
        val choices = listOf<SslMode?>(null) + SslMode.entries.filter { it in spec.modes }
        ListComboBox(
            items = choices.map { it?.label?.invoke() ?: tr("Driver default") },
            selectedIndex = choices.indexOf(form.ssl.mode).coerceAtLeast(0),
            onSelectedItemChange = { form.sslMode = choices[it] },
            modifier = Modifier.width(340.dp),
        )
    }
    val defaults = form.type.defaultProperties[form.driver]?.get(spec.modeKey)
    Text(
        when {
            form.ssl.mode == null && defaults != null -> tr("{0} sets {1}={2} unless you choose a mode.", form.type.displayName, spec.modeKey, defaults)
            form.ssl.mode == null -> tr("The driver decides; choose Verify full for servers reached over the internet.")
            form.ssl.mode == SslMode.Require || form.ssl.mode == SslMode.Prefer -> tr("Encrypted, but anyone in the middle can pretend to be the server.")
            else -> tr("Sets {0}={1}.", spec.modeKey, spec.modes[form.ssl.mode].orEmpty())
        },
        color = JewelTheme.globalColors.text.info,
    )
    if (spec.caKey != null) PemRow(tr("CA certificate"), form.sslCa, Field.SslCa, tr("Trust this CA, e.g. the provider's root.pem"))
    if (spec.certKey != null) PemRow(tr("Client cert"), form.sslCert, Field.SslCert, tr("For servers that check client certificates"))
    if (spec.keyKey != null) PemRow(tr("Client key"), form.sslKey, Field.SslKey, tr("The client certificate's private key"))
    if (spec.caKey == null) {
        Text(tr("{0} reads certificates only from Java key stores; set them under Options › Driver properties.", form.driver.displayName), color = JewelTheme.globalColors.text.info)
    }
}

@Composable
private fun PemRow(label: String, state: TextFieldState, field: Field, hint: String) {
    FormRow(label) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(state, Modifier.weight(1f), outline = outlineOf(field), placeholder = { Text(hint) })
            OutlinedButton(onClick = { chooseFile(label, directory = null)?.let { state.setTextAndPlaceCursorAtEnd(it) } }) { Text(tr("Browse…")) }
        }
    }
}

/** Commonly needed properties per driver, using each driver's documented names. */
private val PropertyExamples =
    mapOf(
        DriverId.PostgreSQL to "currentSchema=app\nApplicationName=TableGrip\nprepareThreshold=0  (behind PgBouncer)",
        DriverId.MySQL to "allowPublicKeyRetrieval=true\nserverTimezone=UTC",
        DriverId.MariaDB to "allowPublicKeyRetrieval=true\nconnectTimeout=5000",
        DriverId.SQLite to "foreign_keys=true\nbusy_timeout=5000",
        DriverId.Oracle to "internal_logon=sysdba\noracle.net.ssl_server_dn_match=true\noracle.jdbc.timezoneAsRegion=false",
        DriverId.Firebird to "encoding=WIN1252  (the database's character set)\nwireCrypt=REQUIRED\ncreateDatabaseIfNotExist=true",
        DriverId.Trino to "SSL=true\nroles=system:admin\nsessionProperties=query_max_run_time:10m",
        DriverId.Presto to "SSL=true\nroles=hive:admin\nsessionProperties=query_max_run_time:10m",
        DriverId.Snowflake to "warehouse=COMPUTE_WH\nrole=ANALYST\nauthenticator=externalbrowser",
        DriverId.Databricks to "ConnCatalog=main\nConnSchema=default\nAuthMech=11 + Auth_Flow=2  (browser sign-in)",
        DriverId.BigQuery to "OAuthType=0 + OAuthServiceAcctEmail=… + OAuthPvtKeyPath=/path/key.json\nLocation=EU",
        DriverId.Spanner to "autoConfigEmulator=true  (the emulator)\ncredentials=/path/key.json",
        DriverId.Db2 to "sslConnection=true\ncurrentSchema=APP",
        DriverId.Hana to "encrypt=true  (HANA Cloud)\ncurrentschema=APP",
        DriverId.Exasol to "fingerprint=…  (self-signed certificates)\nvalidateservercertificate=0",
        DriverId.Informix to "INFORMIXSERVER=informix\nDB_LOCALE=en_US.utf8",
        DriverId.Hive to "auth=noSasl  (Impala without authentication)\nhive.server2.proxy.user=analyst",
        DriverId.UCanAccess to "memory=false  (large files)\nnewDatabaseVersion=V2010  (create the file)",
        DriverId.MongoDB to "authSource=admin\ntls=true\nreplicaSet=rs0",
        DriverId.Etcd to "ssl=true\nverifyPeer=false  (self-signed certificates)",
        DriverId.ElasticRest to "ssl=true\nverifyPeer=false  (self-signed certificates)\napiKey=…  (instead of a user and password)\nshowHidden=true  (indices starting with a dot)",
        DriverId.Meilisearch to "ssl=true\npathPrefix=/search  (behind a reverse proxy)",
        DriverId.CouchDB to "ssl=true\nshowSystem=true  (_users, _replicator)",
        DriverId.DynamoDB to "region=eu-west-1\nsessionToken=…  (temporary credentials)\nssl=false  (DynamoDB Local on port 443)",
        DriverId.InfluxDB to "ssl=true\nshowSystem=true  (_monitoring, _tasks)",
        DriverId.Prometheus to "bearerToken=…\nssl=true\npathPrefix=/select/0/prometheus  (cluster vmselect)",
        DriverId.Neo4j to "ssl=true\nverifyPeer=false  (self-signed certificates)",
        DriverId.Kafka to "ssl=true\nsasl.mechanism=SCRAM-SHA-512\nsecurity.protocol=SASL_SSL",
        DriverId.Consul to "datacenter=dc1\nssl=true",
        DriverId.Libsql to "ssl=true  (Turso: port 443, the database's host, its token as the API key)",
        DriverId.Cassandra to "localDatacenter=datacenter1\nssl=true\nshowSystem=true  (system keyspaces)",
        DriverId.Lettuce to "ssl=true\nmode=cluster  (hosts are seed nodes)\nmode=sentinel + sentinelMaster=mymaster",
    )

/** The file and how to read it: delimiter and encoding for CSV, whether the first row is the header. */
@Composable
private fun DataFileFields(form: ConnectionForm) {
    FormRow(tr("File")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(form.database, Modifier.weight(1f), outline = outlineOf(Field.Database), placeholder = { Text(form.type.databaseHint.orEmpty()) })
            OutlinedButton(onClick = { chooseDataFile()?.let(form::useDataFile) }) { Text(tr("Browse…")) }
        }
    }
    if (form.type == DatabaseType.CsvFile) {
        FormRow(tr("Delimiter")) {
            ListComboBox(
                items = Delimiters.map { it.second },
                selectedIndex = Delimiters.indexOfFirst { it.first == form.delimiter }.coerceAtLeast(0),
                onSelectedItemChange = { form.delimiter = Delimiters[it].first },
                modifier = Modifier.width(180.dp),
            )
        }
        FormRow(tr("Encoding")) {
            ListComboBox(
                items = Encodings.map { it ?: tr("Detect") },
                selectedIndex = Encodings.indexOf(form.charset).coerceAtLeast(0),
                onSelectedItemChange = { form.charset = Encodings[it] },
                modifier = Modifier.width(180.dp),
            )
        }
    }
    if (form.type != DatabaseType.JsonFile) {
        CheckboxRow(tr("The first row names the columns"), form.hasHeader, onCheckedChange = { form.hasHeader = it })
    }
    Text(
        tr("Opens as tables you can query with SQL and edit. Committed changes are saved to the file straight away, ") +
            tr("keeping its format; changes made to the file elsewhere are picked up."),
        color = JewelTheme.globalColors.text.info,
    )
}

private val Delimiters = listOf(null to tr("Detect"), ',' to tr("Comma ,"), ';' to tr("Semicolon ;"), '\t' to tr("Tab"), '|' to tr("Pipe |"))
private val Encodings = listOf(null, "UTF-8", "windows-1252", "ISO-8859-1", "UTF-16LE", "UTF-16BE", "Shift_JIS", "GB18030", "windows-1258")

/** A file chooser that shows the files a data file profile opens. */
private fun chooseDataFile(): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, tr("Choose a Data File"), java.awt.FileDialog.LOAD)
    val extensions = DataFileFormats.all.flatMap { it.extensions }.toSet()
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
    dialog.isVisible = true
    return dialog.file?.let { java.io.File(dialog.directory, it).path }
}

/** Native file chooser; returns the picked path or null. */
private fun chooseFile(title: String, directory: String?): String? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    directory?.let { dialog.directory = it }
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
        title = tr("Delete Connection"),
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Delete “{0}”? Its saved password will be removed from the keychain too.", config.name))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.pendingDelete = null }) { Text(tr("Cancel")) }
                DefaultButton(onClick = { state.delete(config) }) { Text(tr("Delete")) }
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
        title = tr("Driver Needed"),
        resizable = false,
    ) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (config.type.isDataFile) tr("Data files open in a SQLite working copy, and {0} isn't on this computer yet. It's a one-time download.", driver.displayName)
                else tr("“{0}” uses {1}, which isn't on this computer yet.", config.name, driver.displayName),
            )
            DriverBanner(
                driver,
                state.drivers,
                Modifier.fillMaxWidth(),
                onSwitch = { it: DriverId -> driver = it }.takeIf { DriverId.MariaDB in config.type.drivers },
                onMessage = { error = it },
                profileJars = jars,
                onProfileJars = { jars = it },
            )
            error?.let { Text(it, color = JewelTheme.globalColors.text.error, maxLines = 3) }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.driverPrompt = null }) { Text(tr("Cancel")) }
                DefaultButton(onClick = ::connect, enabled = ready) { Text(tr("Connect")) }
            }
        }
    }
}

/** Asks for the password of a connection that doesn't keep it; Enter connects, closing cancels. */
@Composable
internal fun PasswordPromptDialog(state: ConnectionsViewModel, prompt: PasswordPrompt) {
    val password = remember(prompt) { TextFieldState() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt) { focus.requestFocus() }
    val config = prompt.config
    fun submit() = state.answerPassword(prompt, password.text.toString())
    AppDialog(onCloseRequest = { state.answerPassword(prompt, null) }, size = DpSize(440.dp, 190.dp), title = tr("Password Needed"), resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val target = if (config.user.isBlank()) config.host else "${config.user}@${config.host}"
            Text(tr("“{0}” doesn't save its password. Enter it to connect to {1}.", config.name, target))
            SecretField(password, Modifier.fillMaxWidth().focusRequester(focus), onEnter = ::submit)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.answerPassword(prompt, null) }) { Text(tr("Cancel")) }
                DefaultButton(onClick = ::submit) { Text(tr("Connect")) }
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
            renaming != null -> tr("Rename Group")
            request.newGroup -> tr("New Group")
            else -> tr("Move to Group")
        }
    AppDialog(onCloseRequest = { state.groupRequest = null }, size = DpSize(440.dp, 210.dp), title = title, resizable = false) {
        Column(
            Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) true.also { submit() } else false
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val placeholder = if (renaming != null || request.newGroup) tr("e.g. a project or client") else tr("Leave blank for no group")
            FormRow(tr("Group")) { TextField(group, Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text(placeholder) }) }
            val existing = state.groups.filter { it != renaming && it != request.configs.singleOrNull()?.group }
            if (existing.isNotEmpty() && renaming == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Existing:"), color = JewelTheme.globalColors.text.info)
                    existing.take(6).forEach { name -> Link(name, onClick = { group.setTextAndPlaceCursorAtEnd(name) }) }
                }
            } else if (request.configs.size > 1) {
                Text(tr("{0} connections", request.configs.size), color = JewelTheme.globalColors.text.info)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { state.groupRequest = null }) { Text(tr("Cancel")) }
                DefaultButton(onClick = ::submit) { Text(if (renaming != null) tr("Rename") else if (request.newGroup) tr("Create") else tr("Move")) }
            }
        }
    }
}
