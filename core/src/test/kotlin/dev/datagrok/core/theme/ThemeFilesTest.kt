package dev.datagrok.core.theme

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeFilesTest {
    @Test
    fun `a theme file reads like Zed's, with tokens as colours or styles`() {
        val family =
            ThemeJson.parse(
                """
                {
                  "${'$'}schema": "https://datagrok.dev/schema/theme-v1.json",
                  "name": "Mine",
                  "themes": [
                    {
                      "name": "Mine Dark",
                      "appearance": "dark",
                      // Just the keys that differ
                      "colors": { "accent": "#FF8800", "some.future.key": "#000000" },
                      "syntax": { "keyword": "#C678DD", "comment": { "color": "#5C6370", "fontStyle": "italic" } },
                    },
                  ],
                }
                """.trimIndent()
            )
        val dark = family.themes.single()
        assertTrue(dark.isDark)
        assertEquals("#FF8800", dark.colors["accent"])
        assertEquals(SyntaxStyle("#C678DD"), dark.syntax["keyword"])
        assertEquals(SyntaxStyle("#5C6370", fontStyle = "italic"), dark.syntax["comment"])
        // Writing keeps the short form for plain colours.
        val again = ThemeJson.parse(ThemeJson.encode(family))
        assertEquals(family, again)
        assertTrue("\"keyword\": \"#C678DD\"" in ThemeJson.encode(family))
    }

    @Test
    fun `overrides lay keys over a variant`() {
        val spec = ThemeSpec("One Dark", colors = mapOf("accent" to "#4D78CC", "text" to "#ABB2BF"), syntax = mapOf("keyword" to SyntaxStyle("#C678DD")))
        val merged = spec.with(ThemeOverride(colors = mapOf("accent" to "#E5C07B"), syntax = mapOf("string" to SyntaxStyle("#00FF00"))))
        assertEquals(mapOf("accent" to "#E5C07B", "text" to "#ABB2BF"), merged.colors)
        assertEquals(setOf("keyword", "string"), merged.syntax.keys)
    }

    @Test
    fun `a broken file in the themes folder is reported, the others still load`() {
        val dir = createTempDirectory("themes")
        dir.resolve("good.json").writeText("""{ "name": "Good", "themes": [ { "name": "Good Light", "appearance": "light" } ] }""")
        dir.resolve("bad.json").writeText("""{ "name": "Bad", "themes": [ """)
        dir.resolve("notes.txt").writeText("ignored")
        val files = ThemeJson.loadDirectory(dir)
        assertEquals(listOf("Good"), files.families.map { it.name })
        assertEquals(listOf("bad.json"), files.errors.map { it.file.fileName.toString() })
    }
}
