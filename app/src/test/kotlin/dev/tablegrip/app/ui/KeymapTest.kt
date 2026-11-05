package dev.tablegrip.app.ui

import androidx.compose.ui.input.key.Key
import dev.tablegrip.core.settings.KeyBinding
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeymapTest {
    @AfterTest
    fun restoreDefaults() = Shortcuts.configure(KeymapPreset.Default, emptyMap())

    @Test
    fun `every action has a unique id and the default keymap changes nothing`() {
        val ids = Shortcuts.actions.map { it.id }
        assertEquals(ids.distinct(), ids)
        Shortcuts.configure(KeymapPreset.Default, emptyMap())
        Shortcuts.actions.forEach { assertEquals(it.default, Shortcuts.current(it), it.id) }
    }

    @Test
    fun `a user binding wins over the preset and reaches the shortcut properties`() {
        val custom = Shortcut(Key.K, "K", primary = true, alt = true)
        Shortcuts.configure(KeymapPreset.VsCode, mapOf("Format" to custom.toBinding()))
        assertEquals(custom, Shortcuts.Format)
        // Untouched by the user: the preset's.
        assertEquals(Shortcut(Key.K, "K", primary = true, shift = true), Shortcuts.DeleteLine)
        // Untouched by both: the default.
        assertEquals(assertNotNull(Shortcuts.byId("Comment")).default, Shortcuts.Comment)
    }

    @Test
    fun `bindings for unknown actions are ignored`() {
        Shortcuts.configure(KeymapPreset.Default, mapOf("NoSuchAction" to KeyBinding(65, "A", primary = true)))
        Shortcuts.actions.forEach { assertEquals(it.default, Shortcuts.current(it)) }
    }

    @Test
    fun `presets load by name, and an unknown name falls back to the default`() {
        KeymapPreset.entries.forEach { assertEquals(it, KeymapPreset.of(it.name)) }
        assertEquals(KeymapPreset.Default, KeymapPreset.of("Nonsense"))
    }

    @Test
    fun `a binding survives the trip through settings`() {
        val shortcut = Shortcut(Key.DirectionDown, "↓", alt = true, shift = true)
        assertEquals(shortcut, shortcut.toBinding().toShortcut())
    }

    @Test
    fun `presets only name real actions and keep the console free of clashes`() {
        KeymapPreset.entries.forEach { preset ->
            preset.bindings().keys.forEach { id -> assertNotNull(Shortcuts.byId(id), "${preset.name} binds unknown action $id") }
            Shortcuts.configure(preset, emptyMap())
            val console = Shortcuts.actions.filter { it.group in setOf("General", "Console", "SQL Editor") }.map { Shortcuts.current(it).copy(keyLabel = "") to it.id }
            val clashes = console.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            assertTrue(clashes.isEmpty(), "${preset.name}: $clashes")
        }
    }
}
