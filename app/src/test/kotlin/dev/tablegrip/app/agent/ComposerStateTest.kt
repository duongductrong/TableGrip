package dev.tablegrip.app.agent

import androidx.compose.ui.text.TextRange
import dev.tablegrip.app.ui.TextClipboard
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.core.agent.MentionText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerStateTest {
    private class FakeClipboard(var text: String? = null) : TextClipboard {
        override fun get() = text

        override fun set(text: String) {
            this.text = text
        }
    }

    private val orders = Mention.Sql("Console", 3..5, "SQLite", "SELECT * FROM orders")
    private val clipboard = FakeClipboard()
    private val composer = ComposerState(clipboard)

    private val text: String
        get() = composer.text.text.toString()

    private fun select(start: Int, end: Int = start) = composer.text.edit { selection = TextRange(start, end) }

    @Test
    fun `copying pills puts their labels on the clipboard`() {
        composer.insert(orders)
        composer.text.edit { append("is slow") }
        select(0, text.length)
        assertTrue(composer.copy())
        assertEquals("@Console:3-5 is slow", clipboard.text)
        assertEquals("${MentionText.First} is slow", text)
    }

    @Test
    fun `copying plain text is left to the field`() {
        composer.text.edit { append("plain words") }
        select(0, 5)
        assertFalse(composer.copy())
        assertNull(clipboard.text)
        select(3)
        assertFalse(composer.copy())
    }

    @Test
    fun `cut and paste moves pills, even after the message was sent`() {
        composer.insert(orders)
        composer.text.edit { append("why") }
        select(0, 2)
        assertTrue(composer.copy(cut = true))
        assertEquals("why", text)
        assertEquals(TextRange(0), composer.text.selection)

        select(3)
        assertTrue(composer.paste())
        assertEquals("why${MentionText.First} ", text)
        assertEquals(TextRange(5), composer.text.selection)
        assertEquals(orders, composer.mention(MentionText.First))

        // Sending forgets the pill's character; pasting brings the mention back under a fresh one.
        composer.take()
        assertTrue(composer.paste())
        val pill = text.single(MentionText::isMention)
        assertEquals(orders, composer.mention(pill))
        assertEquals("@Console:3-5 [1]", MentionText(text, mapOf(pill to orders)).prompt.substringBefore('\n').trim())
    }

    @Test
    fun `paste is left to the field once the clipboard holds something else`() {
        composer.insert(orders)
        select(0, 1)
        composer.copy()
        clipboard.text = "copied elsewhere"
        assertFalse(composer.paste())
    }

    @Test
    fun `removing a pill takes it out of the text and closes its details`() {
        composer.insert(orders)
        composer.text.edit { append("and again ") }
        composer.insert(Mention.Sql("Console", 9..9, "SQLite", "SELECT 1"))
        val first = MentionText.First
        composer.preview = first
        composer.remove(first)
        assertNull(composer.preview)
        assertEquals(" and again ${first + 1} ", text)
    }

    @Test
    fun `typing after @ is a mention query until Esc or a space`() {
        composer.text.edit { append("join @ord") }
        assertEquals(5 to "ord", composer.typing)
        composer.text.edit { append(".to") }
        assertEquals(5 to "ord.to", composer.typing)
        composer.dismissed = 5
        assertNull(composer.typing)
        composer.dismissed = null
        composer.text.edit { append(" ") }
        assertNull(composer.typing)
    }

    @Test
    fun `an @ inside a word is not a mention`() {
        composer.text.edit { append("mail me@exa") }
        assertNull(composer.typing)
        composer.text.edit { replace(0, length, "@") }
        assertEquals(0 to "", composer.typing)
    }

    @Test
    fun `picking replaces the query with a pill`() {
        composer.text.edit { append("why @ord slow") }
        select(8)
        composer.accept(orders)
        assertEquals("why ${MentionText.First}  slow", text)
        assertEquals(TextRange(6), composer.text.selection)
        assertNull(composer.typing)
    }
}
