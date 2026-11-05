package dev.tablegrip.app.agent

import dev.tablegrip.core.agent.AgentEvent
import dev.tablegrip.core.agent.AgentMessage
import dev.tablegrip.core.agent.Mention
import dev.tablegrip.core.agent.SavedActivity
import dev.tablegrip.core.agent.SavedChatMessage
import dev.tablegrip.core.agent.SavedMention
import dev.tablegrip.core.agent.SavedRun
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What a chat is called until the user renames it: its first message, on one line and cut short. */
internal fun chatTitle(messages: List<ChatMessage>): String? {
    val first = messages.firstOrNull { it.role == AgentMessage.Role.User && it.kind == ChatMessage.Kind.Chat } ?: return null
    val line = first.mentionText.plain.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    return if (line.length <= TitleLength) line else line.take(TitleLength).trimEnd() + "…"
}

private const val TitleLength = 60

/**
 * The conversation as it is kept. A reply still being written keeps what has arrived; one with nothing yet is left out,
 * so reopening never shows an answer that will not come.
 */
internal fun List<ChatMessage>.toSaved(): List<SavedChatMessage> {
    val kept = filter { !(it.streaming && it.text.isBlank() && it.reasoning.isBlank()) }
    return kept.map { message ->
        val origin = message.origin?.let { (request, block) -> kept.indexOf(request).takeIf { it >= 0 }?.let { it to block } }
        SavedChatMessage(
            role = message.role,
            text = message.text,
            note = message.kind == ChatMessage.Kind.Note,
            label = message.label,
            reasoning = message.reasoning,
            thoughtSeconds = message.thoughtSeconds,
            activities = message.activities.map {
                // A step still running when the chat was saved never reports back once reopened.
                SavedActivity(it.id, it.label, if (it.state == AgentEvent.Activity.State.Running) AgentEvent.Activity.State.Failed else it.state)
            },
            error = message.error,
            mentions = message.mentionText.used.associate { (char, mention) -> char.toString() to SavedMention(mention.label, mention.context) },
            runs = message.runs.mapNotNull { (block, state) -> state.toSaved()?.let { (saved, summary) -> SavedRun(block, saved, summary) } }.sortedBy { it.block },
            origin = origin?.first,
            originBlock = origin?.second,
        )
    }
}

/** A run still going is left unanswered: reopened, its request asks again. */
private fun RunRequestState.toSaved(): Pair<SavedRun.State, String?>? =
    when (this) {
        RunRequestState.Running -> null
        is RunRequestState.Done -> SavedRun.State.Done to summary
        RunRequestState.Declined -> SavedRun.State.Declined to null
        RunRequestState.Refused -> SavedRun.State.Refused to null
        RunRequestState.NotShared -> SavedRun.State.NotShared to null
    }

internal fun List<SavedChatMessage>.toChat(): List<ChatMessage> {
    val messages = map { saved ->
        ChatMessage(
            saved.role,
            saved.text,
            if (saved.note) ChatMessage.Kind.Note else ChatMessage.Kind.Chat,
            saved.mentions.mapNotNull { (char, mention) -> char.singleOrNull()?.let { it to Mention.Saved(mention.label, mention.context) } }.toMap(),
        ).apply {
            label = saved.label
            reasoning = saved.reasoning
            thoughtSeconds = saved.thoughtSeconds
            activities += saved.activities.map { AgentEvent.Activity(it.id, it.label, it.state) }
            error = saved.error
            saved.runs.forEach { run ->
                runs[run.block] =
                    when (run.state) {
                        SavedRun.State.Done -> RunRequestState.Done(run.summary.orEmpty())
                        SavedRun.State.Declined -> RunRequestState.Declined
                        SavedRun.State.Refused -> RunRequestState.Refused
                        SavedRun.State.NotShared -> RunRequestState.NotShared
                    }
            }
        }
    }
    forEachIndexed { index, saved ->
        val request = saved.origin?.let(messages::getOrNull) ?: return@forEachIndexed
        messages[index].origin = request to (saved.originBlock ?: return@forEachIndexed)
    }
    return messages
}

/** The headings the chat list groups by, newest first, as most chat apps do. */
internal enum class ChatAge(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    Week("Previous 7 days"),
    Month("Previous 30 days"),
    Older("Older");

    companion object {
        fun of(updatedAt: Long, today: LocalDate, zone: ZoneId): ChatAge {
            val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate(), today)
            return when {
                days <= 0 -> Today
                days == 1L -> Yesterday
                days < 7 -> Week
                days < 30 -> Month
                else -> Older
            }
        }
    }
}

/** When a chat was last touched, short: `14:05` today, `Mon` this week, `Sep 12` this year, else `Sep 12, 2025`. */
internal fun chatTime(updatedAt: Long, today: LocalDate, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val days = ChronoUnit.DAYS.between(time.toLocalDate(), today)
    val pattern =
        when {
            days <= 0 -> "HH:mm"
            days < 7 -> "EEE"
            time.year == today.year -> "MMM d"
            else -> "MMM d, yyyy"
        }
    return time.format(DateTimeFormatter.ofPattern(pattern))
}
