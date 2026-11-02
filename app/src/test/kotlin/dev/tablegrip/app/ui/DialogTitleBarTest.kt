package dev.tablegrip.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DialogTitleBarTest {
    @Test
    fun `only interactive elements keep clicks, the rest of the bar drags`() = runComposeUiTest {
        val regions = TitleBarRegions()
        var shown by mutableStateOf(true)
        setContent {
            Box(Modifier.size(400.dp, 32.dp)) {
                if (shown) Box(Modifier.offset(300.dp, 4.dp).size(24.dp).interactiveRegion(regions))
            }
        }
        waitForIdle()
        val px = Density(density.density)
        with(px) {
            assertTrue(regions.isInteractive(Offset(310.dp.toPx(), 10.dp.toPx())), "button keeps the click")
            assertFalse(regions.isInteractive(Offset(100.dp.toPx(), 10.dp.toPx())), "empty bar drags")
        }

        shown = false
        waitForIdle()
        with(px) { assertFalse(regions.isInteractive(Offset(310.dp.toPx(), 10.dp.toPx())), "removed button no longer blocks dragging") }
    }
}
