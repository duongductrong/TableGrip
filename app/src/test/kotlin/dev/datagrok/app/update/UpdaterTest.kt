package dev.datagrok.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdaterTest {
    @Test
    fun eachSystemAndArchitectureHasItsOwnFeed() {
        assertEquals("${Updater.FeedBase}/macos-arm64.xml", Updater.feedUrl("Mac OS X", "aarch64", override = null))
        assertEquals("${Updater.FeedBase}/macos-x64.xml", Updater.feedUrl("Mac OS X", "x86_64", override = null))
        assertEquals("${Updater.FeedBase}/windows-x64.xml", Updater.feedUrl("Windows 11", "amd64", override = null))
    }

    @Test
    fun aTestFeedMustBeHttpsOrOnThisMachine() {
        assertEquals("http://localhost:8089/appcast.xml", Updater.feedUrl("Mac OS X", "aarch64", override = "http://localhost:8089/appcast.xml"))
        assertEquals("https://example.com/feed.xml", Updater.feedUrl("Mac OS X", "aarch64", override = "https://example.com/feed.xml"))
        assertEquals("${Updater.FeedBase}/macos-arm64.xml", Updater.feedUrl("Mac OS X", "aarch64", override = "http://example.com/feed.xml"))
        assertFalse(Updater.isAllowedFeed("file:///tmp/appcast.xml"))
        assertFalse(Updater.isAllowedFeed("https://"))
        assertTrue(Updater.isAllowedFeed("http://127.0.0.1:8089/a.xml"))
    }

    @Test
    fun anUnknownChannelIsStable() {
        assertEquals(UpdateChannel.Stable, UpdateChannel.of("Nightly"))
        assertEquals(UpdateChannel.Beta, UpdateChannel.of("Beta"))
    }

    @Test
    fun runningFromSourceHasNoUpdater() {
        assertTrue(Updater.create().unavailableReason != null)
    }
}
