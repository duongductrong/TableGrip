package dev.datagrok.app.agent

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import dev.datagrok.app.ui.TextClipboard
import dev.datagrok.core.agent.Mention
import dev.datagrok.core.agent.MentionText

/**
 * What is being typed to the agent, mentions included. Kept by the chat rather than the composer, so a mention made
 * elsewhere (⌘L in a console) lands in it even while the panel is closed.
 *
 * Each mention is one stand-in character in [text] (see [MentionText]); the field shows it as a pill. Mentions stay known
 * until the message is sent, so undoing the deletion of a pill brings it back.
 *
 * Copying text with pills puts `@label` on the clipboard, so it reads right anywhere else; pasting that same text back here
 * brings the pills back.
 */
class ComposerState(private val clipboard: TextClipboard = TextClipboard.System) {
    val text = TextFieldState()
    private val mentions = mutableStateMapOf<Char, Mention>()
    private var next = MentionText.First

    /** Bumped when something outside the field wants it focused, e.g. after a mention. */
    var focusRequests by mutableStateOf(0)
        private set

    fun mention(char: Char): Mention? = mentions[char]

    /** The pill whose details are showing, if any. */
    var preview by mutableStateOf<Char?>(null)

    /** The last copy that had pills in it: what went on the clipboard, and the text and mentions it stands for. */
    private var copied: Pair<String, MentionText>? = null

    /** Puts [mention] at the caret, or focuses the pill already there for the same thing. */
    fun insert(mention: Mention) {
        focusRequests++
        val existing = mentions.entries.firstOrNull { it.value == mention && it.key in text.text }?.key
        if (existing != null) return
        val char = allocate()
        mentions[char] = mention
        text.edit {
            val start = selection.min
            val end = selection.max
            val before = if (start > 0 && !this.charAt(start - 1).isWhitespace()) " " else ""
            replace(start, end, "$before$char ")
            selection = TextRange(start + before.length + 2)
        }
    }

    /** Takes every copy of the pill [char] out of the text. */
    fun remove(char: Char) {
        if (preview == char) preview = null
        text.edit {
            val current = asCharSequence()
            for (index in current.indices.reversed()) if (current[index] == char) replace(index, index + 1, "")
        }
        focusRequests++
    }

    /**
     * Copies (or with [cut], cuts) a selection that has pills in it as `@label` text. False leaves the key to the field:
     * nothing is selected, or there are no pills in it.
     */
    fun copy(cut: Boolean = false): Boolean {
        val selection = text.selection
        if (selection.collapsed) return false
        val raw = text.text.substring(selection.min, selection.max)
        if (raw.none { it in mentions }) return false
        val selected = MentionText(raw, raw.filter { it in mentions }.associateWith { mentions.getValue(it) })
        clipboard.set(selected.plain)
        copied = selected.plain to selected
        if (cut) {
            text.edit {
                replace(selection.min, selection.max, "")
                this.selection = TextRange(selection.min)
            }
        }
        return true
    }

    /** Pastes the pills of the last [copy] when the clipboard still holds what it put there; false leaves it to the field. */
    fun paste(): Boolean {
        val (plain, source) = copied ?: return false
        if (clipboard.get() != plain) return false
        // A pill sent since has lost its character, which may now stand for another mention: give it a fresh one.
        val renamed = mutableMapOf<Char, Char>()
        for ((char, mention) in source.mentions) {
            renamed[char] = if (mentions[char] == mention) char else allocate().also { mentions[it] = mention }
        }
        val pasted = source.text.map { renamed[it] ?: it }.joinToString("")
        text.edit {
            val start = selection.min
            replace(start, selection.max, pasted)
            selection = TextRange(start + pasted.length)
        }
        return true
    }

    /** The message as typed; the composer is left empty. */
    fun take(): MentionText {
        val message = MentionText(text.text.toString(), mentions.toMap())
        text.clearText()
        mentions.clear()
        preview = null
        return message
    }

    /** Hands a sent message back to edit, pills included; replaces whatever was being typed. */
    fun restore(message: MentionText) {
        preview = null
        mentions.clear()
        mentions.putAll(message.mentions)
        text.setTextAndPlaceCursorAtEnd(message.text)
        focusRequests++
    }

    val isBlank: Boolean
        get() = text.text.none { !it.isWhitespace() && (!MentionText.isMention(it) || it in mentions) }

    private fun allocate(): Char {
        while (next in mentions) next = if (next == MentionText.Last) MentionText.First else next + 1
        return next.also { next = if (it == MentionText.Last) MentionText.First else it + 1 }
    }

    /** Drops stand-ins the composer doesn't know, e.g. pasted from elsewhere. */
    val filter = InputTransformation {
        val text = asCharSequence()
        for (index in text.indices.reversed()) {
            if (MentionText.isMention(text[index]) && text[index] !in mentions) replace(index, index + 1, "")
        }
    }

    /** Shows each stand-in as its label; [pills] says where they ended up, for drawing behind them. */
    fun display(style: SpanStyle) = OutputTransformation {
        val source = asCharSequence().toString()
        var shift = 0
        for ((index, char) in source.withIndex()) {
            val mention = mentions[char] ?: continue
            val label = pillText(mention)
            val at = index + shift
            replace(at, at + 1, label)
            addStyle(style, at, at + label.length)
            shift += label.length - 1
        }
    }

    /** The text as displayed, and where each pill is in it. */
    internal fun layout(): PillLayout = PillLayout.of(text.text, mentions)

    /** Where each pill is in the displayed text: ranges of the field's layout, in order. */
    fun pills(): List<IntRange> = layout().pills

    companion object {
        /** Thin spaces pad the label inside its pill. */
        fun pillText(mention: Mention): String = " @${mention.label} "
    }
}
