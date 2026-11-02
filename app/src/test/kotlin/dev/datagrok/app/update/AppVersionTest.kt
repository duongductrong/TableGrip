package dev.datagrok.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun readsTheVersionTheBuildWrote() {
        val version = AppVersion.current
        assertTrue(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(version.version), version.version)
        assertTrue(version.build > 0, "build ${version.build}")
    }

    @Test
    fun aSuffixMarksAPrerelease() {
        assertTrue(AppVersion("1.4.0-beta.2", 7).isPrerelease)
        assertFalse(AppVersion("1.4.0", 8).isPrerelease)
        assertEquals("1.4.0 (8)", AppVersion("1.4.0", 8).toString())
    }
}
