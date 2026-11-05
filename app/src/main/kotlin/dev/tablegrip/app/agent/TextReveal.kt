package dev.tablegrip.app.agent

/**
 * Paces a streaming answer onto the screen. Agents deliver text in bursts (a pipe read, a whole message from Codex), and
 * shown as it arrives the answer jumps in blocks. Revealed a little each frame instead, with the step growing with what
 * is waiting, it flows evenly and never falls more than a fraction of a second behind.
 *
 * Owned by one turn and driven from one thread, so nothing here needs locking.
 */
internal class TextReveal {
    private val target = StringBuilder()
    private var shown = 0
    private var catchUp = Streaming

    /** Characters received but not yet shown. */
    val pending: Int
        get() = target.length - shown

    fun append(text: String) {
        target.append(text)
    }

    /**
     * The agent's final text. When it carries on from what is shown, the rest keeps flowing, faster now that nothing more
     * is coming; when it doesn't (a CLI that reports a different final answer), it replaces what is shown at once.
     */
    fun complete(text: String) {
        val visible = target.substring(0, shown)
        target.setLength(0)
        target.append(text)
        if (!text.startsWith(visible)) shown = text.length
        catchUp = Finishing
    }

    /** Advances one frame: the text to show now, or null when nothing changed. */
    fun step(): String? {
        if (pending == 0) return null
        var next = minOf(target.length, shown + maxOf(MinStep, (pending + catchUp - 1) / catchUp))
        // Never split a surrogate pair: half an emoji renders as a box.
        if (next < target.length && target[next - 1].isHighSurrogate()) next++
        shown = next
        return target.substring(0, shown)
    }

    /** Everything received, shown at once: the turn ended or was stopped. */
    fun flush(): String {
        shown = target.length
        return target.toString()
    }

    companion object {
        /** How often a step is taken. */
        const val FrameMillis = 16L
        private const val MinStep = 2
        /** Each step shows 1/n of what is waiting: about a fifth of a second behind while streaming. */
        private const val Streaming = 12
        private const val Finishing = 4
    }
}
