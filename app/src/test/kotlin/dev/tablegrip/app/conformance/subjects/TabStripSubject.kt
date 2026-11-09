package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.SimpleTabContent
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Jewel's TabStrip as the dialogs and the console's result tabs use it: tabs "Tab 0"…, `tabs` of them (default 3) in a
 * strip `width` dp wide (default 400), `closable` or not, the `selected` one first (default 0). A click selects; the
 * close button removes the tab and the selection follows the app's rule (stay on the same tab, or the one before when
 * it was the selected one or earlier). Jewel merges a tab's semantics and drops tags inside it, so markers laid over
 * the tabs carry the tags `tab:<i>` and `close:<i>`; they take no pointer input. Reports `selected` (its title),
 * `tabs` (titles, comma separated) and `selectedVisible` (whether the selected tab lies inside the strip).
 */
object TabStripSubject : ConformanceSubject {
    override val component = "tab-strip"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val count = (setup["tabs"] as? JsonPrimitive)?.content?.toInt() ?: 3
        val width = (setup["width"] as? JsonPrimitive)?.content?.toInt() ?: 400
        val closable = setup.bool("closable")
        var titles by remember { mutableStateOf((0 until count).map { "Tab $it" }) }
        var selected by remember { mutableStateOf((setup["selected"] as? JsonPrimitive)?.content?.toInt() ?: 0) }
        val bounds = remember { mutableStateMapOf<String, Rect>() }
        var strip by remember { mutableStateOf(Rect.Zero) }
        probe.readers["selected"] = { titles.getOrNull(selected) ?: "" }
        probe.readers["tabs"] = { titles.joinToString(",") }
        probe.readers["selectedVisible"] = {
            bounds[titles.getOrNull(selected)]?.let { b -> b.width > 0 && b.left >= strip.left && b.right <= strip.right } == true
        }.let { read -> { read().toString() } }
        val density = LocalDensity.current
        Box(Modifier.width(width.dp).onGloballyPositioned { strip = it.boundsInRoot() }.testTag(SubjectTag)) {
            TabStrip(
                tabs = titles.mapIndexed { index, title ->
                    TabData.Default(
                        selected = index == selected,
                        content = { state -> SimpleTabContent(title, state, modifier = Modifier.onGloballyPositioned { bounds[title] = it.boundsInRoot() }) },
                        closable = closable,
                        onClose = {
                            titles = titles.filterIndexed { i, _ -> i != index }
                            if (selected >= index && selected > 0) selected--
                        },
                        onClick = { selected = index },
                    )
                },
                style = JewelTheme.defaultTabStyle,
            )
            // Markers over each tab (content plus its 8dp padding, and the close button's room) and its close button.
            titles.forEachIndexed { index, title ->
                val b = bounds[title]?.takeIf { it.width > 0 } ?: return@forEachIndexed
                val pad = with(density) { 8.dp.toPx() }
                val right = with(density) { (if (closable) 32.dp else 16.dp).toPx() }
                Box(
                    Modifier.offset { IntOffset((b.left - strip.left - pad).toInt(), 0) }
                        .size(with(density) { (b.width + pad + right).toDp() }, with(density) { strip.height.toDp() })
                        .testTag("tab:$index"),
                )
                if (closable) {
                    Box(
                        Modifier.offset { IntOffset((b.right - strip.left + pad).toInt(), (b.top - strip.top).toInt()) }
                            .size(16.dp)
                            .testTag("close:$index"),
                    )
                }
            }
        }
    }
}
