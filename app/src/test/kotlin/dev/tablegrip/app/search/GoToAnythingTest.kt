package dev.tablegrip.app.search

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoToAnythingTest {
    @Test
    fun `fuzzy matching follows word starts and order`() {
        assertNotNull(fuzzyScore("user_orders", "uo"))
        assertNotNull(fuzzyScore("UserOrders", "UO"))
        assertNotNull(fuzzyScore("order_items", "ord it"))
        assertNull(fuzzyScore("orders", "ou"))
        assertEquals(0, fuzzyScore("anything", " "))
    }

    @Test
    fun `better matches rank first`() {
        fun item(label: String, kind: GoToKind = GoToKind.Table) = GoToItem(kind, label) {}
        val items = listOf(item("customer_orders"), item("orders"), item("order_items"), item("users"), item("New Console", GoToKind.Action))
        assertEquals("orders", rankGoTo(items, "orders").first().label)
        assertEquals(listOf("orders", "order_items", "customer_orders"), rankGoTo(items, "ord").map { it.label })
        assertEquals("users", rankGoTo(items, "us").first().label)
        assertEquals(listOf("New Console"), rankGoTo(items, "").map { it.label })
    }

    @Test
    fun `double shift opens only with nothing in between`() {
        var now = 1_000L
        val detector = DoubleShiftDetector(window = 400) { now }
        fun tap(key: Key = Key.ShiftLeft): Boolean {
            detector.onKey(key, KeyEventType.KeyDown)
            return detector.onKey(key, KeyEventType.KeyUp)
        }
        assertFalse(tap())
        now += 200
        assertTrue(tap())

        now += 1_000
        assertFalse(tap())
        now += 100
        detector.onKey(Key.ShiftLeft, KeyEventType.KeyDown)
        detector.onKey(Key.A, KeyEventType.KeyDown)
        assertFalse(detector.onKey(Key.ShiftLeft, KeyEventType.KeyUp))

        now += 1_000
        assertFalse(tap())
        now += 600
        assertFalse(tap())
    }
}
