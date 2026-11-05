package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.metadata.ConstraintKind
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Reads structure from real servers seeded by `core/src/test/resources/structure-it/` (password `pw`). Skipped unless
 * `-Pit.postgres=host:port` / `-Pit.mysql=host:port` are passed.
 */
class StructureIntegrationTest {
    private fun connect(type: DatabaseType, property: String, database: String, user: String): Database? {
        val (host, port) = System.getProperty(property)?.split(":") ?: return null
        val config = ConnectionConfig(id = property, name = property, type = type, host = host, port = port.toInt(), database = database, user = user)
        return runBlocking { Database.connect(config, password = "pw") }
    }

    @Test
    fun postgres() = runBlocking {
        val database = connect(DatabaseType.PostgreSQL, "it.postgres", "postgres", "postgres") ?: return@runBlocking
        database.use {
            val orders = database.tableStructure(TableRef(Namespace(null, "shop"), "orders"))
            println(orders.ddl)
            assertEquals(
                listOf(ConstraintKind.PrimaryKey, ConstraintKind.ForeignKey, ConstraintKind.Check, ConstraintKind.Exclusion),
                orders.constraints.map { it.kind },
            )
            assertEquals(listOf("customer_id"), orders.constraints[1].columns)
            assertTrue("ALTER TABLE \"shop\".\"orders\" DISABLE TRIGGER \"orders_audit\";" in orders.ddl)
            assertEquals(
                listOf(Triple("orders_audit", "AFTER", "DELETE") to false, Triple("orders_touch", "BEFORE", "INSERT OR UPDATE") to true),
                orders.triggers.map { Triple(it.name, it.timing, it.events) to it.enabled },
            )
            val fk = orders.details.foreignKeys.single()
            assertEquals("CASCADE" to "SET NULL", fk.onDelete to fk.onUpdate)

            val shop = Namespace(null, "shop")
            val totals = database.tableStructure(TableRef(shop, "totals")).ddl
            assertTrue(totals.startsWith("CREATE MATERIALIZED VIEW \"shop\".\"totals\" AS"))
            val bigOrders = database.tableStructure(TableRef(shop, "big_orders")).ddl

            // The generated DDL must recreate the table as it was, and the views that depend on it.
            database.openSession().use { session ->
                session.execute("DROP TABLE shop.orders CASCADE")
                session.execute(orders.ddl)
                session.execute(bigOrders)
                session.execute(totals)
            }
            assertEquals(orders.ddl, database.tableStructure(TableRef(Namespace(null, "shop"), "orders")).ddl)

            val ns = Namespace(null, "shop")
            println(database.tableStructure(TableRef(ns, "events")).ddl)
            println(database.tableStructure(TableRef(Namespace(null, "public"), "Mixed Case")).ddl)
            val view = database.tableStructure(TableRef(ns, "big_orders"))
            println(view.ddl)
            assertTrue(view.ddl.startsWith("CREATE OR REPLACE VIEW \"shop\".\"big_orders\" AS"))
            assertTrue(view.constraints.isEmpty())
        }
    }

    @Test
    fun mysql() = runBlocking {
        val database = connect(DatabaseType.MySQL, "it.mysql", "app", "root") ?: return@runBlocking
        database.use {
            val orders = database.tableStructure(TableRef(Namespace("app", null), "orders"))
            println(orders.ddl)
            println(orders.constraints.joinToString("\n"))
            assertEquals(
                listOf(ConstraintKind.PrimaryKey, ConstraintKind.ForeignKey, ConstraintKind.Check, ConstraintKind.Check),
                orders.constraints.map { it.kind },
            )
            assertTrue(orders.constraints.first { it.name == "orders_customer" }.definition.endsWith("ON DELETE CASCADE"))
            assertTrue(orders.constraints.first { it.name == "positive_total" }.definition.startsWith("CHECK ("))
            assertTrue("\\" !in orders.constraints.first { it.name == "orders_chk_1" }.definition)
            assertEquals(listOf(Triple("orders_bi", "BEFORE", "INSERT")), orders.triggers.map { Triple(it.name, it.timing, it.events) })
            assertTrue(orders.ddl.startsWith("CREATE TABLE `orders`"))
            assertTrue("CREATE TRIGGER `orders_bi` BEFORE INSERT ON `orders`" in orders.ddl)

            val customers = database.tableStructure(TableRef(Namespace("app", null), "customers"))
            assertEquals(listOf(ConstraintKind.PrimaryKey, ConstraintKind.Unique), customers.constraints.map { it.kind })
            val view = database.tableStructure(TableRef(Namespace("app", null), "big_orders"))
            println(view.ddl)
            assertTrue(view.ddl.startsWith("CREATE ALGORITHM"))
        }
    }
}
