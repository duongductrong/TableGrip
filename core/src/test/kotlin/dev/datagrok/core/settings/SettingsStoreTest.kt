package dev.datagrok.core.settings

import dev.datagrok.core.theme.SyntaxStyle
import dev.datagrok.core.theme.ThemeOverride
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Test
    fun `a settings file from before customization loads with today's look`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        file.writeText("""{ "theme": "Dark", "editorFontSize": 15, "pageSize": 500 }""")
        val loaded = SettingsStore(file).load()
        assertEquals(Settings(theme = ThemeSelection(mode = "Dark"), editorFontSize = 15, pageSize = 500), loaded)
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

    @Test
    fun `the theme takes Zed's shape and a bare mode from older files still loads`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        file.writeText("""{ "theme": "Light" }""")
        assertEquals(ThemeSelection(mode = "Light"), SettingsStore(file).load().theme)
        file.writeText("""{ "theme": { "mode": "Dark", "dark": "One Dark", "light": "One Light" } }""")
        assertEquals(ThemeSelection("Dark", "One Dark", "One Light"), SettingsStore(file).load().theme)
    }

    @Test
    fun `hand-written settings may have comments and trailing commas`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        file.writeText(
            """
            {
              // Like VS Code's and Zed's settings
              "pageSize": 300,
              "themeOverrides": { "One Dark": { "colors": { "accent": "#E5C07B" }, "syntax": { "keyword": "#FF79C6" } } },
            }
            """.trimIndent()
        )
        val store = SettingsStore(file)
        val loaded = store.load()
        assertNull(store.loadError)
        assertEquals(300, loaded.pageSize)
        assertEquals(ThemeOverride(mapOf("accent" to "#E5C07B"), mapOf("keyword" to SyntaxStyle("#FF79C6"))), loaded.themeOverrides["One Dark"])
    }

    @Test
    fun `a file that doesn't parse is never overwritten with defaults`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        val broken = """{ "pageSize": 300, "editorFontSize": }"""
        file.writeText(broken)
        val store = SettingsStore(file)
        assertEquals(Settings(), store.load())
        assertNotNull(store.loadError)
        assertFalse(store.save(Settings(pageSize = 10)))
        assertEquals(broken, file.readText())
        // Once fixed by hand, it loads and saves again.
        file.writeText("""{ "pageSize": 300 }""")
        assertEquals(300, store.load().pageSize)
        assertTrue(store.save(Settings(pageSize = 10)))
    }

    @Test
    fun `only settings that differ from the defaults are written`() {
        val file = createTempDirectory("settings").resolve("settings.json")
        SettingsStore(file).save(Settings(pageSize = 50, theme = ThemeSelection(dark = "Dracula")))
        val text = file.readText()
        assertTrue("pageSize" in text && "Dracula" in text)
        assertFalse("editorFontSize" in text || "mode" in text, text)
    }
}
