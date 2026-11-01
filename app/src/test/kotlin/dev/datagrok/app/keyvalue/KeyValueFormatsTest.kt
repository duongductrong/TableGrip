package dev.datagrok.app.keyvalue

import dev.datagrok.core.driver.KeyInfo
import dev.datagrok.core.driver.KeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyValueFormatsTest {
    private fun key(name: String) = KeyInfo(name, KeyType.Text, null)

    private fun List<KeyRow>.render() =
        map { row ->
            "  ".repeat(row.depth) +
                when (row) {
                    is KeyRow.Group -> "${if (row.expanded) "v" else ">"} ${row.label} (${row.keyCount})"
                    is KeyRow.Key -> row.label
                }
        }

    @Test
    fun `keys group into folders on the delimiter, folders first`() {
        val keys = listOf("user:2", "session:abc", "user:1:profile", "leaders", "user:1:cart").map(::key)
        assertEquals(
            listOf("v session (1)", "  abc", "v user (3)", "  v 1 (2)", "    cart", "    profile", "  2", "leaders"),
            keyTree(keys, ":", emptySet()).render(),
        )
    }

    @Test
    fun `collapsed folders hide their keys but keep their counts`() {
        val keys = listOf("user:1", "user:2", "session:abc").map(::key)
        assertEquals(listOf("v session (1)", "  abc", "> user (2)"), keyTree(keys, ":", setOf("user:")).render())
        assertEquals(setOf("user:", "session:"), keyGroups(keys, ":"))
    }

    @Test
    fun `a leading delimiter, as etcd uses, stays in the path but not the label`() {
        val rows = keyTree(listOf("/dg/services/api/port", "/dg/feature-flags").map(::key), "/", emptySet())
        assertEquals(listOf("v dg (2)", "  v services (1)", "    v api (1)", "      port", "  feature-flags"), rows.render())
        assertEquals("/dg/services/", (rows[1] as KeyRow.Group).path)
    }

    @Test
    fun `ttl amounts read back in the largest even unit`() {
        assertEquals(TtlUnit.Hours, TtlUnit.fitting(7_200))
        assertEquals(TtlUnit.Minutes, TtlUnit.fitting(120))
        assertEquals(TtlUnit.Seconds, TtlUnit.fitting(90))
        assertEquals(TtlUnit.Seconds, TtlUnit.fitting(59))
        assertEquals(TtlUnit.Days, TtlUnit.fitting(172_800))
        assertEquals(5_400L, ttlSeconds(" 90 ", TtlUnit.Minutes))
        assertNull(ttlSeconds("0", TtlUnit.Hours))
        assertNull(ttlSeconds("1.5", TtlUnit.Hours))
        assertEquals(1L to TtlUnit.Hours, ttlForEditing(3_599))
        assertEquals(45L to TtlUnit.Seconds, ttlForEditing(45))
        assertEquals(90L to TtlUnit.Minutes, ttlForEditing(5_390))
        assertEquals("1d", formatTtl(86_400_000))
        assertEquals("59m 59s", formatTtl(3_599_000))
    }

    @Test
    fun `only objects and arrays count as json documents`() {
        assertNotNull(parseJsonDocument(" {\"user\": 1} "))
        assertNotNull(parseJsonDocument("[1, 2]"))
        assertNull(parseJsonDocument("42"))
        assertNull(parseJsonDocument("\"text\""))
        assertNull(parseJsonDocument("{not json}"))
        assertEquals("{\n  \"a\": 1\n}", prettyJson(parseJsonDocument("{\"a\":1}")!!))
    }

    @Test
    fun `counts name their elements`() {
        assertEquals("1 field", elementNoun(KeyType.Hash, 1))
        assertEquals("1,200 members", elementNoun(KeyType.SortedSet, 1_200))
        assertEquals("3 entries", elementNoun(KeyType.Stream, 3))
        assertEquals("1.5 KB", formatBytes(1_536))
    }
}
