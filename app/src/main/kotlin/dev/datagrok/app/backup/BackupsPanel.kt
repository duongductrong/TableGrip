package dev.datagrok.app.backup

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.app.AppState
import dev.datagrok.core.backup.BackupFrequency
import dev.datagrok.core.backup.BackupSchedule
import dev.datagrok.core.backup.BackupSupport
import dev.datagrok.core.connection.Environment
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/** Settings → Backups: the master switch (off by default), the schedules, and what would stop each from running. */
@Composable
internal fun BackupsPanel(app: AppState) {
    val settings = app.settings
    val info = JewelTheme.globalColors.text.info
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckboxRow(
            "Run scheduled backups while Datagrok is open",
            settings.scheduledBackups,
            onCheckedChange = { on -> app.updateSettings { it.copy(scheduledBackups = on) } },
        )
        Text(
            "Off by default. Backups only run while the app is open; one missed while it was closed runs soon after it opens. " +
                "Each schedule keeps its newest backups and deletes its own older ones, never other files.",
            color = info,
            fontSize = 12.sp,
        )
        settings.backupSchedules.forEach { ScheduleCard(app, it) }
        NewSchedule(app)
    }
}

@Composable
private fun ScheduleCard(app: AppState, schedule: BackupSchedule) {
    val info = JewelTheme.globalColors.text.info
    val config = app.connections.byId(schedule.connectionId)
    val problems by produceState(emptyList<String>(), schedule, app.connections.profiles) { value = app.scheduledBackups.problems(schedule) }
    val scope = rememberCoroutineScope()
    var running by remember(schedule.id) { mutableStateOf(false) }
    fun update(change: (BackupSchedule) -> BackupSchedule) =
        app.updateSettings { s -> s.copy(backupSchedules = s.backupSchedules.map { if (it.id == schedule.id) change(it) else it }) }
    Column(
        Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(config?.name ?: "Deleted connection", fontWeight = FontWeight.SemiBold)
            Text("  ${schedule.database.ifBlank { config?.database ?: "" }}", color = info)
            Spacer(Modifier.weight(1f))
            CheckboxRow("Enabled", schedule.enabled, onCheckedChange = { on -> update { it.copy(enabled = on) } })
        }
        Text(
            listOfNotNull(
                schedule.frequency.label,
                if (schedule.keep > 0) "keeps the newest ${schedule.keep}" else "keeps all",
                app.scheduledBackups.folderOf(schedule).toString(),
                schedule.lastRun?.let { "last run ${Formatter.format(Instant.ofEpochMilli(it))}" } ?: "not run yet",
            ).joinToString("  ·  "),
            color = info,
            fontSize = 12.sp,
        )
        schedule.lastError?.let { Text("Last run failed: $it", color = JewelTheme.globalColors.text.error, fontSize = 12.sp, maxLines = 3) }
        problems.forEach { Text("⚠ $it", color = JewelTheme.globalColors.text.warning, fontSize = 12.sp) }
        if (config?.environment == Environment.Production) Text("Production: each run reads the whole database and adds load to the server.", color = JewelTheme.globalColors.text.warning, fontSize = 12.sp)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Link(if (running) "Running…" else "Run now", enabled = !running, onClick = {
                running = true
                scope.launch {
                    val error = app.scheduledBackups.runOnce(schedule)
                    update { it.copy(lastRun = System.currentTimeMillis(), lastError = error) }
                    running = false
                }
            })
            Link("Show backups", onClick = {
                val folder = app.scheduledBackups.folderOf(schedule)
                runCatching { java.nio.file.Files.createDirectories(folder); java.awt.Desktop.getDesktop().open(folder.toFile()) }
            })
            Link("Remove", onClick = { app.updateSettings { s -> s.copy(backupSchedules = s.backupSchedules.filter { it.id != schedule.id }) } })
        }
    }
}

@Composable
private fun NewSchedule(app: AppState) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        Link("Add schedule…", onClick = { open = true })
        return
    }
    val connections = app.connections.profiles.filter { BackupSupport.of(it.type).let { s -> s is BackupSupport.WithTools || s is BackupSupport.Native } }
    if (connections.isEmpty()) {
        Text("No connection can be backed up yet.", color = JewelTheme.globalColors.text.info)
        return
    }
    var connection by remember { mutableStateOf(connections.first()) }
    var frequency by remember { mutableStateOf(BackupFrequency.Daily) }
    val database = remember { TextFieldState() }
    val keep = remember { TextFieldState("7") }
    Column(
        Modifier.fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", Modifier.width(90.dp))
            ListComboBox(connections.map { it.name }, connections.indexOf(connection), onSelectedItemChange = { connection = connections[it] }, modifier = Modifier.width(240.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Database", Modifier.width(90.dp))
            TextField(database, Modifier.width(240.dp), placeholder = { Text(connection.database.ifBlank { "the connection's database" }) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Frequency", Modifier.width(90.dp))
            ListComboBox(BackupFrequency.entries.map { it.label }, BackupFrequency.entries.indexOf(frequency), onSelectedItemChange = { frequency = BackupFrequency.entries[it] }, modifier = Modifier.width(160.dp))
            Text("Keep", Modifier.padding(start = 12.dp))
            TextField(keep, Modifier.width(60.dp))
        }
        if (connection.environment == Environment.Production) {
            Text("${connection.name} is Production: scheduled backups add load to that server.", color = JewelTheme.globalColors.text.warning, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = {
                val schedule =
                    BackupSchedule(
                        id = UUID.randomUUID().toString().take(8),
                        connectionId = connection.id,
                        database = database.text.toString().trim(),
                        frequency = frequency,
                        keep = keep.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 7,
                    )
                app.updateSettings { it.copy(backupSchedules = it.backupSchedules + schedule) }
                open = false
            }) { Text("Add") }
            OutlinedButton(onClick = { open = false }) { Text("Cancel") }
        }
    }
}

private val Formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
