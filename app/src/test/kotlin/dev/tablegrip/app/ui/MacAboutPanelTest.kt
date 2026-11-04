package dev.tablegrip.app.ui

import dev.tablegrip.app.update.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MacAboutPanelTest {
    @Test
    fun `an installed copy shows its full version and build number`() {
        val info = AboutInfo.of(AppVersion("1.4.0-beta.2", 321), packaged = true, name = null)
        assertEquals("TableGrip", info.name)
        assertEquals("1.4.0-beta.2", info.version)
        assertEquals("321", info.build)
        assertNull(info.iconResource, "the bundle's own icon")
    }

    @Test
    fun `a copy run from source says so instead of describing Java`() {
        val info = AboutInfo.of(AppVersion("1.4.0", 7), packaged = false, name = "TableGrip Dev")
        assertEquals("TableGrip Dev", info.name)
        assertEquals("Development", info.build)
        assertEquals(AboutInfo.IconResource, info.iconResource)
        assertNotNull(AboutInfo::class.java.getResource(AboutInfo.IconResource))
    }

    @Test
    fun `a blank application name falls back to TableGrip`() {
        assertEquals("TableGrip", AboutInfo.of(AppVersion("1.0.0", 1), packaged = true, name = " ").name)
    }
}
