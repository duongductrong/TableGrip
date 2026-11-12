package dev.tablegrip.core

import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.driver.Database
import dev.tablegrip.core.metadata.ConstraintKind
import dev.tablegrip.core.metadata.Namespace
import dev.tablegrip.core.metadata.SchemaObjectKind
import dev.tablegrip.core.metadata.SchemaObjectRef
import dev.tablegrip.core.metadata.TableRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Reads structure from real servers seeded by `core/src/test/resources/structure-it/` (password `pw`). Skipped unless
 * `-Pit.postgres=host:port` / `-Pit.mysql=host:port` are passed; `-Pit.mariadb=host:port` is a MariaDB seeded with the
 * MySQL script, and `-Pit.mssql` the SQL Server of [ServerEnginesIntegrationTest].
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

    @Test
    fun postgresSchemaObjects() = runBlocking {
        val database = connect(DatabaseType.PostgreSQL, "it.postgres", "postgres", "postgres") ?: return@runBlocking
        database.use {
            val shop = Namespace(null, "shop")
            database.openSession().use { session ->
                session.execute("DROP SEQUENCE IF EXISTS shop.invoice_no")
                session.execute("CREATE SEQUENCE shop.invoice_no INCREMENT BY 10 START WITH 1000 MAXVALUE 99999 CYCLE")
                session.execute("SELECT nextval('shop.invoice_no')")
            }
            val objects = database.schemaObjects(shop)
            println(objects.joinToString("\n"))
            val totals = objects.filter { it.ref.name == "order_total" }
            assertEquals(listOf("integer", "integer, date"), totals.map { it.ref.id })
            assertEquals("(customer integer) → numeric", totals.first().detail)
            assertEquals(SchemaObjectKind.Procedure, objects.single { it.ref.name == "close_orders" }.ref.kind)
            assertTrue(objects.any { it.ref.kind == SchemaObjectKind.Sequence && it.ref.name == "invoice_no" })
            // btree_gist's functions live in public but belong to the extension.
            assertTrue(database.schemaObjects(Namespace(null, "public")).none { it.ref.kind == SchemaObjectKind.Function })
            assertTrue(database.tables(shop).single { it.ref.name == "totals" }.materialized)
            assertTrue(!database.tables(shop).single { it.ref.name == "big_orders" }.materialized)

            val function = database.schemaObjectDefinition(totals.first().ref)
            println(function.source)
            assertTrue(function.source.startsWith("CREATE OR REPLACE FUNCTION shop.order_total(customer integer)"))
            assertTrue("COMMENT ON FUNCTION \"shop\".\"order_total\"(integer) IS 'Sum of a customer''s orders';" in function.source)
            assertEquals("sql", function.properties.toMap()["language"])
            val procedure = database.schemaObjectDefinition(objects.single { it.ref.name == "close_orders" }.ref)
            assertTrue(procedure.source.startsWith("CREATE OR REPLACE PROCEDURE shop.close_orders"))

            val sequence = database.schemaObjectDefinition(SchemaObjectRef(shop, "invoice_no", SchemaObjectKind.Sequence))
            println(sequence.source)
            assertEquals("1000", sequence.properties.toMap()["current value"])
            assertEquals("10", sequence.properties.toMap()["increment"])
            // The DDL recreates the sequence as it was, and the function source runs as is.
            database.openSession().use { session ->
                session.execute("DROP SEQUENCE shop.invoice_no")
                session.execute(sequence.source)
                session.execute(function.replaceSql.substringBefore("\n\nCOMMENT"))
            }
            val again = database.schemaObjectDefinition(SchemaObjectRef(shop, "invoice_no", SchemaObjectKind.Sequence))
            assertEquals(sequence.source, again.source)
        }
    }

    @Test
    fun mysqlSchemaObjects() = runBlocking {
        val database = connect(DatabaseType.MySQL, "it.mysql", "app", "root") ?: return@runBlocking
        database.use { mysqlRoutines(it) }
    }

    @Test
    fun mariadbSchemaObjects() = runBlocking {
        val database = connect(DatabaseType.MariaDB, "it.mariadb", "app", "root") ?: return@runBlocking
        database.use {
            mysqlRoutines(it)
            val app = Namespace("app", null)
            database.openSession().use { session ->
                session.execute("CREATE OR REPLACE SEQUENCE invoice_no START WITH 1000 INCREMENT BY 10")
            }
            assertTrue(database.schemaObjects(app).any { it.ref.kind == SchemaObjectKind.Sequence && it.ref.name == "invoice_no" })
            val sequence = database.schemaObjectDefinition(SchemaObjectRef(app, "invoice_no", SchemaObjectKind.Sequence))
            println(sequence.source)
            assertEquals("10", sequence.properties.toMap()["increment"])
            assertTrue(sequence.replaceSql.startsWith("CREATE OR REPLACE SEQUENCE"))
            val function = database.schemaObjectDefinition(SchemaObjectRef(app, "order_total", SchemaObjectKind.Function))
            assertTrue(function.replaceSql.startsWith("CREATE OR REPLACE DEFINER="))
        }
    }

    private suspend fun mysqlRoutines(database: Database) {
        val app = Namespace("app", null)
        val objects = database.schemaObjects(app)
        println(objects.joinToString("\n"))
        val total = objects.single { it.ref.name == "order_total" }
        assertEquals(SchemaObjectKind.Function, total.ref.kind)
        // MariaDB writes int as int(11).
        assertEquals("(customer int) → decimal(10,2)", total.detail?.replace("int(11)", "int"))
        val procedure = objects.single { it.ref.name == "close_orders" }
        assertEquals("(customer int, OUT closed int)", procedure.detail?.replace("int(11)", "int"))
        val definition = database.schemaObjectDefinition(total.ref)
        println(definition.source)
        assertTrue(definition.source.startsWith("CREATE DEFINER="))
        assertTrue("RETURN (SELECT COALESCE(SUM(total), 0)" in definition.source)
        assertEquals("Sum of a customer's orders", definition.properties.toMap()["comment"])
        assertTrue(database.schemaObjectDefinition(procedure.ref).source.contains("PROCEDURE `close_orders`"))
    }

    @Test
    fun sqlServerSchemaObjects() = runBlocking<Unit> {
        val (host, port) = System.getProperty("it.mssql")?.split(":") ?: return@runBlocking
        val config =
            ConnectionConfig(
                id = "it.mssql", name = "it.mssql", type = DatabaseType.SqlServer, host = host, port = port.toInt(), database = "master", user = "sa",
                properties = mapOf("trustServerCertificate" to "true"),
            )
        Database.connect(config, password = "TableGrip#2026").use { database ->
            database.openSession().use { session ->
                listOf(
                    "DROP PROCEDURE IF EXISTS dbo.close_orders", "DROP FUNCTION IF EXISTS dbo.order_total", "DROP FUNCTION IF EXISTS dbo.big_ones",
                    "DROP SEQUENCE IF EXISTS dbo.invoice_no",
                    "CREATE FUNCTION dbo.order_total(@customer int) RETURNS decimal(10,2) AS BEGIN RETURN 0 END",
                    "CREATE FUNCTION dbo.big_ones(@min int) RETURNS TABLE AS RETURN SELECT 1 AS id WHERE 1 > @min",
                    "CREATE PROCEDURE dbo.close_orders @customer int, @closed int OUTPUT AS SET @closed = 0",
                    "CREATE SEQUENCE dbo.invoice_no AS bigint START WITH 1000 INCREMENT BY 10",
                ).forEach { session.execute(it) }
                // Consoles limit rows with SET ROWCOUNT, which NEXT VALUE FOR refuses.
                session.execute("DECLARE @first sql_variant; EXEC sys.sp_sequence_get_range N'dbo.invoice_no', 1, @first OUTPUT")
            }
            val dbo = Namespace(null, "dbo")
            val objects = database.schemaObjects(dbo)
            println(objects.joinToString("\n"))
            assertEquals("(@customer int) → decimal", objects.single { it.ref.name == "order_total" }.detail)
            assertEquals("(@min int) → TABLE", objects.single { it.ref.name == "big_ones" }.detail)
            val procedure = objects.single { it.ref.name == "close_orders" }
            assertEquals(SchemaObjectKind.Procedure to "(@customer int, @closed int OUTPUT)", procedure.ref.kind to procedure.detail)
            val source = database.schemaObjectDefinition(procedure.ref)
            assertTrue(source.replaceSql.startsWith("CREATE OR ALTER PROCEDURE dbo.close_orders"))
            val sequence = database.schemaObjectDefinition(SchemaObjectRef(dbo, "invoice_no", SchemaObjectKind.Sequence))
            println(sequence.source)
            assertEquals("1000", sequence.properties.toMap()["current value"])
            database.openSession().use { session ->
                session.execute("DROP SEQUENCE dbo.invoice_no")
                session.execute(sequence.source)
            }
        }
    }
}
