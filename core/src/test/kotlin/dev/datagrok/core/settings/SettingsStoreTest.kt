package dev.datagrok.core.settings

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreTest {
    @Test
    fun `a settings file from before customization loads with today's look`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        file.writeText("""{ "theme": "Dark", "editorFontSize": 15, "pageSize": 500 }""")
        val loaded = SettingsStore(file).load()
        assertEquals(Settings(theme = "Dark", editorFontSize = 15, pageSize = 500), loaded)
        // The defaults are what the app hard-coded before they became settings.
        assertEquals("<null>", loaded.nullText)
        assertEquals(4, loaded.tabSize)
        assertEquals(150, loaded.editorLineHeight)
        assertEquals("Upper", loaded.formatKeywordCase)
        assertEquals("Comfortable", loaded.gridDensity)
        assertEquals(10, loaded.islandCornerRadius)
    }

    @Test
    fun `custom colours and key bindings survive a round trip`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        val store = SettingsStore(file)
        val settings =
            Settings(
                accentColor = "#D95C9A",
                syntaxColors = mapOf("dark.keyword" to "#FF0000"),
                keymap = "VsCode",
                keyBindings = mapOf("Format" to KeyBinding(76, "L", primary = true, shift = true)),
                stripeHidden = listOf("Queries"),
            )
        store.save(settings)
        assertEquals(settings, store.load())
    }
}
