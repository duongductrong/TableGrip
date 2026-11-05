package dev.tablegrip.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.connections.MaskPassword
import dev.tablegrip.core.agent.provider.ProviderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The selected protocol's API key. It goes to the system keychain, never to settings.json, and is never shown back:
 * the field only says whether one is saved. Saving checks nothing with the provider, so it costs no request.
 */
@Composable
internal fun ApiKeyPanel(app: AppState) {
    val protocol = ProviderProtocol.of(app.settings.agentProvider)
    val key = remember(protocol) { TextFieldState() }
    val scope = rememberCoroutineScope()
    var saved by remember(protocol) { mutableStateOf<Boolean?>(null) }
    var message by remember(protocol) { mutableStateOf<String?>(null) }
    LaunchedEffect(protocol) { saved = withContext(Dispatchers.IO) { runCatching { app.agent.hasSavedKey(protocol) }.getOrDefault(false) } }

    fun store(value: String) =
        scope.launch {
            runCatching { app.agent.saveKey(protocol, value) }
                .onSuccess {
                    saved = value.isNotBlank()
                    key.clearText()
                    message = if (value.isBlank()) "Key removed." else "Key saved in the system keychain."
                }
                .onFailure { message = "Couldn't reach the keychain: ${it.message ?: it}" }
        }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API key", Modifier.width(172.dp))
            TextField(
                key,
                Modifier.weight(1f),
                outputTransformation = MaskPassword,
                placeholder = { Text(if (saved == true) "Saved — type to replace" else "Not set") },
            )
            OutlinedButton(onClick = { store(key.text.toString()) }, enabled = key.text.isNotBlank()) { Text("Save") }
            if (saved == true) Link("Remove", onClick = { store("") })
        }
        Text(
            message ?: "Stored in the system keychain. Without one, ${protocol.keyVariable} is used if set; local OpenAI-compatible servers usually need none.",
            Modifier.padding(start = 180.dp),
            color = JewelTheme.globalColors.text.info,
            fontSize = 12.sp,
        )
    }
}
