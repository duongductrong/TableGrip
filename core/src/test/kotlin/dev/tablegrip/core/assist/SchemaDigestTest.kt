package dev.tablegrip.core.assist

import dev.tablegrip.core.metadata.ColumnInfo
import dev.tablegrip.core.metadata.ForeignKeyInfo
import dev.tablegrip.core.metadata.IndexInfo
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableDetails
import dev.tablegrip.core.metadata.TableRef
import java.sql.Types
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaDigestTest {
    private val main = Namespace(null, "main")

    private fun column(name: String, type: String, pk: Boolean = false, nullable: Boolean = true, default: String? = null) =
        ColumnInfo(name, type, null, nullable, default, autoIncrement = false, primaryKey = pk, jdbcType = Types.OTHER)

    @Test
    fun `a table lists its columns, keys and indexes`() {
        val details =
            TableDetails(
                TableRef(main, "orders"),
                listOf(column("id", "INTEGER", pk = true, nullable = false), column("user_id", "INTEGER"), column("status", "TEXT", default = "'new'")),
                listOf(IndexInfo("orders_user", listOf("user_id"), unique = false)),
                listOf(ForeignKeyInfo("fk_user", listOf("user_id"), TableRef(main, "users"), listOf("id"))),
            )
        assertEquals(
            """
            Columns:
              id integer pk not null
              user_id integer
              status text default 'new'
            Primary key: id
            Foreign key: (user_id) -> users(id)
            Index orders_user: (user_id)
            """.trimIndent(),
            SchemaDigest.table(details),
        )
    }
}
