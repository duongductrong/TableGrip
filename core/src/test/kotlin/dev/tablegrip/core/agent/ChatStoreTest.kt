package dev.tablegrip.core.agent

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ChatStoreTest {
    private val directory = Files.createTempDirectory("chats")
    private val store = ChatStore(directory)

    private fun chat(id: String, updatedAt: Long, title: String = id) =
        SavedChat(
            id,
            title,
            createdAt = 1,
            updatedAt = updatedAt,
            agent = "cli:claude",
            messages =
                listOf(
                    SavedChatMessage(AgentMessage.Role.User, "rows in ?", mentions = mapOf("" to SavedMention("orders", "table orders: id int"))),
                    SavedChatMessage(
                        AgentMessage.Role.Assistant,
                        "```sql\nSELECT 1\n```",
                        reasoning = "count",
                        thoughtSeconds = 2,
                        activities = listOf(SavedActivity("a", "Read schema", AgentEvent.Activity.State.Done)),
                        runs = listOf(SavedRun(0, SavedRun.State.Done, "1 row(s) sent to the agent")),
                    ),
                    SavedChatMessage(AgentMessage.Role.User, "result", note = true, label = "Result shared", origin = 1, originBlock = 0),
                ),
        )

    @Test
    fun `round-trips a chat`() {
        val chat = chat("a", 5)
        store.save(chat)
        assertEquals(chat, store.load("a"))
    }

    @Test
    fun `lists newest first and skips broken files`() {
        store.save(chat("old", 1))
        store.save(chat("new", 9))
        directory.resolve("broken.json").writeText("{ not json")
        assertEquals(listOf("new", "old"), store.list().map { it.id })
    }

    @Test
    fun `renames and deletes`() {
        store.save(chat("a", 5))
        store.rename("a", "Monthly revenue")
        assertEquals("Monthly revenue", store.load("a")?.title)
        store.delete("a")
        assertNull(store.load("a"))
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `never reads or writes outside its directory`() {
        assertFailsWith<IllegalArgumentException> { store.save(chat("../escape", 1)) }
        assertNull(store.load("../settings"))
    }
}
