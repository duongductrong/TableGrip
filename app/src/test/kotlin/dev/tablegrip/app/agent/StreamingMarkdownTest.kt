package dev.tablegrip.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingMarkdownTest {
    private val answer =
        """
        Here are the **top customers** by order count:

        ```sql run
        SELECT c.name, count(*) AS orders

        FROM customers c
        ```

        - Uses the `orders.customer_id` foreign key
        - Add a filter

        ## Notes

        Done.
        """.trimIndent()

    @Test
    fun `parsing as it streams matches parsing the whole`() {
        val markdown = StreamingMarkdown()
        for (end in 1..answer.length) {
            val source = answer.substring(0, end)
            // Closed prose renders the same either way; only the open tail may differ, so compare with it closed.
            assertEquals(parseMarkdown(source, open = true), markdown.parse(source, open = true), "at $end")
        }
        assertEquals(parseMarkdown(answer), StreamingMarkdown().parse(answer, open = false))
    }

    @Test
    fun `a replaced answer starts over`() {
        val markdown = StreamingMarkdown()
        markdown.parse("First paragraph.\n\nSecond", open = true)
        assertEquals(parseMarkdown("Other.\n\nText"), markdown.parse("Other.\n\nText", open = false))
    }

    @Test
    fun `an unclosed mark styles what follows instead of showing`() {
        val bold = (parseMarkdown("Look at **the top", open = true).single() as MarkdownBlock.Paragraph).text
        assertEquals("Look at the top", bold.text)
        assertEquals(1, bold.spanStyles.size)
        val code = (parseMarkdown("Uses `orders.cus", open = true).single() as MarkdownBlock.Paragraph).text
        assertEquals("Uses orders.cus", code.text)
        assertEquals("Half a mark", (parseMarkdown("Half a mark*", open = true).single() as MarkdownBlock.Paragraph).text.text)
        // Once the answer is complete, a stray mark is just text.
        assertEquals("Look at **the top", (parseMarkdown("Look at **the top").single() as MarkdownBlock.Paragraph).text.text)
    }
}
