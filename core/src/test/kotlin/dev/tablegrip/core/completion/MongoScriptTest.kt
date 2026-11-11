package dev.tablegrip.core.completion

import dev.tablegrip.core.sql.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MongoScriptTest {
    private fun commands(text: String) = MongoScript.split(text).map { it.text }

    @Test
    fun `commands end at a semicolon or a line end, once their brackets close`() {
        assertEquals(listOf("show collections", "db.orders.find({})"), commands("show collections\ndb.orders.find({})"))
        assertEquals(listOf("use shop", "db.orders.countDocuments({})"), commands("use shop; db.orders.countDocuments({});"))
        // Brackets left open carry the command on.
        val multiLine = "db.orders.find({\n  status: 'A',\n  total: {\$gt: 10}\n})"
        assertEquals(listOf(multiLine), commands(multiLine))
        // A line starting with `.` goes on with the command above.
        assertEquals(listOf("db.orders.find({})\n  .sort({total: -1})\n  .limit(5)"), commands("db.orders.find({})\n  .sort({total: -1})\n  .limit(5)\n\ndb.users.find()").take(1))
        // Separators inside strings don't count.
        assertEquals(listOf("db.notes.find({text: 'a; b\\' }'})"), commands("db.notes.find({text: 'a; b\\' }'})"))
    }

    @Test
    fun `comments are left out`() {
        assertEquals(listOf("db.orders.find({})", "db.users.find()"), commands("// orders\ndb.orders.find({}) // all of them\n/* then */ db.users.find()"))
        assertEquals(listOf("db.orders.find({a:  1})"), commands("db.orders.find({a: /* one */ 1})"))
    }

    @Test
    fun `the command at the caret`() {
        val text = "db.a.find()\n\ndb.b.find({\n  x: 1\n})\n"
        assertEquals("db.a.find()", MongoScript.statementAt(text, 3)?.text)
        assertEquals("db.a.find()", MongoScript.statementAt(text, 11)?.text)
        assertEquals("db.b.find({\n  x: 1\n})", MongoScript.statementAt(text, text.indexOf("x:"))?.text)
        assertEquals("db.b.find({\n  x: 1\n})", MongoScript.statementAt(text, text.length)?.text)
        assertNull(MongoScript.statementAt("", 0))
    }

    @Test
    fun `tokens for colouring`() {
        val text = "db.orders.find({\$or: [{s: 'A'}], n: 10}) // x"
        val types = MongoScript.tokenize(text).filter { it.type != TokenType.Whitespace && it.type != TokenType.Operator }.map { text.substring(it.start, it.end) to it.type }
        assertEquals(
            listOf(
                "db" to TokenType.Keyword, "orders" to TokenType.Identifier, "find" to TokenType.Identifier, "\$or" to TokenType.Parameter,
                "s" to TokenType.Identifier, "'A'" to TokenType.String, "n" to TokenType.Identifier, "10" to TokenType.Number, "// x" to TokenType.Comment,
            ),
            types,
        )
        // A collection called like a keyword is a name.
        assertEquals(TokenType.Identifier, MongoScript.tokenize("db.use").last().type)
    }
}
