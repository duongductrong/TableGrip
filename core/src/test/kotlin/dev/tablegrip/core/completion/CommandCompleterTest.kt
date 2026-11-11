package dev.tablegrip.core.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandCompleterTest {
    private val keys = listOf("user:1", "user:2", "user:10", "session:abc", "my key", "orders")

    private fun complete(text: String, catalog: CommandCatalog = ConsoleCommands.Redis, readOnly: Boolean = false): CompletionResult {
        val caret = text.indexOf('|')
        return CommandCompleter.complete(text.removeRange(caret, caret + 1), caret, catalog, { prefix -> keys.filter { it.startsWith(prefix) } }, readOnly)
    }

    private fun labels(text: String, catalog: CommandCatalog = ConsoleCommands.Redis, readOnly: Boolean = false) = complete(text, catalog, readOnly).items.map { it.label }

    /** The line after accepting [label] (or the first suggestion), with `|` at the caret. */
    private fun accept(text: String, label: String? = null, catalog: CommandCatalog = ConsoleCommands.Redis): String {
        val caret = text.indexOf('|')
        val plain = text.removeRange(caret, caret + 1)
        val result = CommandCompleter.complete(plain, caret, catalog, { prefix -> keys.filter { it.startsWith(prefix) } })
        val item = label?.let { l -> result.items.first { it.label == l } } ?: result.items.first()
        val after = plain.replaceRange(result.replaceStart, result.replaceEnd, item.insertText)
        val at = result.replaceStart + item.caretOffset
        return after.substring(0, at) + "|" + after.substring(at)
    }

    @Test
    fun `command names complete in the case typed`() {
        assertEquals(listOf("HGET", "HGETALL"), labels("HGE|"))
        assertEquals("hgetall |", accept("hgeta|"))
        assertEquals("HGETALL |", accept("HGETA|"))
        // Containers are offered once, then their subcommands.
        assertEquals(1, labels("CONF|").count { it == "CONFIG" })
        assertEquals(listOf("GET"), labels("CONFIG G|"))
        assertEquals("config get |", accept("config g|"))
        // Words merely containing what's typed come after prefix matches.
        assertTrue(labels("RANGE|").containsAll(listOf("ZRANGE", "LRANGE", "GETRANGE")))
    }

    @Test
    fun `keys are offered where the command takes a key`() {
        assertEquals(listOf("user:1", "user:2", "user:10"), labels("GET us|"))
        assertEquals("GET user:10 |", accept("GET user:1|", "user:10"))
        // Names with spaces are quoted the way the console splits them.
        assertEquals("DEL \"my key\" |", accept("DEL my|"))
        // Every argument of a key list is a key.
        assertEquals(listOf("orders"), labels("MGET user:1 or|"))
        // A value isn't a key.
        assertTrue(labels("SET user:1 us|").isEmpty())
        // A key typed inside quotes is replaced, quotes and all.
        assertEquals("GET \"my key\" |", accept("GET \"my|\""))
    }

    @Test
    fun `options come where the syntax puts them, and option arguments take their values`() {
        // After the key and value of SET, its options.
        assertTrue(labels("SET k v |").containsAll(listOf("NX", "XX", "EX", "PX", "KEEPTTL")))
        assertEquals(listOf("EX", "EXAT"), labels("SET k v E|"))
        // Not before the value.
        assertFalse("NX" in labels("SET k |"))
        // An option isn't offered twice.
        assertFalse("MATCH" in labels("SCAN 0 MATCH user:* |"))
        assertTrue(labels("SCAN 0 |").containsAll(listOf("MATCH", "COUNT", "TYPE")))
        assertEquals(listOf("hash"), labels("SCAN 0 TYPE h|"))
        // MATCH takes a pattern built from the keys' namespaces.
        assertEquals(listOf("user:*", "session:*"), labels("SCAN 0 MATCH |").take(2))
        assertEquals(listOf("BEFORE", "AFTER"), labels("LINSERT mylist |"))
        // ZADD's flags go before the scores.
        assertTrue("NX" in labels("ZADD board |"))
        assertTrue(labels("INFO |").containsAll(listOf("memory", "keyspace")))
        assertEquals(listOf("NORMAL", "MASTER", "REPLICA", "PUBSUB"), labels("CLIENT LIST TYPE |"))
    }

    @Test
    fun `a read-only console doesn't offer writes`() {
        val all = labels("S|", readOnly = true)
        assertTrue("SCAN" in all && "SMEMBERS" in all, all.toString())
        assertFalse("SET" in all || "SADD" in all)
        assertTrue(labels("SET k v |", readOnly = true).isEmpty())
    }

    @Test
    fun `the hint is the typed command's syntax`() {
        assertEquals("SET key value [NX|XX] [GET] [EX seconds|PX milliseconds|EXAT unix-time-seconds|PXAT unix-time-milliseconds|KEEPTTL] — set a string value", CommandCompleter.hint("set k", 5, ConsoleCommands.Redis))
        assertEquals("CONFIG GET parameter [parameter ...] — configuration values", CommandCompleter.hint("CONFIG GET ", 11, ConsoleCommands.Redis))
        assertNull(CommandCompleter.hint("NOPE x", 6, ConsoleCommands.Redis))
    }

    @Test
    fun `etcd commands, flags and keys`() {
        val etcd = ConsoleCommands.Etcd
        assertEquals(listOf("get"), labels("g|", etcd))
        assertEquals(listOf("grant"), labels("lease g|", etcd))
        assertEquals(listOf("user:1", "user:2", "user:10"), labels("get us|", etcd))
        assertEquals(listOf("--prefix", "--keys-only", "--limit="), labels("get user: --|", etcd))
        // Flags may come before the key, and a value flag leaves the caret after its `=`.
        assertEquals("get --limit=|", accept("get --li|", catalog = etcd))
        assertFalse("--prefix" in labels("get user: --prefix --|", etcd))
        assertEquals(listOf("--lease="), labels("put k v --|", etcd))
        assertTrue(labels("p|", etcd, readOnly = true).isEmpty())
    }
}
