package dev.datagrok.app.settings

import androidx.compose.ui.graphics.Color
import dev.datagrok.app.console.EditorActions
import dev.datagrok.app.console.EditorText
import dev.datagrok.app.console.SyntaxScheme
import dev.datagrok.app.console.SyntaxToken
import dev.datagrok.app.console.syntaxColor
import dev.datagrok.app.grid.CellFormat
import dev.datagrok.app.grid.groupDigits
import dev.datagrok.app.ui.StripeGroup
import dev.datagrok.app.ui.StripeItem
import dev.datagrok.app.ui.parseHexColor
import dev.datagrok.app.ui.toHex
import dev.datagrok.core.settings.Settings
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomizationTest {
    @Test
    fun `default cell format shows values as the database returns them`() {
        val format = Settings().let { CellFormat(nullText = it.nullText) }
        assertEquals("<null>", format.nullText)
        assertEquals("1234567.5", format.display("1234567.5", numeric = true))
        assertEquals("2026-10-28 13:45:00.123+07", format.display("2026-10-28 13:45:00.123+07", numeric = false))
    }

    @Test
    fun `cells can group digits and reformat date-times`() {
        assertEquals("-1,234,567.25", groupDigits("-1234567.25"))
        assertEquals("999", groupDigits("999"))
        assertEquals("1e10", groupDigits("1e10"))
        val format = CellFormat(dateTimePattern = "dd/MM/yyyy HH:mm", zone = ZoneOffset.UTC, numberGrouping = true)
        assertEquals("28/10/2026 06:45", format.display("2026-10-28 13:45:00+07", numeric = false))
        // No offset: the stored wall-clock time.
        assertEquals("28/10/2026 13:45", format.display("2026-10-28 13:45:00", numeric = false))
        assertEquals("not a date", format.display("not a date", numeric = false))
        assertFalse(CellFormat.isValidPattern("yyyy-MM-dd {"))
        assertTrue(CellFormat.isValidPattern(""))
    }

    @Test
    fun `indent uses the configured unit`() {
        val tabbed = EditorActions.indent(EditorText("a\nb", androidx.compose.ui.text.TextRange(0, 3)), outdent = false, unit = "\t")
        assertEquals("\ta\n\tb", tabbed.text)
        assertEquals("a\nb", EditorActions.indent(tabbed, outdent = true, unit = "\t").text)
        assertEquals("  a", EditorActions.indent(EditorText("a", androidx.compose.ui.text.TextRange(0)), outdent = false, unit = "  ").text)
    }

    @Test
    fun `the default scheme is the editor's original palette and custom colours win`() {
        assertEquals(Color(0xFFCF8E6D), Settings().syntaxColor(SyntaxToken.Keyword, dark = true))
        assertEquals(Color(0xFF0033B3), Settings().syntaxColor(SyntaxToken.Keyword, dark = false))
        val custom = Settings(syntaxScheme = SyntaxScheme.Monokai.name, syntaxColors = mapOf("dark.string" to "#112233"))
        assertEquals(Color(0xFF112233), custom.syntaxColor(SyntaxToken.String, dark = true))
        assertEquals(SyntaxScheme.Monokai.color(SyntaxToken.Keyword, dark = true), custom.syntaxColor(SyntaxToken.Keyword, dark = true))
    }

    @Test
    fun `hex colours parse and print`() {
        assertEquals("#3574F0", parseHexColor("3574f0")!!.toHex())
        assertNull(parseHexColor("#12"))
        assertNull(parseHexColor("zzzzzz"))
    }

    @Test
    fun `stripe buttons follow the user's order and hide`() {
        assertEquals(listOf(StripeItem.Explorer, StripeItem.History, StripeItem.Queries), StripeItem.visible(StripeGroup.Tools, Settings()))
        val settings = Settings(stripeOrder = listOf("Queries", "Explorer"), stripeHidden = listOf("History"))
        assertEquals(listOf(StripeItem.Queries, StripeItem.Explorer), StripeItem.visible(StripeGroup.Tools, settings))
    }

    @Test
    fun `every settings page field resets to the defaults`() {
        // Each field reads back what it was set to, so Reset to defaults puts every page back.
        SettingsPages.all.flatMap { it.fields }.forEach { field ->
            when (field) {
                is BooleanField -> assertTrue(field.get(field.set(Settings(), !field.get(Settings()))) != field.get(Settings()), field.label)
                is ColorField -> assertEquals("#ABCDEF", field.get(field.set(Settings(), "#ABCDEF")), field.label)
                else -> Unit
            }
        }
    }
}
