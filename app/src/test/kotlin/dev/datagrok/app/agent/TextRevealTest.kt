package dev.datagrok.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextRevealTest {
    private fun TextReveal.drain(): List<String> = generateSequence { step() }.toList()

    @Test
    fun `a burst flows out over several frames and catches up`() {
        val reveal = TextReveal()
        reveal.append("x".repeat(600))
        val frames = reveal.drain()
        assertTrue(frames.size in 10..80, "took ${frames.size} frames")
        assertTrue(frames.zipWithNext().all { (a, b) -> b.length > a.length })
        assertEquals(600, frames.last().length)
        assertNull(reveal.step())
    }

    @Test
    fun `completing speeds up the rest`() {
        val streaming = TextReveal().apply { append("x".repeat(600)) }.drain().size
        val finishing = TextReveal().apply { complete("x".repeat(600)) }.drain().size
        assertTrue(finishing < streaming, "$finishing vs $streaming")
    }

    @Test
    fun `a final answer that continues the stream keeps flowing`() {
        val reveal = TextReveal()
        reveal.append("Hello wor")
        reveal.step()
        reveal.complete("Hello world, and more.")
        assertEquals("Hello world, and more.", reveal.drain().last())
    }

    @Test
    fun `a final answer that differs replaces what is shown at once`() {
        val reveal = TextReveal()
        reveal.append("partial draft")
        reveal.step()
        reveal.complete("Final.")
        assertEquals(0, reveal.pending)
        assertEquals("Final.", reveal.flush())
    }

    @Test
    fun `never splits a surrogate pair`() {
        val reveal = TextReveal()
        reveal.append("a😀".repeat(40))
        reveal.drain().forEach { assertTrue(it.isEmpty() || !it.last().isHighSurrogate(), it) }
    }
}
