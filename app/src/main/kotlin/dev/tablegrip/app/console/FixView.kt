package dev.tablegrip.app.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.AppState
import dev.tablegrip.app.ui.LocalIslands
import dev.tablegrip.app.ui.TableGripIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Under the latest failed statement in the output: a quick fix when one name is clearly misspelt, and the agent. */
@Composable
internal fun FailureActions(app: AppState, tab: ConsoleTab, failure: StatementFailure) {
    // Completion's schema may still be loading; its version changing recomputes the quick fix.
    val version = app.schemaCache(tab.config).version
    val quick = remember(failure, version) { tab.quickFix(failure) }
    Row(Modifier.padding(start = 72.dp, top = 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        quick?.let { fix ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Link("Quick fix", onClick = { tab.proposeQuickFix(failure, fix) })
                Text(fix.explanation, color = JewelTheme.globalColors.text.info)
            }
        }
        if (tab.canAskAgent) {
            Link("Fix with AI", onClick = { tab.askAgentToFix(failure) }, enabled = tab.fix !is FixState.Asking)
            Link("Ask in chat", onClick = { tab.discussInChat(failure) })
        }
    }
}

/** The fix being asked for or reviewed, above the output. Nothing changes in the editor until it is applied. */
@Composable
internal fun FixCard(tab: ConsoleTab, fix: FixState) {
    val islands = LocalIslands.current
    val info = JewelTheme.globalColors.text.info
    val shape = RoundedCornerShape(8.dp)
    val warnings = (fix as? FixState.Proposed)?.warnings.orEmpty()
    val border = if (warnings.isNotEmpty()) WarningColor else islands.accent
    Column(
        Modifier.padding(8.dp).fillMaxWidth().clip(shape).background(islands.frame).border(1.dp, border, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (fix) {
            is FixState.Asking ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Asking ${fix.agent ?: "the agent"} for a fix…", Modifier.weight(1f))
                    OutlinedButton(onClick = tab::cancelFix) { Text("Cancel") }
                }
            is FixState.NoFix -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AllIconsKeys.General.Information, null, Modifier.size(14.dp))
                    Text("No fix", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = tab::cancelFix) { Text("Dismiss") }
                }
                Text(fix.message)
            }
            is FixState.Proposed -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(TableGripIcons.Agent, null, Modifier.size(14.dp))
                    Text("Proposed fix", fontWeight = FontWeight.SemiBold)
                    Text("from ${fix.source}", color = info)
                }
                Text(fix.explanation)
                warnings.forEach { warning ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AllIconsKeys.General.Warning, null, Modifier.size(14.dp))
                        Text(warning, color = WarningColor)
                    }
                }
                FixDiff(fix.failure.sql, fix.sql)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replaces the failed statement in the editor; ⌘Z undoes it.", Modifier.weight(1f), color = info)
                    OutlinedButton(onClick = tab::cancelFix) { Text("Discard") }
                    if (warnings.isEmpty()) OutlinedButton(onClick = { tab.applyFix(andRun = true) }, enabled = !tab.running) { Text("Apply & Run") }
                    DefaultButton(onClick = { tab.applyFix() }) { Text("Apply") }
                }
            }
        }
    }
}

/** The fixed statement, its changed lines marked against the one that failed. */
@Composable
private fun FixDiff(original: String, fixed: String) {
    val lines = remember(original, fixed) { diffLines(original, fixed) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { (kind, text) ->
            val (prefix, background) =
                when (kind) {
                    DiffKind.Removed -> "- " to RemovedColor
                    DiffKind.Added -> "+ " to AddedColor
                    DiffKind.Same -> "  " to Color.Transparent
                }
            Text(prefix + text, Modifier.background(background).padding(horizontal = 4.dp), style = JewelTheme.editorTextStyle, softWrap = false)
        }
        Spacer(Modifier.size(2.dp))
    }
}

internal enum class DiffKind { Same, Removed, Added }

/** A line diff by longest common subsequence: statements are short, so the quadratic table is fine. */
internal fun diffLines(original: String, fixed: String): List<Pair<DiffKind, String>> {
    val a = original.trim().lines()
    val b = fixed.trim().lines()
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val result = mutableListOf<Pair<DiffKind, String>>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> result += DiffKind.Same to a[i++].also { j++ }
            i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> result += DiffKind.Removed to a[i++]
            else -> result += DiffKind.Added to b[j++]
        }
    }
    return result
}

private val WarningColor = Color(0xFFD9822B)
private val AddedColor = Color(0x3349A85A)
private val RemovedColor = Color(0x33DB4B4B)
