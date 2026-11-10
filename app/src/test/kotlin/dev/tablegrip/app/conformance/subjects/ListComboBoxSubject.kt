package dev.tablegrip.app.conformance.subjects

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.conformance.ConformanceSubject
import dev.tablegrip.app.conformance.Probe
import dev.tablegrip.app.conformance.SubjectTag
import dev.tablegrip.app.conformance.bool
import dev.tablegrip.app.conformance.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.TrackClickBehavior
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** A non-editable ListComboBox, as the app uses it: `selectedIndex` is the app's, a change reports the new index. Reports `selected`. */
object ListComboBoxSubject : ConformanceSubject {
    override val component = "list-combo-box"

    @Composable
    override fun Content(setup: JsonObject, probe: Probe) {
        val items = setup["items"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("MySQL", "PostgreSQL", "SQLite", "Redis")
        var selected by remember { mutableIntStateOf(setup["selected"]?.jsonPrimitive?.int ?: 0) }
        var changes by remember { mutableIntStateOf(0) }
        probe.readers["selected"] = { selected.toString() }
        probe.readers["changes"] = { changes.toString() }
        ListComboBox(
            items = items,
            selectedIndex = selected,
            onSelectedItemChange = { selected = it; changes++ },
            enabled = setup.bool("enabled", true),
            modifier = Modifier.width(200.dp).testTag(SubjectTag),
        )
    }
}
