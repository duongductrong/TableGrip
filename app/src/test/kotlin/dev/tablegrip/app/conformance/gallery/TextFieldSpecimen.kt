package dev.tablegrip.app.conformance.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.tablegrip.app.connections.MaskPassword
import dev.tablegrip.app.conformance.GallerySpecimen
import dev.tablegrip.app.ui.Island
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.TextField

/**
 * Text fields at rest, with a placeholder, disabled, with the error outline, masked as a password and focused with
 * everything selected (no caret, so the render doesn't depend on the blink), plus a two-line TextArea. Each field's
 * inside, 4dp in from its edge, is a text region: text, placeholder and selection are compared by ink; fills, borders
 * and outlines pixel for pixel.
 */
@OptIn(ExperimentalTestApi::class)
object TextFieldSpecimen : GallerySpecimen {
    override val name = "text-field"
    override val size = DpSize(416.dp, 176.dp)

    @Composable
    override fun Content() {
        Island(Modifier.size(416.dp, 176.dp)) {
            Box {
                Field(16, 16, "text:field") { TextField(remember { TextFieldState("users") }, it) }
                Field(148, 16, "text:field.placeholder") {
                    TextField(remember { TextFieldState("") }, it, placeholder = { Text("Filter") })
                }
                Field(280, 16, "text:field.disabled") { TextField(remember { TextFieldState("users") }, it, enabled = false) }
                Field(16, 56, "text:field.error") { TextField(remember { TextFieldState("users") }, it, outline = Outline.Error) }
                Field(148, 56, "text:field.password") {
                    TextField(remember { TextFieldState("secret") }, it, outputTransformation = MaskPassword)
                }
                Field(280, 56, "text:field.focused") {
                    TextField(remember { TextFieldState("users", TextRange(0, 5)) }, it.testTag("field.focused"))
                }
                Box(Modifier.offset(16.dp, 96.dp)) {
                    TextArea(remember { TextFieldState("SELECT *\nFROM users") }, Modifier.size(252.dp, 64.dp))
                }
                Box(Modifier.offset(20.dp, 100.dp).size(244.dp, 56.dp).testTag("text:area"))
            }
        }
    }

    /** A 120dp field at ([x], [y]) and the text region over its inside. */
    @Composable
    private fun Field(x: Int, y: Int, region: String, field: @Composable (Modifier) -> Unit) {
        Box(Modifier.offset(x.dp, y.dp)) { field(Modifier.width(120.dp)) }
        Box(Modifier.offset((x + 4).dp, (y + 4).dp).size(112.dp, 20.dp).testTag(region))
    }

    override fun ComposeUiTest.prepare() {
        onNodeWithTag("field.focused").requestFocus()
    }
}
