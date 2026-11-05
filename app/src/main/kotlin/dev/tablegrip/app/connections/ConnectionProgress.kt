package dev.tablegrip.app.connections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tablegrip.core.connection.ConnectionConfig
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.CircularProgressIndicatorBig
import org.jetbrains.jewel.ui.component.Text

/** Seconds after which a wait is shown with its elapsed time. */
private const val ShowElapsedAfter = 2L

/** Seconds after which a wait is called out as slow, with a hint at why. */
private const val SlowAfter = 8L

/** What a connection attempt is doing right now, in words, e.g. "Connecting to db.example.com:5432…". */
fun connectingText(config: ConnectionConfig, status: ConnectionStatus.Connecting): String =
    when {
        status.step == ConnectionStatus.Connecting.Step.Driver -> "Preparing the ${config.type.displayName} driver…"
        config.type.isFile -> "Opening ${config.database.substringAfterLast('/').ifEmpty { config.name }}…"
        config.ssh != null -> "Connecting through ${config.ssh!!.host}…"
        config.url != null -> "Connecting to ${config.name}…"
        else -> "Connecting to ${config.host}${(config.port ?: config.type.defaultPort)?.let { ":$it" }.orEmpty()}…"
    }

/** Whole seconds since [since] (epoch millis), ticking once a second; null while there is nothing to time. */
@Composable
fun rememberElapsedSeconds(since: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return if (since == null) 0 else ((now - since) / 1_000).coerceAtLeast(0)
}

/** A one-line spinner and status for narrow places such as a tree row; the elapsed time shows once a wait drags on. */
@Composable
fun ProgressLine(text: String, modifier: Modifier = Modifier, since: Long? = null) {
    val elapsed = rememberElapsedSeconds(since)
    val info = JewelTheme.globalColors.text.info
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text(text, Modifier.weight(1f, fill = false), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (since != null && elapsed >= ShowElapsedAfter) Text("${elapsed}s", color = info, maxLines = 1, softWrap = false)
        }
        AnimatedVisibility(since != null && elapsed >= SlowAfter, enter = fadeIn(), exit = fadeOut()) {
            Text("Taking longer than usual…", color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A centred spinner and status filling an empty view while it waits for its connection or first data. */
@Composable
fun LoadingPlaceholder(text: String, modifier: Modifier = Modifier, since: Long? = null) {
    val elapsed = rememberElapsedSeconds(since)
    val info = JewelTheme.globalColors.text.info
    Column(modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicatorBig()
        Text(text, textAlign = TextAlign.Center)
        val slow = since != null && elapsed >= SlowAfter
        when {
            slow -> Text("Still trying after ${elapsed}s. The server may be busy, or a VPN or firewall may be in the way.", color = info, textAlign = TextAlign.Center)
            since != null && elapsed >= ShowElapsedAfter -> Text("${elapsed}s", color = info)
        }
    }
}

/** [LoadingPlaceholder] for a view of [config]: the connection's progress while it opens, else [text]. */
@Composable
fun ConnectionLoadingPlaceholder(config: ConnectionConfig, status: ConnectionStatus, text: String, modifier: Modifier = Modifier) {
    val startedAt = remember { System.currentTimeMillis() }
    if (status is ConnectionStatus.Connecting) LoadingPlaceholder(connectingText(config, status), modifier, since = status.since)
    else LoadingPlaceholder(text, modifier, since = startedAt)
}

/** A connection error put plainly: what went wrong, what to check, and the driver's own words for the details. */
data class FriendlyError(val headline: String, val hint: String?, val detail: String)

/** Recognises the usual ways a connection fails (unreachable, unknown host, rejected sign-in…) across drivers. */
fun friendlyError(message: String): FriendlyError {
    val text = message.lowercase()
    fun has(vararg needles: String) = needles.any { it in text }
    val (headline, hint) =
        when {
            has("ssh") ->"Couldn't open the SSH tunnel" to "Check the SSH host, user and key or password."
            has("password authentication failed", "access denied", "authentication failed", "login failed", "invalid password", "wrongpass", "noauth", "not authorized", "invalid username") ->
                "Sign-in was rejected" to "Check the user name and password."
            has("unknownhost", "unknown host", "nodename nor servname", "name or service not known", "no such host") ->
                "Unknown host" to "Check the host name, or your network and VPN."
            has("connection refused", "connect refused") -> "The server refused the connection" to "Make sure it's running and the host and port are right."
            has("timed out", "timeout", "time out") -> "The server didn't answer in time" to "It may be offline, or a VPN or firewall may be blocking it."
            has("does not exist", "unknown database", "cannot open database") && has("database") -> "Database not found" to "Check the database name."
            has("ssl", "tls", "certificate", "handshake") -> "Secure connection failed" to "Check the SSL settings in the connection's properties."
            else -> message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { "Couldn't connect" } to null
        }
    return FriendlyError(headline, hint, message.trim())
}
