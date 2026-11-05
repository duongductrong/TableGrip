package dev.tablegrip.core.testdata

import dev.tablegrip.core.metadata.ColumnInfo
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TestDataGeneratorTest {
    private val now = LocalDateTime.of(2026, 10, 30, 0, 0)

    private fun column(
        name: String,
        typeName: String,
        jdbcType: Int = Types.OTHER,
        size: Int? = null,
        nullable: Boolean = false,
        primaryKey: Boolean = false,
        autoIncrement: Boolean = false,
        default: String? = null,
    ) = ColumnInfo(name, typeName, size, nullable, default, autoIncrement, primaryKey, jdbcType)

    private fun varchar(name: String, size: Int = 255, nullable: Boolean = false) = column(name, "varchar", Types.VARCHAR, size, nullable)

    private fun int(name: String, typeName: String = "int4", jdbcType: Int = Types.INTEGER) = column(name, typeName, jdbcType)

    private fun suggest(column: ColumnInfo, foreignKey: Boolean = false, unique: Boolean = false) =
        GeneratorSuggestion.suggest(column, foreignKey, unique).kind

    private fun generate(vararg plans: ColumnPlan, count: Int = 100, seed: Long = 7, pools: Map<String, List<String>> = emptyMap()) =
        TestDataGenerator(plans.toList(), seed, pools, now).rows(count).toList()

    @Test
    fun `suggests text generators from column names in any case style`() {
        assertEquals(GeneratorKind.Email, suggest(varchar("email")))
        assertEquals(GeneratorKind.Email, suggest(varchar("user_email")))
        assertEquals(GeneratorKind.Email, suggest(varchar("emailAddress")))
        assertEquals(GeneratorKind.Phone, suggest(varchar("phone_number")))
        assertEquals(GeneratorKind.Phone, suggest(varchar("phoneNumber")))
        assertEquals(GeneratorKind.PostalCode, suggest(varchar("zip")))
        assertEquals(GeneratorKind.PostalCode, suggest(varchar("postal-code")))
        assertEquals(GeneratorKind.FirstName, suggest(varchar("first_name")))
        assertEquals(GeneratorKind.FirstName, suggest(varchar("fname")))
        assertEquals(GeneratorKind.FirstName, suggest(varchar("firstName")))
        assertEquals(GeneratorKind.LastName, suggest(varchar("surname")))
        assertEquals(GeneratorKind.LastName, suggest(varchar("LastName")))
        assertEquals(GeneratorKind.FullName, suggest(varchar("name")))
        assertEquals(GeneratorKind.Username, suggest(varchar("username")))
        assertEquals(GeneratorKind.Url, suggest(varchar("website")))
        assertEquals(GeneratorKind.Url, suggest(varchar("url")))
        assertEquals(GeneratorKind.ImageUrl, suggest(varchar("avatar_url")))
        assertEquals(GeneratorKind.IpAddress, suggest(varchar("ip")))
        assertEquals(GeneratorKind.IpAddress, suggest(varchar("ip_address")))
        assertEquals(GeneratorKind.CountryCode, suggest(varchar("country_code")))
        assertEquals(GeneratorKind.CountryCode, suggest(varchar("country", size = 2)))
        assertEquals(GeneratorKind.Country, suggest(varchar("country")))
        assertEquals(GeneratorKind.CurrencyCode, suggest(varchar("currency")))
        assertEquals(GeneratorKind.Paragraph, suggest(column("description", "text", Types.VARCHAR)))
        assertEquals(GeneratorKind.Paragraph, suggest(column("bio", "text", Types.VARCHAR)))
        assertEquals(GeneratorKind.Title, suggest(varchar("title")))
        assertEquals(GeneratorKind.JobTitle, suggest(varchar("job_title")))
        assertEquals(GeneratorKind.Slug, suggest(varchar("slug")))
        assertEquals(GeneratorKind.Status, suggest(varchar("status")))
        assertEquals(GeneratorKind.Company, suggest(varchar("company_name")))
        assertEquals(GeneratorKind.Street, suggest(varchar("address_line1")))
        assertEquals(GeneratorKind.City, suggest(varchar("city")))
        assertEquals(GeneratorKind.Uuid, suggest(varchar("guid")))
        assertEquals(GeneratorKind.Hash, suggest(varchar("password_hash")))
        // SQLite keeps times as text.
        assertEquals(GeneratorKind.PastTimestamp, suggest(column("created_at", "TEXT")))
    }

    @Test
    fun `suggests from types, and never text for numbers`() {
        assertEquals(GeneratorKind.Uuid, suggest(column("uuid", "uuid")))
        assertEquals(GeneratorKind.Uuid, suggest(column("external_ref", "uuid")))
        assertEquals(GeneratorKind.PastTimestamp, suggest(column("created_at", "timestamptz", Types.TIMESTAMP)))
        assertEquals(GeneratorKind.PastTimestamp, suggest(column("updatedAt", "datetime", Types.TIMESTAMP)))
        assertEquals(GeneratorKind.FutureTimestamp, suggest(column("expires_at", "timestamp", Types.TIMESTAMP)))
        assertEquals(GeneratorKind.Timestamp, suggest(column("happened", "timestamp", Types.TIMESTAMP)))
        assertEquals(GeneratorKind.BirthDate, suggest(column("date_of_birth", "date", Types.DATE)))
        assertEquals(GeneratorKind.Date, suggest(column("start", "date", Types.DATE)))
        assertEquals(GeneratorKind.Time, suggest(column("opens", "time", Types.TIME)))
        assertEquals(GeneratorKind.Boolean, suggest(column("is_active", "bool", Types.BOOLEAN)))
        assertEquals(GeneratorKind.Boolean, suggest(column("is_active", "TINYINT", Types.BIT, size = 1)))
        assertEquals(GeneratorKind.Boolean, suggest(int("is_active", "INTEGER")))
        assertEquals(GeneratorKind.Json, suggest(column("data", "jsonb")))
        assertEquals(GeneratorKind.Price, suggest(column("price", "numeric(10,2)", Types.NUMERIC)))
        assertEquals(GeneratorKind.Price, suggest(int("amount")))
        assertEquals(GeneratorKind.Integer, suggest(int("age")))
        assertEquals(GeneratorKind.Decimal, suggest(column("weight", "double precision", Types.DOUBLE)))
        assertEquals(GeneratorKind.Skip, suggest(column("id", "int4", Types.INTEGER, primaryKey = true, autoIncrement = true)))
        assertEquals(GeneratorKind.Skip, suggest(column("id", "int8", Types.BIGINT, primaryKey = true, default = "nextval('users_id_seq'::regclass)")))
        assertEquals(GeneratorKind.Sequence, suggest(column("id", "int4", Types.INTEGER, primaryKey = true)))
        assertEquals(GeneratorKind.ForeignKey, suggest(int("team_id"), foreignKey = true))
        assertEquals(GeneratorKind.Skip, suggest(column("mood", "mood")))

        // Text-sounding names on numeric columns.
        for (name in listOf("email", "user_email", "phone_number", "first_name", "url", "status", "zip", "description", "country_code", "title", "uuid")) {
            for (type in listOf(int(name), int(name, "smallint", Types.SMALLINT), column(name, "numeric(12,2)", Types.NUMERIC))) {
                val plan = GeneratorSuggestion.suggest(type, foreignKey = false, uniqueIndexed = false)
                assertFalse(plan.kind.isText, "$name ${type.typeName} -> ${plan.kind}")
                assertTrue(plan.kind in GeneratorSuggestion.choices(type), "$name ${type.typeName} -> ${plan.kind}")
            }
        }
        assertTrue(GeneratorSuggestion.choices(int("n")).none { it.isText })
        assertTrue(GeneratorSuggestion.choices(varchar("s")).any { it.isNumeric })
        assertEquals(GeneratorKind.Sequence, suggest(int("code"), unique = true))
    }

    @Test
    fun `every suggestion is among the column's choices`() {
        val columns =
            listOf(
                varchar("email"), varchar("created_at"), int("age"), column("price", "decimal(8,2)", Types.DECIMAL),
                column("is_admin", "boolean", Types.BOOLEAN), column("born", "date", Types.DATE), column("x", "uuid"),
                column("ip", "inet"), column("blob", "bytea", Types.BINARY, nullable = true), column("meta", "json"),
            )
        for (c in columns) {
            val plan = GeneratorSuggestion.suggest(c, foreignKey = false, uniqueIndexed = false)
            assertTrue(plan.kind in GeneratorSuggestion.choices(c), "${c.name}: ${plan.kind}")
        }
    }

    @Test
    fun `the same seed gives the same rows, another seed different ones`() {
        val plans =
            listOf("id", "first_name", "last_name", "email", "price", "created_at", "note", "uuid").map { name ->
                val c = if (name == "id") int("id") else if (name == "price") column("price", "numeric(10,2)", Types.NUMERIC) else varchar(name, nullable = true)
                GeneratorSuggestion.suggest(c, false, name == "id")
            }
        val a = TestDataGenerator(plans, 42, now = now).rows(500).toList()
        val b = TestDataGenerator(plans, 42, now = now).rows(500).toList()
        val c = TestDataGenerator(plans, 43, now = now).rows(500).toList()
        assertEquals(a, b)
        assertNotEquals(a, c)
        // Iterating again starts over.
        val generator = TestDataGenerator(plans, 42, now = now)
        assertEquals(generator.rows(50).toList(), generator.rows(50).toList())
    }

    @Test
    fun `emails and user names follow the row's person`() {
        val rows =
            generate(
                ColumnPlan(varchar("first_name"), GeneratorKind.FirstName),
                ColumnPlan(varchar("last_name"), GeneratorKind.LastName),
                ColumnPlan(varchar("email"), GeneratorKind.Email),
                ColumnPlan(varchar("username"), GeneratorKind.Username),
                count = 200,
            )
        for ((first, last, email, username) in rows) {
            val f = Words.ascii(first!!)
            val l = Words.ascii(last!!)
            val local = email!!.substringBefore('@')
            assertTrue(local.contains(f) || local.contains(l), "$first $last -> $email")
            assertTrue(username!!.contains(f) || username.contains(l), "$first $last -> $username")
            assertTrue(Regex("""^[a-z0-9._]+@[a-z0-9.-]+\.[a-z]+$""").matches(email), email)
        }
        assertTrue(rows.any { Words.namePools[1].last.contains(it[1]) }, "some Vietnamese names")
    }

    @Test
    fun `text is cut to the column's size, emails keep their domain`() {
        val rows =
            generate(
                ColumnPlan(varchar("email", 18), GeneratorKind.Email, unique = true),
                ColumnPlan(varchar("bio", 20), GeneratorKind.Paragraph),
                ColumnPlan(varchar("code", 2), GeneratorKind.CountryCode),
                count = 2000,
            )
        for ((email, bio, code) in rows) {
            assertTrue(email!!.length <= 18, email)
            assertTrue(Regex("""^[a-z0-9._]+@[a-z0-9.-]+\.[a-z]+$""").matches(email), email)
            assertTrue(bio!!.length <= 20, bio)
            assertEquals(2, code!!.length)
        }
        assertEquals(2000, rows.map { it[0] }.toSet().size)
    }

    @Test
    fun `unique columns stay unique over many rows`() {
        val plans =
            listOf(
                ColumnPlan(varchar("email"), GeneratorKind.Email, unique = true),
                ColumnPlan(varchar("username", 12), GeneratorKind.Username, unique = true),
                ColumnPlan(varchar("first_name"), GeneratorKind.FirstName, unique = true),
                ColumnPlan(varchar("city"), GeneratorKind.City, unique = true),
                ColumnPlan(int("code", "smallint", Types.SMALLINT), GeneratorKind.Integer, unique = true, min = 1.0, max = 10_000.0),
                ColumnPlan(int("id"), GeneratorKind.Sequence, unique = true),
                ColumnPlan(column("price", "numeric(6,2)", Types.NUMERIC), GeneratorKind.Price, unique = true, min = 0.0, max = 1000.0),
                ColumnPlan(column("day", "date", Types.DATE), GeneratorKind.Date, unique = true),
                ColumnPlan(column("ref", "uuid"), GeneratorKind.Uuid, unique = true),
                ColumnPlan(varchar("ip"), GeneratorKind.IpAddress, unique = true),
            )
        val generator = TestDataGenerator(plans, 1, now = now)
        assertEquals(emptyList(), generator.problems(1400))
        val rows = generator.rows(10_000).take(1400).toList()
        for (i in plans.indices) assertEquals(rows.size, rows.map { it[i] }.toSet().size, plans[i].column.name)
        // Columns without a small range keep going.
        val wide = TestDataGenerator(plans.filter { it.column.name !in setOf("day", "price") }, 1, now = now).rows(10_000).toList()
        for (i in 0 until wide[0].size) assertEquals(10_000, wide.map { it[i] }.toSet().size)
        assertTrue(wide.all { it[1]!!.length <= 12 })
    }

    @Test
    fun `null ratio is roughly honoured, and only for nullable columns`() {
        val rows =
            generate(
                ColumnPlan(varchar("nickname", nullable = true), GeneratorKind.Word, nullRatio = 0.3),
                ColumnPlan(varchar("name"), GeneratorKind.FullName, nullRatio = 0.3),
                count = 10_000,
            )
        val nulls = rows.count { it[0] == null }
        assertTrue(nulls in 2700..3300, "$nulls")
        assertTrue(rows.none { it[1] == null })
    }

    @Test
    fun `foreign keys come from the referenced values only`() {
        val pool = listOf("10", "20", "30", "40")
        val rows = generate(ColumnPlan(int("team_id"), GeneratorKind.ForeignKey), count = 1000, pools = mapOf("team_id" to pool))
        assertTrue(rows.all { it[0] in pool })
        assertEquals(pool.toSet(), rows.map { it[0] }.toSet())
        val unique = TestDataGenerator(listOf(ColumnPlan(int("team_id"), GeneratorKind.ForeignKey, unique = true)), 3, mapOf("team_id" to pool), now)
        assertEquals(pool.toSet(), unique.rows(4).map { it[0] }.toSet())
    }

    @Test
    fun `reports problems before generating`() {
        val plans =
            listOf(
                ColumnPlan(int("team_id"), GeneratorKind.ForeignKey),
                ColumnPlan(varchar("code"), GeneratorKind.Status, unique = true),
                ColumnPlan(int("level", "smallint", Types.SMALLINT), GeneratorKind.Integer, unique = true, min = 1.0, max = 100.0),
                ColumnPlan(column("flag", "boolean", Types.BOOLEAN), GeneratorKind.Boolean, unique = true),
                ColumnPlan(int("small_id", "smallint", Types.SMALLINT), GeneratorKind.Sequence),
                ColumnPlan(varchar("name"), GeneratorKind.Null),
                ColumnPlan(varchar("kind"), GeneratorKind.Skip),
                ColumnPlan(int("age"), GeneratorKind.Email),
                ColumnPlan(varchar("tag", 2), GeneratorKind.Word, unique = true),
                ColumnPlan(varchar("fine", 2, nullable = true), GeneratorKind.Skip),
            )
        val problems = TestDataGenerator(plans, 1, now = now).problems(40_000)
        assertEquals(
            listOf(
                "team_id: no rows in the referenced table",
                "code: unique with only 8 possible values",
                "level: unique with only 100 possible values",
                "flag: unique with only 2 possible values",
                "small_id: 40000 rows run past 32767, the most this smallint column holds",
                "name: NOT NULL, so it can't always be NULL",
                "kind: NOT NULL with no default, so it needs a generator",
                "age: Email address values don't fit a int4 column",
                "tag: 2 characters are too few for 40000 unique values",
            ),
            problems,
        )
        assertEquals(listOf("team_id: no rows in the referenced table", "name: NOT NULL, so it can't always be NULL", "kind: NOT NULL with no default, so it needs a generator", "age: Email address values don't fit a int4 column"), TestDataGenerator(plans, 1, now = now).problems(2))
    }

    @Test
    fun `integers stay within their type`() {
        val rows =
            generate(
                ColumnPlan(int("a", "smallint", Types.SMALLINT), GeneratorKind.Integer, min = -1e9, max = 1e9),
                ColumnPlan(int("b", "tinyint", Types.TINYINT), GeneratorKind.Integer, min = 0.0, max = 1e6),
                ColumnPlan(int("c", "int2", Types.SMALLINT), GeneratorKind.Integer),
                ColumnPlan(column("d", "numeric(4,2)", Types.NUMERIC), GeneratorKind.Decimal, max = 1e6),
                ColumnPlan(column("e", "numeric", Types.NUMERIC), GeneratorKind.Price),
                count = 5000,
            )
        val a = rows.map { it[0]!!.toInt() }
        assertTrue(a.all { it in -32768..32767 })
        assertTrue(a.any { it > 1000 } && a.any { it < -1000 }, "uses the type's range")
        assertTrue(rows.all { it[1]!!.toInt() in 0..127 })
        assertTrue(rows.all { it[2]!!.toInt() in 0..1000 })
        assertTrue(rows.all { BigDecimal(it[3]) < BigDecimal(100) && BigDecimal(it[3]).scale() == 2 })
        assertTrue(rows.all { BigDecimal(it[4]).scale() == 2 && BigDecimal(it[4]) >= BigDecimal.ONE && BigDecimal(it[4]) <= BigDecimal(1000) })
    }

    @Test
    fun `formats bind and satisfy constraints in a real database`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("CREATE TABLE teams (id INTEGER PRIMARY KEY)")
                statement.execute("INSERT INTO teams (id) VALUES (1), (2), (3)")
                statement.execute(
                    """
                    CREATE TABLE people (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        email VARCHAR(30) NOT NULL UNIQUE CHECK (length(email) <= 30 AND email LIKE '%_@_%._%'),
                        full_name VARCHAR(40) CHECK (length(full_name) <= 40),
                        rank SMALLINT NOT NULL CHECK (rank BETWEEN -32768 AND 32767),
                        price NUMERIC(10,2) NOT NULL CHECK (price >= 0),
                        is_active BOOLEAN NOT NULL CHECK (is_active IN (0, 1)),
                        born DATE NOT NULL CHECK (born = date(born)),
                        created_at TIMESTAMP NOT NULL CHECK (created_at = datetime(created_at)),
                        opens TIME CHECK (opens = time(opens)),
                        ref UUID NOT NULL UNIQUE,
                        meta JSON CHECK (json_valid(meta)),
                        team_id INTEGER NOT NULL REFERENCES teams (id)
                    )
                    """.trimIndent(),
                )
            }
            val columns =
                listOf(
                    column("id", "INTEGER", Types.INTEGER, primaryKey = true, autoIncrement = true),
                    varchar("email", 30),
                    varchar("full_name", 40, nullable = true),
                    int("rank", "SMALLINT", Types.SMALLINT),
                    column("price", "NUMERIC(10,2)", Types.NUMERIC),
                    column("is_active", "BOOLEAN", Types.BOOLEAN),
                    column("born", "DATE", Types.DATE),
                    column("created_at", "TIMESTAMP", Types.TIMESTAMP),
                    column("opens", "TIME", Types.TIME, nullable = true),
                    column("ref", "UUID"),
                    column("meta", "JSON", nullable = true),
                    int("team_id", "INTEGER"),
                )
            val plans = columns.map { GeneratorSuggestion.suggest(it, foreignKey = it.name == "team_id", uniqueIndexed = it.name in setOf("email", "ref")) }
            assertEquals(
                listOf(
                    GeneratorKind.Skip, GeneratorKind.Email, GeneratorKind.FullName, GeneratorKind.Integer, GeneratorKind.Price,
                    GeneratorKind.Boolean, GeneratorKind.BirthDate, GeneratorKind.PastTimestamp, GeneratorKind.Time, GeneratorKind.Uuid,
                    GeneratorKind.Json, GeneratorKind.ForeignKey,
                ),
                plans.map { it.kind },
            )
            val generator = TestDataGenerator(plans, 99, mapOf("team_id" to listOf("1", "2", "3")), now)
            assertEquals(emptyList(), generator.problems(500))
            assertEquals(columns.drop(1), generator.columns)
            val sql = "INSERT INTO people (${generator.columns.joinToString { it.name }}) VALUES (${generator.columns.joinToString { "?" }})"
            connection.prepareStatement(sql).use { insert ->
                for (row in generator.rows(500)) {
                    row.forEachIndexed { i, value -> bind(insert, i + 1, generator.columns[i], value) }
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*), count(DISTINCT email), count(full_name), min(born), max(created_at) FROM people").use { rs ->
                    rs.next()
                    assertEquals(500, rs.getInt(1))
                    assertEquals(500, rs.getInt(2))
                    assertTrue(rs.getInt(3) in 400 until 500, "about a tenth NULL")
                    assertTrue(LocalDate.parse(rs.getString(4)) >= now.toLocalDate().minusYears(80))
                    assertTrue(rs.getString(5) <= "2026-10-30 00:00:00")
                }
            }
        }
    }

    /** Binds as a type-aware binder would: parsing each value with the JDBC type proves its format. */
    private fun bind(statement: java.sql.PreparedStatement, index: Int, column: ColumnInfo, value: String?) {
        if (value == null) return statement.setNull(index, column.jdbcType)
        when (column.jdbcType) {
            Types.INTEGER, Types.SMALLINT, Types.BIGINT -> statement.setLong(index, value.toLong())
            Types.NUMERIC -> statement.setBigDecimal(index, BigDecimal(value))
            Types.BOOLEAN -> statement.setInt(index, if (value.toBooleanStrict()) 1 else 0)
            Types.DATE -> statement.setString(index, java.sql.Date.valueOf(value).toString())
            Types.TIMESTAMP -> statement.setString(index, java.sql.Timestamp.valueOf(value).toString().removeSuffix(".0"))
            Types.TIME -> statement.setString(index, java.sql.Time.valueOf(value).toString())
            else ->
                when (column.typeName) {
                    "UUID" -> statement.setString(index, UUID.fromString(value).toString().also { assertEquals(value, it) })
                    "JSON" -> statement.setString(index, value.also { assertTrue(Json.parseToJsonElement(it) is JsonObject) })
                    else -> statement.setString(index, value)
                }
        }
    }

    @Test
    fun `generates a hundred thousand rows quickly`() {
        val plans =
            listOf(
                ColumnPlan(int("id"), GeneratorKind.Sequence),
                ColumnPlan(varchar("first_name"), GeneratorKind.FirstName),
                ColumnPlan(varchar("last_name"), GeneratorKind.LastName),
                ColumnPlan(varchar("email"), GeneratorKind.Email, unique = true),
                ColumnPlan(varchar("phone"), GeneratorKind.Phone),
                ColumnPlan(column("price", "numeric(10,2)", Types.NUMERIC), GeneratorKind.Price),
                ColumnPlan(column("created_at", "timestamp", Types.TIMESTAMP), GeneratorKind.PastTimestamp),
                ColumnPlan(varchar("status"), GeneratorKind.Status),
                ColumnPlan(column("ref", "uuid"), GeneratorKind.Uuid),
                ColumnPlan(varchar("bio", nullable = true), GeneratorKind.Sentence, nullRatio = 0.2),
            )
        val generator = TestDataGenerator(plans, 5, now = now)
        generator.rows(10_000).count() // warm up
        val started = System.nanoTime()
        assertEquals(100_000, generator.rows(100_000).count())
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 2000, "took $millis ms")
        println("100k rows in $millis ms")
    }
}
