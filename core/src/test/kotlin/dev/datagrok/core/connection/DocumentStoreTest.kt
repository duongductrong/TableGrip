package dev.datagrok.core.connection

import dev.datagrok.core.driver.DocumentStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentStoreTest {
    @Test
    fun `reads are told from writes in console commands`() {
        listOf(
            "show dbs",
            "use shop",
            "db.orders.find({status: 'A'}).sort({total: -1}).limit(5)",
            "db.getCollection('order-items').countDocuments({})",
            "db.orders.aggregate([{\$match: {a: 1}}, {\$group: {_id: '\$b'}}])",
            "db.runCommand({ listCollections: 1 })",
            "db.runCommand({\"dbStats\": 1})",
            "db.getCollectionNames()",
        ).forEach { assertTrue(DocumentStore.isRead(it), it) }
        listOf(
            "db.orders.insertOne({a: 1})",
            "db.orders.updateMany({}, {\$set: {a: 1}})",
            "db.orders.aggregate([{\$match: {}}, {\$out: 'copy'}])",
            "db.orders.aggregate([{\$merge: {into: 'copy'}}])",
            "db.runCommand({drop: 'orders'})",
            "db.adminCommand({shutdown: 1})",
            "db.dropDatabase()",
            "db.orders.drop()",
            "db.orders.find().forEach(d => db.x.insertOne(d))".takeIf { false } ?: "something unknown",
        ).forEach { assertFalse(DocumentStore.isRead(it), it) }
    }

    @Test
    fun `mongodb connection strings keep their password out of the profile`() {
        val parsed = ConnectionString.parse("mongodb+srv://app:s3cret@cluster0.example.net/shop?retryWrites=true")
        kotlin.test.assertEquals(DatabaseType.MongoDB, parsed.type)
        kotlin.test.assertEquals("mongodb+srv://app@cluster0.example.net/shop?retryWrites=true" to "s3cret", parsed.url to parsed.password)
    }
}
