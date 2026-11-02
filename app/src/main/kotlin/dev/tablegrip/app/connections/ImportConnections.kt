package dev.tablegrip.app.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.ui.AppDialog
import dev.tablegrip.app.ui.Badge
import dev.tablegrip.app.ui.EnvironmentChip
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.imports.ConnectionImport
import dev.tablegrip.core.imports.ImportCandidate
import dev.tablegrip.core.imports.ImportScan
import dev.tablegrip.core.imports.ImportSource
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.TriStateCheckbox

/** One connection offered for import, and whether the user wants it. */
internal class ImportRow(
    val candidate: ImportCandidate,
    /** A saved profile for the same database: importing would add a second one. */
    val alreadySaved: ConnectionConfig?,
    /** An earlier row, from another tool, for the same database. */
    val duplicateOf: ImportRow?,
) {
    var selected by mutableStateOf(alreadySaved == null && duplicateOf == null)

    /** Another tool may know the password this one didn't keep: duplicates hand theirs to the row that's imported. */
    var password by mutableStateOf(candidate.password)

    val config: ConnectionConfig
        get() = candidate.config

    /** Engines that log in with a password and have none to import. */
    val needsPassword: Boolean
        get() = password == null && config.type.isServer && config.effectiveDriver != DriverId.Etcd
}

/** What one source contributed. */
internal class ImportGroup(val source: ImportSource, val rows: List<ImportRow>, val skipped: List<String>) {
    val state: ToggleableState
        get() =
            when (rows.count { it.selected }) {
                0 -> ToggleableState.Off
                rows.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }

    fun toggle() {
        val select = state != ToggleableState.On
        rows.forEach { it.selected = select }
    }
}

/**
 * The Import Connections dialog's state: the sources found on this machine and those the user added, every
 * connection they hold, and which of them to save. Connections already in TableGrip, or found in two tools, start
 * unticked so importing twice never duplicates anything.
 */
class ConnectionImportState internal constructor(private val connections: ConnectionsViewModel, private val scope: CoroutineScope) {
    internal val groups = mutableStateListOf<ImportGroup>()
    var scanning by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var keepGroups by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)

    internal val rows: List<ImportRow>
        get() = groups.flatMap { it.rows }

    internal val selected: List<ImportRow>
        get() = rows.filter { it.selected }

    /** Reads every source this machine has. */
    fun scanThisComputer() = scan { ConnectionImport.discover() }

    /** A file or folder the user picked: a tool's export, a project, a `.env` file. */
    fun add(path: Path) {
        val source = ConnectionImport.detect(path)
        when {
            source == null -> message = "TableGrip doesn't recognise ${path.fileName} as a connection list."
            groups.any { it.source.path == source.path } -> message = "${path.fileName} is already in the list."
            else -> scan { listOf(source) }
        }
    }

    private fun scan(sources: () -> List<ImportSource>) {
        scanning = true
        message = null
        scope.launch {
            val read =
                withContext(Dispatchers.IO) {
                    val pgpass = ConnectionImport.pgPassFile()
                    sources().map { source ->
                        val scan = ConnectionImport.read(source)
                        source to scan.copy(candidates = ConnectionImport.fillPasswordsFromPgPass(scan.candidates, pgpass))
                    }
                }
            append(read)
            if (read.isNotEmpty() && read.all { it.second.candidates.isEmpty() }) message = "No connections found in ${read.joinToString { it.first.path.fileName.toString() }}."
            scanning = false
        }
    }

    private fun append(read: List<Pair<ImportSource, ImportScan>>) {
        val saved = connections.profiles.associateBy { ConnectionImport.keyOf(it) }
        val seen = rows.associateBy { it.candidate.key }.toMutableMap()
        for ((source, scan) in read) {
            val rows =
                scan.candidates.map { candidate ->
                    val earlier = seen[candidate.key]
                    if (earlier != null && earlier.password == null && candidate.password != null) earlier.password = candidate.password
                    ImportRow(candidate, saved[candidate.key], earlier).also { seen.putIfAbsent(candidate.key, it) }
                }
            if (rows.isNotEmpty() || scan.skipped.isNotEmpty()) groups += ImportGroup(source, rows, scan.skipped)
        }
    }

    /** Saves the ticked connections; returns how many. */
    fun import(onDone: () -> Unit) {
        val chosen = selected
        if (chosen.isEmpty()) return
        saving = true
        scope.launch {
            val items = chosen.map { row ->
                val config = if (keepGroups) row.config else row.config.copy(group = "")
                Triple(config, row.password, row.candidate.sshSecret)
            }
            connections.saveImported(items)
                .onSuccess { onDone() }
                .onFailure { message = "Import failed: ${it.message}" }
            saving = false
        }
    }
}

@Composable
internal fun ImportConnectionsDialog(state: ConnectionsViewModel, import: ConnectionImportState) {
    AppDialog(onCloseRequest = state::closeImport, size = DpSize(780.dp, 580.dp), title = "Import Connections") {
        ImportConnectionsContent(state, import)
    }
}

/** The dialog body, separate from its window so it can be rendered in tests. */
@Composable
internal fun ImportConnectionsContent(state: ConnectionsViewModel, import: ConnectionImportState) {
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "From TablePlus, DBeaver, DataGrip and other JetBrains IDEs, .pgpass, pg_service.conf and .env files. Nothing changes in those tools.",
                Modifier.weight(1f),
                color = JewelTheme.globalColors.text.info,
            )
            Spacer(Modifier.padding(start = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { chooseImportFile()?.let(import::add) }, enabled = !import.scanning) { Text("Add File…") }
                OutlinedButton(onClick = { chooseFolder("Choose a project to look for .env files in")?.let(import::add) }, enabled = !import.scanning) {
                    Text("Scan Project…")
                }
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                import.groups.isEmpty() && import.scanning -> Centered("Looking for connections…")
                import.groups.isEmpty() ->
                    Centered(
                        "No other database tools found on this computer.\n" +
                            "Add a file — TablePlus' Connections.plist, DBeaver's data-sources.json, a .idea/dataSources.xml, .pgpass — " +
                            "or scan a project for .env files.",
                    )
                else -> ImportList(import)
            }
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            import.message?.let { Text(it, color = JewelTheme.globalColors.text.warning, maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckboxRow("Keep folders as groups", import.keepGroups, onCheckedChange = { import.keepGroups = it })
                Spacer(Modifier.weight(1f))
                val count = import.selected.size
                val missing = import.selected.count { it.needsPassword }
                if (missing > 0) {
                    Tooltip({ Text("Their tools keep passwords where TableGrip can't read them. Add one with Edit Connection, or connect without.") }) {
                        Text("$missing without a password", color = JewelTheme.globalColors.text.info)
                    }
                }
                OutlinedButton(onClick = state::closeImport) { Text("Cancel") }
                DefaultButton(onClick = { import.import(state::closeImport) }, enabled = count > 0 && !import.saving && !import.scanning) {
                    Text(if (count == 1) "Import 1 Connection" else "Import $count Connections")
                }
            }
        }
    }
}

@Composable
private fun ImportList(import: ConnectionImportState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
        import.groups.forEach { group ->
            item(key = "group:${group.source.path}") { GroupHeader(group) }
            items(group.rows, key = { it.config.id }) { row -> ImportRowView(row) }
            if (group.skipped.isNotEmpty()) item(key = "skipped:${group.source.path}") { SkippedNote(group.skipped) }
        }
        if (import.scanning) item(key = "scanning") { Text("Looking for connections…", Modifier.padding(16.dp), color = JewelTheme.globalColors.text.info) }
    }
}

@Composable
private fun GroupHeader(group: ImportGroup) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group.rows.isNotEmpty()) TriStateCheckbox(group.state, onClick = group::toggle)
        Text(group.source.label, fontWeight = FontWeight.SemiBold)
        Text(shortPath(group.source.path), Modifier.weight(1f), color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(
            when (group.rows.size) {
                0 -> "nothing to import"
                1 -> "1 connection"
                else -> "${group.rows.size} connections"
            },
            color = JewelTheme.globalColors.text.info,
        )
    }
}

@Composable
private fun ImportRowView(row: ImportRow) {
    val config = row.config
    val details = buildList {
        add(row.candidate.origin.let(::shortOrigin))
        addAll(row.candidate.notes)
        row.alreadySaved?.let { add("Already in TableGrip as “${it.name}”") }
        row.duplicateOf?.let { add("Also found as “${it.config.name}” above") }
        if (row.password != null && row.candidate.password == null) add("Password from another tool")
    }
    Tooltip({ Text(details.joinToString("\n")) }) {
        Row(
            Modifier.fillMaxWidth().clickable { row.selected = !row.selected }.padding(start = 36.dp, end = 16.dp).heightIn(min = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(row.selected, { row.selected = it })
            Badge(config, size = 18)
            Text(config.name, Modifier.widthIn(max = 240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            EnvironmentChip(config)
            Text("${config.type.displayName} · ${address(config)}", Modifier.weight(1f), color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (config.group.isNotEmpty()) Tag(config.group)
            if (config.ssh != null) Tag("SSH")
            if (config.readOnly) Tag("read-only")
            when {
                row.alreadySaved != null -> Tag("already added")
                row.duplicateOf != null -> Tag("duplicate")
                row.needsPassword -> Tag("no password")
            }
        }
    }
}

@Composable
private fun SkippedNote(skipped: List<String>) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 36.dp, end = 16.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Link(if (open) "Hide what was left out" else "${skipped.size} left out — why?", onClick = { open = !open })
        if (open) skipped.forEach { Text(it, color = JewelTheme.globalColors.text.info) }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, color = JewelTheme.globalColors.text.info, maxLines = 1)
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = JewelTheme.globalColors.text.info)
    }
}

/** Where a profile points, compactly: `host:port/db`, a file, or the URL. */
private fun address(config: ConnectionConfig): String =
    when {
        config.url != null -> config.url!!.substringAfter("//").substringBefore('?')
        config.type.isFile -> config.database
        else -> buildString {
            append(config.host)
            config.port?.let { append(':').append(it) }
            if (config.database.isNotEmpty()) append('/').append(config.database)
        }
    }.let { if (config.user.isNotEmpty() && config.type.isServer) "${config.user}@$it" else it }

private val home = System.getProperty("user.home")

private fun shortPath(path: Path): String = path.toString().let { if (it.startsWith(home)) "~" + it.substring(home.length) else it }

private fun shortOrigin(origin: String): String = if (origin.startsWith(home)) "~" + origin.substring(home.length) else origin

/** Native file picker; on macOS ⇧⌘. shows hidden files such as `.pgpass` and `.env`. */
private fun chooseImportFile(): Path? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a file to import connections from", java.awt.FileDialog.LOAD)
    dialog.directory = home
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, it) }
}

/** Connections other tools have on this computer, for the empty explorer to offer. */
data class ImportHint(val count: Int, val tools: List<String>)
