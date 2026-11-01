package dev.datagrok.core

import dev.datagrok.core.history.HistoryEntry
import dev.datagrok.core.history.QueryHistory
import dev.datagrok.core.settings.Settings
import dev.datagrok.core.settings.SettingsStore
import dev.datagrok.core.settings.ThemeSelection
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class StorageTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `settings round-trip and fall back to defaults on bad files`() {
        val file = dir.resolve("settings.json")
        val store = SettingsStore(file)
        assertEquals(Settings(), store.load())

        store.save(Settings(theme = ThemeSelection(mode = "Dark"), pageSize = 50))
        assertEquals(Settings(theme = ThemeSelection(mode = "Dark"), pageSize = 50), store.load())

        file.writeText("{ not json")
        assertEquals(Settings(), store.load())
    }

    @Test
    fun `history returns most recent entries first, per connection, skipping corrupt lines`() {
        val file = dir.resolve("history.jsonl")
        val history = QueryHistory(file)
        history.append(HistoryEntry("a", "SELECT 1", executedAt = 1, elapsedMillis = 1))
        file.appendText("{ broken\n")
        history.append(HistoryEntry("b", "SELECT 2", executedAt = 2, elapsedMillis = 1))
        history.append(HistoryEntry("a", "SELECT 3", executedAt = 3, elapsedMillis = 1, error = "boom"))

        assertEquals(listOf("SELECT 3", "SELECT 1"), history.recent("a").map { it.sql })
        assertEquals(listOf("SELECT 3", "SELECT 2", "SELECT 1"), history.recent().map { it.sql })
    }
}
