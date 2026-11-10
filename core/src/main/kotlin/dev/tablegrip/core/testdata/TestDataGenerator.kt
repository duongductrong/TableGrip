package dev.tablegrip.core.testdata

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.filter.ValueKind
import dev.tablegrip.core.metadata.ColumnInfo
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln

/**
 * Generates fake rows for [plans]. Columns planned as [GeneratorKind.Skip] are left out of [columns] and of each row.
 * [foreignKeyValues] maps a column name to existing key values of the referenced table, as strings, to pick from.
 *
 * Output is the same for the same plans, [seed] and [now]: each column draws from its own random stream (keyed by its
 * name, so adding a column doesn't change the others), and relative dates count from [now], which defaults to the start
 * of today. Values are strings a `PreparedStatement` binds for the column's type: plain numbers, `true`/`false`,
 * `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss`, `HH:mm:ss`, canonical UUIDs and compact JSON; NULL is null.
 */
class TestDataGenerator(
    plans: List<ColumnPlan>,
    private val seed: Long,
    foreignKeyValues: Map<String, List<String>> = emptyMap(),
    private val now: LocalDateTime = LocalDate.now().atStartOfDay(),
) {
    private val allPlans = plans
    private val plans = plans.filter { it.kind != GeneratorKind.Skip }
    private val pools = foreignKeyValues.mapValues { (_, values) -> values.distinct() }

    val columns: List<ColumnInfo> = this.plans.map { it.column }

    /**
     * [count] rows, generated as they're read; each call starts over and yields the same rows. Throws
     * [IllegalStateException] when a unique column runs out of values, or a foreign key has nothing to reference:
     * [problems] reports those up front.
     */
    fun rows(count: Int): Sequence<List<String?>> =
        sequence {
            val generators = plans.map { generator(it) }
            val row = Row(SplittableRandom(seed xor PersonSalt))
            for (index in 0 until count) {
                row.person = null
                val values = ArrayList<String?>(generators.size)
                for (generator in generators) values += generator.next(row, index)
                yield(values)
            }
        }

    /** What would stop [count] rows from generating or inserting, e.g. "team_id: no rows in the referenced table". */
    fun problems(count: Int): List<String> {
        val problems = mutableListOf<String>()
        for (plan in allPlans) {
            val column = plan.column
            val name = column.name
            val type = ColumnType(column)
            when (plan.kind) {
                GeneratorKind.Skip ->
                    if (!column.nullable && column.defaultValue == null && !column.autoIncrement) {
                        problems += tr("{0}: NOT NULL with no default, so it needs a generator", name)
                    }
                GeneratorKind.Null -> if (!column.nullable) problems += tr("{0}: NOT NULL, so it can't always be NULL", name)
                GeneratorKind.Constant -> {
                    if (plan.constant == null && !column.nullable) problems += tr("{0}: NOT NULL, so the fixed value can't be NULL", name)
                    if (plan.unique && count > 1) problems += tr("{0}: unique, but a fixed value repeats on every row", name)
                }
                GeneratorKind.ForeignKey ->
                    if (pools[name].isNullOrEmpty() && (!column.nullable || plan.nullRatio < 1.0)) {
                        problems += tr("{0}: no rows in the referenced table", name)
                    }
                else -> {}
            }
            if (plan.kind == GeneratorKind.Skip || plan.kind == GeneratorKind.Null) continue
            if (plan.kind !in GeneratorSuggestion.choices(column, foreignKey = true)) {
                problems += tr("{0}: {1} values don't fit a {2} column", name, plan.kind.label, column.typeName)
                continue
            }
            if (plan.min != null && plan.max != null && plan.min > plan.max) problems += tr("{0}: the minimum is above the maximum", name)
            val generator = generator(plan)
            if (generator is SequenceGenerator && generator.start + count - 1 > generator.last) {
                problems += tr("{0}: {1} rows run past {2}, the most this {3} column holds", name, count, generator.last, column.typeName)
            }
            if (!plan.unique || count <= 1) continue
            val space = generator.space
            when {
                space != null && space < count -> problems += tr("{0}: unique with only {1} possible values", name, space)
                generator is TextGenerator && type.textLimit != null && ColumnType.pow10(type.textLimit) < count ->
                    problems += tr("{0}: {1} characters are too few for {2} unique values", name, type.textLimit, count)
            }
        }
        return problems
    }

    // region Generators

    /** One row being built: the person its name, user name and email columns all describe, drawn when first needed. */
    private class Row(private val random: SplittableRandom) {
        var person: Person? = null

        fun person(): Person = person ?: Person.draw(random).also { person = it }
    }

    private class Person(val first: String, val middle: String?, val last: String, val familyFirst: Boolean) {
        val firstAscii = Words.ascii(first)
        val lastAscii = Words.ascii(last)

        val fullName: String
            get() = if (familyFirst) listOfNotNull(last, middle, first).joinToString(" ") else "$first $last"

        companion object {
            private val totalWeight = Words.namePools.sumOf { it.weight }

            fun draw(random: SplittableRandom): Person {
                var pick = random.nextInt(totalWeight)
                val pool = Words.namePools.first { pick -= it.weight; pick < 0 }
                val middle = if (pool.middle.isEmpty()) null else pool.middle.pick(random)
                return Person(pool.first.pick(random), middle, pool.last.pick(random), pool.familyFirst)
            }
        }
    }

    private abstract inner class Generator(val plan: ColumnPlan) {
        val random = SplittableRandom(mix(seed, plan.column.name))
        val type = ColumnType(plan.column)

        /** How many distinct values this can produce, or null when practically unlimited. */
        open val space: Long? = null

        private val nullable = plan.column.nullable && plan.nullRatio > 0.0

        fun next(row: Row, index: Int): String? = if (nullable && random.nextDouble() < plan.nullRatio) null else value(row, index)

        abstract fun value(row: Row, index: Int): String?

        /** Cuts a value to the column's length. */
        fun fit(value: String): String = type.textLimit?.let { if (value.length > it) value.take(it) else value } ?: value
    }

    private inner class ConstantGenerator(plan: ColumnPlan) : Generator(plan) {
        override val space = 1L

        override fun value(row: Row, index: Int) = plan.constant
    }

    private inner class SequenceGenerator(plan: ColumnPlan) : Generator(plan) {
        val start = plan.min?.toLong() ?: 1L
        val last: Long =
            type.integerBounds?.last
                ?: type.maxScaled?.let { it / ColumnType.pow10(type.scale) }
                ?: type.textLimit?.let { ColumnType.pow10(it) - 1 }
                ?: Long.MAX_VALUE

        override fun value(row: Row, index: Int): String = (start + index).toString()
    }

    /**
     * Values drawn as whole numbers in [first]..[last] and then [format]ted: numbers (scaled to whole units), days,
     * seconds, IP addresses and indexes into a word list all share one way of staying unique.
     */
    private inner class RangeGenerator(
        plan: ColumnPlan,
        private val first: Long,
        private val last: Long,
        private val format: (Long) -> String,
        private val draw: (SplittableRandom) -> Long = { it.nextLong(first, last + 1) },
    ) : Generator(plan) {
        override val space = last - first + 1
        private val used = if (plan.unique) HashSet<Long>() else null

        override fun value(row: Row, index: Int): String = fit(format(if (used == null) draw(random) else claim()))

        private fun claim(): Long {
            val used = used!!
            var value = draw(random)
            if (used.add(value)) return value
            repeat(8) {
                value = draw(random)
                if (used.add(value)) return value
            }
            // Random draws keep colliding as the range fills up; walk to the next free value instead.
            check(used.size < space) { tr("{0}: unique with only {1} possible values", plan.column.name, space) }
            while (true) {
                value = if (value >= last) first else value + 1
                if (used.add(value)) return value
            }
        }
    }

    /**
     * Open-ended text. A duplicate under [ColumnPlan.unique] is redrawn a few times, then given a numeric suffix placed
     * by [split]: `minh.nguyen42@gmail.com`, `report_2.pdf`, `Lotus Tech 7`.
     */
    private inner class TextGenerator(
        plan: ColumnPlan,
        private val separator: String,
        private val produce: (Row) -> String,
    ) : Generator(plan) {
        private val used = if (plan.unique) HashSet<String>() else null
        private var suffix = 2

        override fun value(row: Row, index: Int): String {
            var value = fitText(produce(row), "")
            val used = used ?: return value
            if (used.add(value)) return value
            repeat(3) {
                value = fitText(produce(row), "")
                if (used.add(value)) return value
            }
            while (true) {
                val candidate = fitText(value, separator + suffix++)
                if (used.add(candidate)) return candidate
            }
        }

        /** [value] with [extra] inserted where a suffix belongs, shortened from its head to fit the column. */
        private fun fitText(value: String, extra: String): String {
            val (head, tail) = split(value)
            val limit = type.textLimit ?: return head + extra + tail
            check(extra.length < limit) { tr("{0}: {1} characters are too few for more unique values", plan.column.name, type.textLimit) }
            if (head.length + extra.length + tail.length <= limit) return head + extra + tail
            val room = limit - extra.length - tail.length
            // An email keeps its domain, a file name its extension; when even that doesn't fit, the value is just cut.
            return if (room >= 1) head.take(room).trimEnd() + extra + tail else value.take(limit - extra.length) + extra
        }

        private fun split(value: String): Pair<String, String> {
            val at =
                when (plan.kind) {
                    GeneratorKind.Email -> value.lastIndexOf('@')
                    GeneratorKind.FileName, GeneratorKind.ImageUrl -> value.lastIndexOf('.')
                    GeneratorKind.Domain -> value.indexOf('.')
                    else -> -1
                }
            return if (at > 0) value.substring(0, at) to value.substring(at) else value to ""
        }
    }

    private inner class UuidGenerator(plan: ColumnPlan) : Generator(plan) {
        override fun value(row: Row, index: Int): String = fit(uuid(random))
    }

    private inner class JsonGenerator(plan: ColumnPlan) : Generator(plan) {
        override fun value(row: Row, index: Int): String = fit(json(random))
    }

    private fun generator(plan: ColumnPlan): Generator {
        val type = ColumnType(plan.column)
        fun words(list: List<String>, draw: (SplittableRandom) -> Long = { it.nextInt(list.size).toLong() }): Generator =
            RangeGenerator(plan, 0L, list.size - 1L, { list[it.toInt()] }, draw)

        return when (plan.kind) {
            GeneratorKind.Skip, GeneratorKind.Null -> ConstantGenerator(plan.copy(constant = null))
            GeneratorKind.Constant -> ConstantGenerator(plan)
            GeneratorKind.ForeignKey -> {
                val pool = pools[plan.column.name].orEmpty()
                if (pool.isEmpty()) {
                    object : Generator(plan) {
                        override val space = 0L
                        override fun value(row: Row, index: Int): String = error(tr("{0}: no rows in the referenced table", plan.column.name))
                    }
                } else {
                    words(pool)
                }
            }
            GeneratorKind.Sequence -> SequenceGenerator(plan)
            GeneratorKind.Integer -> number(plan, type, scale = 0, defaultMin = 0.0, defaultMax = 1000.0)
            GeneratorKind.Decimal -> number(plan, type, type.scale, defaultMin = 0.0, defaultMax = 10_000.0)
            GeneratorKind.Price -> number(plan, type, if (type.isInteger) 0 else maxOf(type.scale, 2).coerceAtMost(ColumnType.MaxScale), 1.0, 1000.0, price = true)
            GeneratorKind.Boolean -> {
                val (yes, no) = if (type.kind == ValueKind.Number) "1" to "0" else "true" to "false"
                RangeGenerator(plan, 0, 1, { if (it == 1L) yes else no })
            }
            GeneratorKind.Uuid -> UuidGenerator(plan)
            GeneratorKind.Json -> JsonGenerator(plan)

            GeneratorKind.FirstName -> streamText(plan, "") { it.person().first }
            GeneratorKind.LastName -> streamText(plan, "") { it.person().last }
            GeneratorKind.FullName -> streamText(plan, " ") { it.person().fullName }
            GeneratorKind.Username -> streamText(plan, "") { row -> username(row.person(), this) }
            GeneratorKind.Email -> streamText(plan, "") { row -> "${emailLocal(row.person(), this)}@${Words.emailDomains.pick(this)}" }
            GeneratorKind.Phone -> streamText(plan, "") { pattern(Words.phoneFormats.pick(this), this) }
            GeneratorKind.Company -> streamText(plan, " ") { company(this) }
            GeneratorKind.JobTitle -> streamText(plan, " ") { Words.jobTitles.pick(this) }
            GeneratorKind.Street -> streamText(plan, " ") { street(this) }
            GeneratorKind.City -> streamText(plan, " ") { Words.cities.pick(this) }
            GeneratorKind.PostalCode -> streamText(plan, "") { pattern(Words.postalFormats.pick(this), this) }
            GeneratorKind.Url -> streamText(plan, "-") { "https://${domain(this)}" + if (nextBoolean()) "/${Words.nouns.pick(this)}" else "" }
            GeneratorKind.ImageUrl -> streamText(plan, "-") { "https://cdn.example.com/images/${Words.nouns.pick(this)}-${hex(this, 8)}.jpg" }
            GeneratorKind.Domain -> streamText(plan, "") { domain(this) }
            GeneratorKind.Word -> streamText(plan, "") { (if (nextInt(3) == 0) Words.adjectives else Words.nouns).pick(this) }
            GeneratorKind.Title -> streamText(plan, " ") { title(this) }
            GeneratorKind.Sentence -> streamText(plan, " ") { sentence(this) }
            GeneratorKind.Paragraph -> streamText(plan, " ") { (1..3 + nextInt(4)).joinToString(" ") { sentence(this) } }
            GeneratorKind.Slug -> streamText(plan, "-") { (1..2 + nextInt(3)).joinToString("-") { (if (it == 1) Words.adjectives else Words.nouns).pick(this) } }
            GeneratorKind.Code -> streamText(plan, "") { pattern("AAA-####", this) }
            GeneratorKind.Hash -> streamText(plan, "") { hex(this, 64) }
            GeneratorKind.FileName -> streamText(plan, "_") { "${Words.nouns.pick(this)}_${Words.adjectives.pick(this)}.${Words.fileExtensions.pick(this)}" }
            GeneratorKind.ProductName -> streamText(plan, " ") { product(this) }

            GeneratorKind.IpAddress -> RangeGenerator(plan, 0x0B000001L, 0xDFFFFFFEL, { ip -> "${ip shr 24}.${ip shr 16 and 255}.${ip shr 8 and 255}.${ip and 255}" })
            GeneratorKind.HexColor -> RangeGenerator(plan, 0L, 0xFFFFFFL, { "#" + it.toString(16).padStart(6, '0') })
            GeneratorKind.Status -> words(Words.statuses) { random -> if (random.nextInt(100) < 55) 0L else random.nextInt(Words.statuses.size).toLong() }
            GeneratorKind.Color -> words(Words.colors)
            GeneratorKind.Region -> words(Words.regions)
            GeneratorKind.Country -> words(Words.countries.map { it.first })
            GeneratorKind.CountryCode -> words(Words.countries.map { it.second })
            GeneratorKind.CurrencyCode -> words(Words.currencies)
            GeneratorKind.LanguageCode -> words(Words.languages)

            GeneratorKind.Date, GeneratorKind.PastDate, GeneratorKind.FutureDate, GeneratorKind.BirthDate -> {
                val today = now.toLocalDate()
                val (from, to) =
                    when (plan.kind) {
                        GeneratorKind.PastDate -> today.minusYears(1) to today
                        GeneratorKind.FutureDate -> today to today.plusYears(1)
                        GeneratorKind.BirthDate -> today.minusYears(80) to today.minusYears(18)
                        else -> today.minusYears(3) to today.plusYears(1)
                    }
                val withTime = type.kind == ValueKind.DateTime
                RangeGenerator(plan, from.toEpochDay(), to.toEpochDay(), { day -> LocalDate.ofEpochDay(day).toString() + if (withTime) " 00:00:00" else "" })
            }
            GeneratorKind.Timestamp, GeneratorKind.PastTimestamp, GeneratorKind.FutureTimestamp -> {
                val (from, to) =
                    when (plan.kind) {
                        GeneratorKind.PastTimestamp -> now.minusYears(1) to now
                        GeneratorKind.FutureTimestamp -> now to now.plusYears(1)
                        else -> now.minusYears(3) to now.plusYears(1)
                    }
                val dateOnly = type.kind == ValueKind.Date
                RangeGenerator(plan, from.toEpochSecond(ZoneOffset.UTC), to.toEpochSecond(ZoneOffset.UTC), { second -> timestamp(second, dateOnly) })
            }
            GeneratorKind.Time -> RangeGenerator(plan, 0L, 86_399L, { time(it) })
        }
    }

    /** Text drawn from the column's own random stream. */
    private fun streamText(plan: ColumnPlan, separator: String, produce: SplittableRandom.(Row) -> String): Generator {
        lateinit var generator: TextGenerator
        generator = TextGenerator(plan, separator) { row -> generator.random.produce(row) }
        return generator
    }

    /** A number in whole units of 10^-[scale], within the plan's range (or the default one) and what the column holds. */
    private fun number(plan: ColumnPlan, type: ColumnType, scale: Int, defaultMin: Double, defaultMax: Double, price: Boolean = false): Generator {
        val unit = ColumnType.pow10(scale)
        val bounds =
            type.integerBounds?.let { (it.first * unit).coerceAtLeast(-ColumnType.Big)..(it.last * unit).coerceAtMost(ColumnType.Big) }
                ?: type.maxScaled?.let { max -> (max / ColumnType.pow10(type.scale - scale)).let { -it..it } }
                ?: -ColumnType.Big..ColumnType.Big
        fun scaled(value: Double, mode: RoundingMode): Long =
            BigDecimal.valueOf(value).movePointRight(scale).setScale(0, mode)
                .coerceIn(BigDecimal.valueOf(bounds.first), BigDecimal.valueOf(bounds.last)).toLong()
        val first = scaled(plan.min ?: defaultMin, RoundingMode.CEILING)
        val last = scaled(plan.max ?: defaultMax, RoundingMode.FLOOR).coerceAtLeast(first)
        val format = { value: Long -> decimal(value, scale) }
        if (!price) return RangeGenerator(plan, first, last, format)
        val endings = longArrayOf(99, 0, 49, 95, 50)
        return RangeGenerator(plan, first, last, format) { random ->
            // Log-uniform, so cheap items outnumber expensive ones; cents end the way shop prices do.
            val low = ln(maxOf(first.toDouble() / unit, 0.0) + 1)
            val high = ln(maxOf(last.toDouble() / unit, 0.0) + 1)
            val whole = (exp(low + random.nextDouble() * (high - low)) - 1).toLong()
            val cents = if (random.nextInt(4) == 0) random.nextLong(100) else endings[random.nextInt(endings.size)]
            val fraction = when {
                scale >= 2 -> cents * ColumnType.pow10(scale - 2)
                scale == 1 -> cents / 10
                else -> 0
            }
            (whole * unit + fraction).coerceIn(first, last)
        }
    }

    // endregion

    // region Values

    private fun username(person: Person, random: SplittableRandom): String {
        val first = person.firstAscii
        val last = person.lastAscii
        return when (random.nextInt(6)) {
            0 -> "$first.$last"
            1 -> "$first$last"
            2 -> "${first.take(1)}$last"
            3 -> "${first}_$last"
            4 -> "$first${random.nextInt(10, 10_000)}"
            else -> "$first${last.take(1)}${random.nextInt(10, 100)}"
        }
    }

    private fun emailLocal(person: Person, random: SplittableRandom): String {
        val first = person.firstAscii
        val last = person.lastAscii
        return when (random.nextInt(7)) {
            0, 1 -> "$first.$last"
            2 -> "$first$last"
            3 -> "${first.take(1)}.$last"
            4 -> "${first}_$last"
            5 -> "$last.$first"
            else -> "$first${random.nextInt(10, 100)}"
        }
    }

    private fun company(random: SplittableRandom): String {
        val root = Words.companyRoots.pick(random)
        val industry = if (random.nextInt(5) == 0) "" else " " + Words.companyIndustries.pick(random)
        return root + industry + Words.companySuffixes.pick(random)
    }

    private fun domain(random: SplittableRandom): String {
        val root = Words.ascii(Words.companyRoots.pick(random))
        val industry = if (random.nextBoolean()) Words.companyIndustries.pick(random).lowercase() else ""
        return "$root$industry.${Words.topLevelDomains.pick(random)}"
    }

    private fun street(random: SplittableRandom): String =
        if (random.nextInt(10) < 7) {
            "${random.nextInt(1, 10_000)} ${Words.streetNames.pick(random)} ${Words.streetTypes.pick(random)}"
        } else {
            "${random.nextInt(1, 300)} ${Words.vietnameseStreets.pick(random)}"
        }

    private fun title(random: SplittableRandom): String =
        (1..2 + random.nextInt(4)).joinToString(" ") { i ->
            (if (i % 2 == 1) Words.adjectives else Words.nouns).pick(random).replaceFirstChar(Char::uppercaseChar)
        }

    private fun sentence(random: SplittableRandom): String =
        (1..6 + random.nextInt(9)).joinToString(" ", postfix = ".") { Words.lorem.pick(random) }.replaceFirstChar(Char::uppercaseChar)

    private fun product(random: SplittableRandom): String {
        val material = if (random.nextBoolean()) " " + Words.productMaterials.pick(random) else ""
        return Words.productAdjectives.pick(random) + material + " " + Words.products.pick(random)
    }

    private fun json(random: SplittableRandom): String =
        when (random.nextInt(5)) {
            0 -> """{"id":${random.nextInt(1, 100_000)},"label":${quote(Words.nouns.pick(random))},"active":${random.nextBoolean()}}"""
            1 -> {
                val person = Person.draw(random)
                """{"name":${quote(person.fullName)},"email":${quote("${person.firstAscii}.${person.lastAscii}@${Words.emailDomains.pick(random)}")},"age":${random.nextInt(18, 81)}}"""
            }
            2 -> {
                val tags = (1..1 + random.nextInt(3)).joinToString(",") { quote(Words.adjectives.pick(random)) }
                """{"sku":${quote(pattern("AAA-####", random))},"price":${decimal(random.nextLong(100, 100_000), 2)},"tags":[$tags]}"""
            }
            3 -> """{"theme":${quote(if (random.nextBoolean()) "light" else "dark")},"notifications":${random.nextBoolean()},"language":${quote(Words.languages.pick(random))}}"""
            else -> """{"city":${quote(Words.cities.pick(random))},"lat":${decimal(random.nextLong(-90_000_000, 90_000_001), 6)},"lng":${decimal(random.nextLong(-180_000_000, 180_000_001), 6)}}"""
        }

    // endregion

    private companion object {
        const val PersonSalt = 0x5DEECE66DL

        fun mix(seed: Long, name: String): Long {
            // SplitMix64's finalizer: neighbouring seeds and similar names still give unrelated streams.
            var z = seed + name.hashCode() * -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun uuid(random: SplittableRandom): String {
            // Version 4, variant 1, as UUID.randomUUID makes them, but from the seeded stream.
            val high = random.nextLong() and -0xf001L or 0x4000L
            val low = random.nextLong() and 0x3fffffffffffffffL or Long.MIN_VALUE
            return java.util.UUID(high, low).toString()
        }

        fun hex(random: SplittableRandom, length: Int): String {
            val chars = CharArray(length) { HexDigits[random.nextInt(16)] }
            return String(chars)
        }

        /** Fills `#` with digits and `A` with upper-case letters. */
        fun pattern(format: String, random: SplittableRandom): String {
            val chars = CharArray(format.length) { i ->
                when (val c = format[i]) {
                    '#' -> '0' + random.nextInt(10)
                    'A' -> 'A' + random.nextInt(26)
                    else -> c
                }
            }
            return String(chars)
        }

        fun decimal(value: Long, scale: Int): String {
            if (scale == 0) return value.toString()
            val unit = ColumnType.pow10(scale)
            val magnitude = if (value < 0) -value else value
            val fraction = (magnitude % unit).toString().padStart(scale, '0')
            return (if (value < 0) "-" else "") + (magnitude / unit) + "." + fraction
        }

        fun timestamp(epochSecond: Long, dateOnly: Boolean): String {
            val day = Math.floorDiv(epochSecond, 86_400L)
            val date = LocalDate.ofEpochDay(day).toString()
            return if (dateOnly) date else date + " " + time(epochSecond - day * 86_400L)
        }

        fun time(secondOfDay: Long): String {
            val hours = secondOfDay / 3600
            val minutes = secondOfDay / 60 % 60
            val seconds = secondOfDay % 60
            return buildString(8) {
                if (hours < 10) append('0')
                append(hours).append(':')
                if (minutes < 10) append('0')
                append(minutes).append(':')
                if (seconds < 10) append('0')
                append(seconds)
            }
        }

        fun quote(text: String): String =
            buildString(text.length + 2) {
                append('"')
                for (c in text) {
                    when {
                        c == '"' || c == '\\' -> append('\\').append(c)
                        c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                        else -> append(c)
                    }
                }
                append('"')
            }

        private val HexDigits = "0123456789abcdef".toCharArray()
    }
}

private fun <T> List<T>.pick(random: SplittableRandom): T = this[random.nextInt(size)]
