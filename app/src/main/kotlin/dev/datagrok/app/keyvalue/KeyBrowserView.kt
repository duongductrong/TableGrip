package dev.datagrok.app.keyvalue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.datagrok.app.ui.copyToClipboard
import dev.datagrok.core.driver.KeyEdit
import dev.datagrok.core.driver.KeyInfo
import dev.datagrok.core.driver.KeyType
import dev.datagrok.core.driver.KeyValue
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.ListComboBox
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun KeyBrowserView(tab: KeyBrowserTab) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                tab.pattern,
                Modifier.width(260.dp),
                textStyle = JewelTheme.editorTextStyle,
                placeholder = { Text(if (tab.plainValues) "Pattern, e.g. /services/*" else "Pattern, e.g. user:*") },
                onKeyboardAction = KeyboardActionHandler { tab.reload() },
            )
            Tooltip({ Text("Scan again") }) { IconButton(onClick = tab::reload) { Icon(AllIconsKeys.Actions.Refresh, "Scan again") } }
            if (!tab.readOnly) {
                Tooltip({ Text("New key") }) { IconButton(onClick = { creating = !creating }) { Icon(AllIconsKeys.General.Add, "New key") } }
            }
            Spacer(Modifier.weight(1f))
            val count = "${tab.keys.size} ${if (tab.keys.size == 1) "key" else "keys"}" + if (tab.cursor != null) " so far" else ""
            Text(if (tab.scanning) "Scanning…" else count, color = JewelTheme.globalColors.text.info)
        }
        tab.message?.let {
            Text(
                it.text,
                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                color = if (it.isError) JewelTheme.globalColors.text.error else JewelTheme.globalColors.text.info,
                maxLines = 3,
            )
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.weight(1f).fillMaxWidth()) {
            KeyList(tab, Modifier.width(340.dp).fillMaxHeight())
            Divider(Orientation.Vertical, Modifier.fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                when {
                    creating -> NewKeyForm(tab, onDone = { creating = false })
                    tab.value != null -> ValuePane(tab, tab.value!!)
                    tab.loadingValue -> Text("Loading…", color = JewelTheme.globalColors.text.info)
                    else -> Text("Select a key to see its value.", color = JewelTheme.globalColors.text.info)
                }
            }
        }
    }
}

@Composable
private fun KeyList(tab: KeyBrowserTab, modifier: Modifier) {
    LazyColumn(modifier) {
        items(tab.keys, key = { it.name }) { key ->
            val selected = key.name == tab.selectedKey
            Row(
                Modifier.fillMaxWidth()
                    .background(if (selected) JewelTheme.globalColors.outlines.focused.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { tab.select(key.name) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeBadge(key.type)
                Text(key.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                key.ttlMillis?.let { Text(formatTtl(it), color = JewelTheme.globalColors.text.info, fontSize = 11.sp) }
            }
        }
        if (tab.cursor != null) {
            item { Box(Modifier.fillMaxWidth().padding(8.dp)) { Link("Load more", onClick = tab::loadMore, enabled = !tab.scanning) } }
        }
    }
}

@Composable
private fun TypeBadge(type: KeyType) {
    Text(
        type.label,
        Modifier.width(46.dp).clip(RoundedCornerShape(3.dp)).background(TypeColors.getValue(type).copy(alpha = 0.22f)).padding(horizontal = 4.dp),
        color = TypeColors.getValue(type),
        fontSize = 10.sp,
        maxLines = 1,
    )
}

private val TypeColors =
    mapOf(
        KeyType.Text to Color(0xFF4C8DF6),
        KeyType.Hash to Color(0xFF9B6BDF),
        KeyType.List to Color(0xFF3FA66B),
        KeyType.Set to Color(0xFFD9822B),
        KeyType.SortedSet to Color(0xFFD5534B),
        KeyType.Stream to Color(0xFF2FA4B5),
        KeyType.Json to Color(0xFFB59A2F),
        KeyType.Other to Color(0xFF8A8A8A),
    )

/** e.g. "45s", "12m 5s", "3h 20m", "2d 4h". */
internal fun formatTtl(millis: Long): String {
    val seconds = millis / 1_000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        seconds < 86_400 -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
        else -> "${seconds / 86_400}d ${seconds % 86_400 / 3_600}h"
    }
}

@Composable
private fun ValuePane(tab: KeyBrowserTab, value: KeyValue) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyHeader(tab, value)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (value) {
                is KeyValue.Text -> TextValue(tab, value)
                is KeyValue.Hash ->
                    Elements(
                        tab,
                        headers = listOf("Field", "Value"),
                        rows = value.fields.map { listOf(it.first, it.second) },
                        total = value.total,
                        remove = { KeyEdit.DeleteField(it[0]) },
                        addLabels = listOf("field", "value"),
                        add = { KeyEdit.SetField(it[0], it[1]) },
                    )
                is KeyValue.ListValue ->
                    Elements(
                        tab,
                        headers = listOf("#", "Value"),
                        rows = value.items.mapIndexed { i, item -> listOf(i.toString(), item) },
                        total = value.total,
                        remove = { KeyEdit.RemoveItem(it[1]) },
                        addLabels = listOf("value to append"),
                        add = { KeyEdit.AppendItem(it[0]) },
                    )
                is KeyValue.SetValue ->
                    Elements(
                        tab,
                        headers = listOf("Member"),
                        rows = value.members.map { listOf(it) },
                        total = value.total,
                        remove = { KeyEdit.RemoveMember(it[0]) },
                        addLabels = listOf("member"),
                        add = { KeyEdit.AddMember(it[0]) },
                    )
                is KeyValue.SortedSet ->
                    Elements(
                        tab,
                        headers = listOf("Member", "Score"),
                        rows = value.members.map { listOf(it.first, formatScore(it.second)) },
                        total = value.total,
                        remove = { KeyEdit.RemoveMember(it[0]) },
                        addLabels = listOf("member", "score"),
                        add = { fields -> fields[1].toDoubleOrNull()?.let { KeyEdit.SetScore(fields[0], it) } },
                    )
                is KeyValue.Stream ->
                    Elements(
                        tab,
                        headers = listOf("ID", "Fields"),
                        rows = value.entries.map { entry -> listOf(entry.id, entry.fields.joinToString("  ") { "${it.first}=${it.second}" }) },
                        total = value.total,
                        remove = { KeyEdit.DeleteEntry(it[0]) },
                        addLabels = listOf("field=value, field=value"),
                        add = { fields -> parseFields(fields[0])?.let { KeyEdit.AddEntry(it) } },
                    )
                is KeyValue.Other ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("A ${value.type} value. Use the console to work with it.", color = JewelTheme.globalColors.text.info)
                        if (value.description.isNotEmpty()) Text(value.description, style = JewelTheme.editorTextStyle)
                    }
            }
        }
    }
}

@Composable
private fun KeyHeader(tab: KeyBrowserTab, value: KeyValue) {
    var renaming by remember(value.key) { mutableStateOf(false) }
    var settingTtl by remember(value.key) { mutableStateOf(false) }
    val newName = remember(value.key) { TextFieldState(value.key) }
    val ttl = remember(value.key) { TextFieldState(value.ttlMillis?.div(1_000)?.toString().orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (renaming) {
            TextField(newName, Modifier.weight(1f), onKeyboardAction = KeyboardActionHandler { tab.rename(newName.text.toString()); renaming = false })
            DefaultButton(onClick = { tab.rename(newName.text.toString()); renaming = false }) { Text("Rename") }
            OutlinedButton(onClick = { renaming = false }) { Text("Cancel") }
        } else {
            Text(value.key, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("TTL " + (value.ttlMillis?.let(::formatTtl) ?: "none"), color = JewelTheme.globalColors.text.info)
            Spacer(Modifier.weight(1f))
            Tooltip({ Text("Copy key name") }) { IconButton(onClick = { copyToClipboard(value.key) }) { Icon(AllIconsKeys.Actions.Copy, "Copy key name") } }
            Tooltip({ Text("Reload value") }) { IconButton(onClick = tab::refreshValue) { Icon(AllIconsKeys.Actions.Refresh, "Reload value") } }
            if (!tab.readOnly) {
                Link("Rename", onClick = { renaming = true })
                Link("TTL", onClick = { settingTtl = !settingTtl })
                Tooltip({ Text("Delete key") }) { IconButton(onClick = tab::delete) { Icon(AllIconsKeys.General.Delete, "Delete key") } }
            }
        }
    }
    if (settingTtl && !renaming) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Expire in")
            TextField(ttl, Modifier.width(100.dp), placeholder = { Text("seconds") })
            Text("seconds")
            DefaultButton(onClick = { ttl.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }?.let { tab.expire(it); settingTtl = false } }) { Text("Set") }
            OutlinedButton(onClick = { tab.expire(null); settingTtl = false }, enabled = value.ttlMillis != null) { Text("Never expire") }
        }
    }
}

@Composable
private fun TextValue(tab: KeyBrowserTab, value: KeyValue.Text) {
    val text = remember(value) { TextFieldState(value.value) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextArea(text, Modifier.weight(1f).fillMaxWidth(), textStyle = JewelTheme.editorTextStyle, readOnly = tab.readOnly || value.truncated)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (value.truncated) "Showing the first ${value.value.length} characters; edit it from the console." else "${value.value.length} characters",
                color = JewelTheme.globalColors.text.info,
            )
            Spacer(Modifier.weight(1f))
            if (!tab.readOnly && !value.truncated) {
                DefaultButton(onClick = { tab.edit(KeyEdit.SetText(text.text.toString())) }, enabled = text.text.toString() != value.value) { Text("Save") }
            }
        }
    }
}

/**
 * A collection's elements as rows, with a remove button each and a row of fields to add one. [remove] and [add] map a
 * row's cells (or the typed fields) to the edit; [add] returns null when the fields don't make a valid element.
 */
@Composable
private fun Elements(
    tab: KeyBrowserTab,
    headers: List<String>,
    rows: List<List<String>>,
    total: Long,
    remove: (List<String>) -> KeyEdit,
    addLabels: List<String>,
    add: (List<String>) -> KeyEdit?,
) {
    val inputs = remember(addLabels) { addLabels.map { TextFieldState() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            headers.forEachIndexed { i, header -> Text(header, Modifier.weight(if (i == 0 && headers.size > 1) 0.4f else 1f), fontWeight = FontWeight.SemiBold) }
            if (!tab.readOnly) Spacer(Modifier.width(28.dp))
        }
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(rows) { _, row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEachIndexed { i, cell ->
                        Text(cell, Modifier.weight(if (i == 0 && row.size > 1) 0.4f else 1f), style = JewelTheme.editorTextStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (!tab.readOnly) IconButton(onClick = { tab.edit(remove(row)) }, Modifier.width(28.dp)) { Icon(AllIconsKeys.General.Remove, "Remove") }
                }
            }
        }
        Text(if (rows.size.toLong() == total) "$total in all" else "Showing ${rows.size} of $total", color = JewelTheme.globalColors.text.info)
        if (!tab.readOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                fun submit() {
                    val edit = add(inputs.map { it.text.toString() }) ?: return
                    tab.edit(edit)
                    inputs.forEach { it.clearText() }
                }
                inputs.forEachIndexed { i, input ->
                    TextField(input, Modifier.weight(1f), placeholder = { Text(addLabels[i]) }, onKeyboardAction = KeyboardActionHandler { submit() })
                }
                OutlinedButton(onClick = ::submit) { Text("Add") }
            }
        }
    }
}

/** Creates a key with its first element. */
@Composable
private fun NewKeyForm(tab: KeyBrowserTab, onDone: () -> Unit) {
    val types = if (tab.plainValues) listOf(KeyType.Text) else listOf(KeyType.Text, KeyType.Hash, KeyType.List, KeyType.Set, KeyType.SortedSet, KeyType.Stream)
    var type by remember { mutableStateOf(KeyType.Text) }
    val name = remember { TextFieldState() }
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val labels =
        when (type) {
            KeyType.Hash -> "Field" to "Value"
            KeyType.SortedSet -> "Member" to "Score"
            KeyType.Stream -> "Fields (field=value, …)" to null
            KeyType.List, KeyType.Set -> "First element" to null
            else -> "Value" to null
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (tab.plainValues) "New key" else "New key in db${tab.keyspace}", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Name", Modifier.width(140.dp))
            TextField(name, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Type", Modifier.width(140.dp))
            ListComboBox(items = types.map { it.label }, selectedIndex = types.indexOf(type), onSelectedItemChange = { type = types[it] }, modifier = Modifier.width(160.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(labels.first, Modifier.width(140.dp))
            TextField(first, Modifier.weight(1f))
        }
        labels.second?.let { label ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(140.dp))
                TextField(second, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(
                onClick = {
                    val a = first.text.toString()
                    val b = second.text.toString()
                    val edit =
                        when (type) {
                            KeyType.Hash -> KeyEdit.SetField(a, b)
                            KeyType.List -> KeyEdit.AppendItem(a)
                            KeyType.Set -> KeyEdit.AddMember(a)
                            KeyType.SortedSet -> b.toDoubleOrNull()?.let { KeyEdit.SetScore(a, it) }
                            KeyType.Stream -> parseFields(a)?.let { KeyEdit.AddEntry(it) }
                            else -> KeyEdit.SetText(a)
                        }
                    if (edit != null && name.text.isNotBlank()) {
                        tab.create(name.text.toString(), edit)
                        onDone()
                    } else tab.message = KeyBrowserTab.Message("Fill in a name and a valid first element.", isError = true)
                },
            ) { Text("Create") }
            OutlinedButton(onClick = onDone) { Text("Cancel") }
        }
    }
}

/** `a=1, b=2` → [(a, 1), (b, 2)], or null if a part has no `=`. */
internal fun parseFields(text: String): List<Pair<String, String>>? {
    val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.any { '=' !in it }) return null
    return parts.map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}

private fun formatScore(score: Double): String = if (score == score.toLong().toDouble()) score.toLong().toString() else score.toString()
