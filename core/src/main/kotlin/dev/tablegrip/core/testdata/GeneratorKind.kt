package dev.tablegrip.core.testdata

import dev.tablegrip.core.filter.ValueKind
import dev.tablegrip.core.metadata.ColumnInfo
import java.sql.Types

/** What kind of value a column is filled with when generating test data. [label] is what the column's dropdown shows. */
enum class GeneratorKind(val label: String) {
    // Numbers and identity
    Sequence("Sequence (1, 2, 3…)"),
    Integer("Random integer"),
    Decimal("Random decimal"),
    Price("Price"),
    Boolean("Boolean"),
    Uuid("UUID"),

    // Text
    FirstName("First name"),
    LastName("Last name"),
    FullName("Full name"),
    Username("Username"),
    Email("Email address"),
    Phone("Phone number"),
    Company("Company name"),
    JobTitle("Job title"),
    Street("Street address"),
    City("City"),
    Region("State / province"),
    PostalCode("Postal code"),
    Country("Country"),
    CountryCode("Country code (VN, US…)"),
    Url("URL"),
    ImageUrl("Image URL"),
    Domain("Domain name"),
    IpAddress("IPv4 address"),
    Word("Word"),
    Title("Title (a few words)"),
    Sentence("Sentence"),
    Paragraph("Paragraph"),
    Slug("Slug (some-words-here)"),
    Code("Code (ABC-1234)"),
    Hash("Hex hash"),
    FileName("File name"),
    ProductName("Product name"),
    Color("Color name"),
    HexColor("Hex color (#1a2b3c)"),
    CurrencyCode("Currency code (USD, VND…)"),
    LanguageCode("Language code (en, vi…)"),
    Status("Status (active, pending…)"),

    // Time
    Date("Date"),
    PastDate("Date in the past year"),
    FutureDate("Date in the next year"),
    BirthDate("Date of birth (18–80 years ago)"),
    Timestamp("Timestamp"),
    PastTimestamp("Timestamp in the past year"),
    FutureTimestamp("Timestamp in the next year"),
    Time("Time of day"),

    // Structured
    Json("JSON object"),

    // Special
    ForeignKey("Existing value from referenced table"),
    Constant("Fixed value"),
    Null("Always NULL"),
    Skip("Leave to the database default");

    /** Produces words rather than numbers, times or identifiers. */
    val isText: kotlin.Boolean
        get() = this in TextKinds

    val isTemporal: kotlin.Boolean
        get() = this in TemporalKinds

    val isNumeric: kotlin.Boolean
        get() = this in NumericKinds

    companion object {
        internal val NumericKinds = setOf(Sequence, Integer, Decimal, Price)
        internal val TemporalKinds = setOf(Date, PastDate, FutureDate, BirthDate, Timestamp, PastTimestamp, FutureTimestamp, Time)
        internal val TextKinds =
            listOf(
                FirstName, LastName, FullName, Username, Email, Phone, Company, JobTitle, Street, City, Region, PostalCode, Country,
                CountryCode, Url, ImageUrl, Domain, IpAddress, Word, Title, Sentence, Paragraph, Slug, Code, Hash, FileName,
                ProductName, Color, HexColor, CurrencyCode, LanguageCode, Status,
            )
    }
}

/**
 * How to fill one column. [nullRatio] (0..1) applies only to nullable columns. [unique] retries and derives values to
 * avoid duplicates. [constant] is the value for [GeneratorKind.Constant] (null inserts NULL). [min] and [max] override
 * the range of numeric generators, and are clamped to what the column's type can hold.
 */
data class ColumnPlan(
    val column: ColumnInfo,
    val kind: GeneratorKind,
    val nullRatio: Double = 0.0,
    val unique: Boolean = false,
    val constant: String? = null,
    val min: Double? = null,
    val max: Double? = null,
)

/** What a column's declared type allows: integer bounds, decimal places, text length. */
internal class ColumnType(val column: ColumnInfo) {
    val kind: ValueKind = ValueKind.of(column.typeName, column.jdbcType, column.size)

    private val name = column.typeName.lowercase().substringBefore('(').trim().removeSuffix(" unsigned").removeSuffix(" zerofill").trim()

    // `numeric(10,2)` in the type name, for drivers that report it there rather than in size and scale.
    private val declared = Regex("""\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)""").find(column.typeName)
    private val precision: Int? = declared?.groupValues?.get(1)?.toIntOrNull() ?: column.size?.takeIf { name in ExactNames && it in 1..1000 }
    private val declaredScale: Int? = column.scale ?: declared?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt()
        ?: if (declared != null && name in ExactNames) 0 else null

    /** The range of a whole-number column, or null when it holds fractions (or isn't numeric). */
    val integerBounds: LongRange? =
        when {
            kind != ValueKind.Number -> null
            name == "tinyint" || name == "int1" -> -128L..127L
            name == "smallint" || name == "int2" || name.startsWith("smallserial") || name == "serial2" -> -32768L..32767L
            name == "mediumint" || name == "int3" -> -8388608L..8388607L
            name in IntNames || name == "serial" || name == "serial4" -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            name == "bigint" || name == "int8" || name.startsWith("bigserial") || name == "serial8" || name == "hugeint" -> -Big..Big
            name == "year" -> 1901L..2155L
            name in ExactNames && declaredScale == 0 && precision != null -> pow10(precision).let { -(it - 1)..(it - 1) }
            name in ExactNames || name.startsWith("float") || name.startsWith("double") || name == "real" || name == "money" -> null
            else ->
                when (column.jdbcType) {
                    Types.TINYINT -> -128L..127L
                    Types.SMALLINT -> -32768L..32767L
                    Types.INTEGER -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                    Types.BIGINT -> -Big..Big
                    else -> null
                }
        }

    val isInteger: kotlin.Boolean
        get() = integerBounds != null

    /** Decimal places generated for fractional numbers; exact numerics of unknown scale are assumed to have 2. */
    val scale: Int = if (isInteger) 0 else minOf(declaredScale ?: 2, MaxScale)

    /** The largest magnitude a fractional column holds, in units of 10^-[scale], from its precision; null if unbounded. */
    val maxScaled: Long? =
        if (kind == ValueKind.Number && !isInteger && name in ExactNames && precision != null) {
            // With the scale assumed rather than known, precision still caps the digits as a whole.
            pow10(precision - (declaredScale ?: 2) + scale).let { if (it >= Big) null else it - 1 }
        } else {
            null
        }

    /** The most characters the column takes, for sized text types. */
    val textLimit: Int? = column.size?.takeIf { kind == ValueKind.Text && it > 0 }

    companion object {
        /** A bound that keeps every range's size within a Long. */
        const val Big = 1L shl 61

        const val MaxScale = 9

        private val IntNames = setOf("int", "integer", "int4")
        private val ExactNames = setOf("numeric", "decimal", "dec", "number", "fixed")

        fun pow10(exponent: Int): Long {
            var result = 1L
            repeat(exponent.coerceAtLeast(0)) {
                if (result >= Big / 10) return Big
                result *= 10
            }
            return result
        }
    }
}

/** Picks a generator for a column from its name and type. */
object GeneratorSuggestion {
    /**
     * The best generator for [column] from its name (snake, camel and kebab case alike) and type. [foreignKey] is true
     * when the column is part of a foreign key, [uniqueIndexed] when a unique index or constraint covers it alone.
     * Auto-increment and identity keys are left to the database. Never offers text for a numeric column or vice versa.
     */
    fun suggest(column: ColumnInfo, foreignKey: kotlin.Boolean, uniqueIndexed: kotlin.Boolean): ColumnPlan {
        val type = ColumnType(column)
        val unique = uniqueIndexed || column.primaryKey
        val nullRatio = if (column.nullable && !unique) 0.1 else 0.0
        fun plan(kind: GeneratorKind, min: Double? = null, max: Double? = null) =
            ColumnPlan(column, kind, nullRatio = if (kind == GeneratorKind.Skip) 0.0 else nullRatio, unique = unique, min = min, max = max)

        if (column.autoIncrement || isGeneratedDefault(column.defaultValue)) return plan(GeneratorKind.Skip)
        if (column.primaryKey && column.defaultValue != null && type.kind == ValueKind.Uuid) return plan(GeneratorKind.Skip)
        if (foreignKey && type.kind != ValueKind.Binary) return plan(GeneratorKind.ForeignKey)

        val name = Name(column.name)
        return when (type.kind) {
            ValueKind.Number -> numeric(name, type, unique, ::plan)
            ValueKind.Boolean -> plan(GeneratorKind.Boolean)
            ValueKind.Date -> plan(temporal(name, dateOnly = true) ?: GeneratorKind.Date)
            ValueKind.DateTime -> plan(temporal(name, dateOnly = false) ?: GeneratorKind.Timestamp)
            ValueKind.Time -> plan(GeneratorKind.Time)
            ValueKind.Uuid -> plan(GeneratorKind.Uuid)
            ValueKind.Json -> plan(GeneratorKind.Json)
            ValueKind.Text -> text(name, type, unique, ::plan)
            ValueKind.Other ->
                when {
                    type.column.typeName.lowercase().let { it == "inet" || it == "cidr" } || name.has("ip") -> plan(GeneratorKind.IpAddress)
                    // Enums and other server types: no way to know their values, so leave them to the default or NULL.
                    else -> plan(GeneratorKind.Skip)
                }
            ValueKind.Binary -> plan(GeneratorKind.Skip)
        }
    }

    /** Kinds that make sense for [column]'s type, most fitting first, for the per-column dropdown. */
    fun choices(column: ColumnInfo, foreignKey: kotlin.Boolean = false): List<GeneratorKind> {
        val numbers = listOf(GeneratorKind.Sequence, GeneratorKind.Integer, GeneratorKind.Decimal, GeneratorKind.Price)
        val dates = listOf(GeneratorKind.Date, GeneratorKind.PastDate, GeneratorKind.FutureDate, GeneratorKind.BirthDate)
        val timestamps = listOf(GeneratorKind.Timestamp, GeneratorKind.PastTimestamp, GeneratorKind.FutureTimestamp)
        val generators =
            when (ColumnType(column).kind) {
                ValueKind.Number -> numbers + GeneratorKind.Boolean
                ValueKind.Boolean -> listOf(GeneratorKind.Boolean)
                ValueKind.Date -> dates
                ValueKind.DateTime -> timestamps + dates
                ValueKind.Time -> listOf(GeneratorKind.Time)
                ValueKind.Uuid -> listOf(GeneratorKind.Uuid)
                ValueKind.Json -> listOf(GeneratorKind.Json)
                ValueKind.Text, ValueKind.Other ->
                    GeneratorKind.TextKinds + GeneratorKind.Uuid + GeneratorKind.Json + numbers + GeneratorKind.Boolean + timestamps +
                        dates + GeneratorKind.Time
                ValueKind.Binary -> emptyList()
            }
        val special =
            listOfNotNull(
                GeneratorKind.ForeignKey.takeIf { foreignKey },
                GeneratorKind.Constant.takeIf { ColumnType(column).kind != ValueKind.Binary },
                GeneratorKind.Null.takeIf { column.nullable },
                GeneratorKind.Skip,
            )
        return generators + special
    }

    private fun isGeneratedDefault(default: String?): kotlin.Boolean {
        val text = default?.lowercase() ?: return false
        return "nextval(" in text || "identity" in text || "autoincrement" in text || "generated" in text
    }

    private fun numeric(
        name: Name,
        type: ColumnType,
        unique: kotlin.Boolean,
        plan: (GeneratorKind, Double?, Double?) -> ColumnPlan,
    ): ColumnPlan {
        val whole = if (type.isInteger) GeneratorKind.Integer else GeneratorKind.Decimal
        return when {
            unique -> plan(GeneratorKind.Sequence, null, null)
            name.isFlag() -> plan(GeneratorKind.Boolean, null, null)
            name.has("price", "cost", "amount", "total", "subtotal", "fee", "salary", "balance", "revenue", "tax", "discount",
                "wage", "payment", "budget", "income", "charge", "spend", "paid") -> plan(GeneratorKind.Price, null, null)
            name.has("latitude", "lat") -> plan(GeneratorKind.Decimal, -90.0, 90.0)
            name.has("longitude", "lng", "lon") -> plan(GeneratorKind.Decimal, -180.0, 180.0)
            name.has("age") -> plan(GeneratorKind.Integer, 18.0, 80.0)
            name.has("quantity", "qty", "stock", "inventory", "units") -> plan(GeneratorKind.Integer, 1.0, 100.0)
            name.has("count", "views", "likes", "clicks", "visits", "downloads", "followers") -> plan(GeneratorKind.Integer, 0.0, 10_000.0)
            name.has("rating", "stars") -> plan(GeneratorKind.Integer, 1.0, 5.0)
            name.has("score", "percent", "percentage", "pct", "progress") -> plan(whole, 0.0, 100.0)
            name.has("year") && type.column.typeName.lowercase() != "year" -> plan(GeneratorKind.Integer, 1990.0, 2030.0)
            name.has("priority", "level", "rank", "position", "sort", "order", "ordinal", "seq", "idx", "index") ->
                plan(GeneratorKind.Integer, 1.0, 100.0)
            name.has("status", "type", "kind", "state", "category", "role") -> plan(GeneratorKind.Integer, 0.0, 5.0)
            name.has("weight", "height", "width", "length", "depth", "distance", "duration", "size") -> plan(whole, 1.0, 1000.0)
            else -> plan(whole, null, null)
        }
    }

    /** A time column's kind by what its name says happened: `created_at` in the past, `expires_at` ahead. */
    private fun temporal(name: Name, dateOnly: kotlin.Boolean): GeneratorKind? {
        val past = if (dateOnly) GeneratorKind.PastDate else GeneratorKind.PastTimestamp
        val future = if (dateOnly) GeneratorKind.FutureDate else GeneratorKind.FutureTimestamp
        return when {
            name.has("birth", "dob", "birthday", "birthdate", "born") -> GeneratorKind.BirthDate
            name.has("due", "expires", "expiry", "expiration", "deadline", "scheduled", "until", "renews", "renewal", "next") -> future
            name.has(
                "created", "updated", "modified", "deleted", "changed", "last", "logged", "login", "published", "joined", "registered",
                "sent", "received", "paid", "seen", "visited", "completed", "closed", "opened", "signed", "verified", "confirmed",
            ) -> past
            else -> null
        }
    }

    private fun text(name: Name, type: ColumnType, unique: kotlin.Boolean, plan: (GeneratorKind, Double?, Double?) -> ColumnPlan): ColumnPlan {
        val size = type.textLimit ?: Int.MAX_VALUE
        val kind =
            when {
                name.has("uuid", "guid") -> GeneratorKind.Uuid
                name.has("email", "mail") || name.joined.contains("email") -> GeneratorKind.Email
                name.has("phone", "mobile", "tel", "telephone", "fax", "cell", "msisdn") || name.joined.contains("phone") -> GeneratorKind.Phone
                name.has("avatar", "image", "img", "photo", "picture", "thumbnail", "logo", "icon", "banner", "cover") -> GeneratorKind.ImageUrl
                name.has("url", "website", "homepage", "link", "href", "uri", "site") -> GeneratorKind.Url
                name.has("ip", "ipv4") || name.joined.contains("ipaddress") -> GeneratorKind.IpAddress
                name.has("domain", "hostname", "host") -> GeneratorKind.Domain
                name.has("fname", "forename", "firstname", "givenname") || name.has("first", "given", "middle") && name.has("name") ->
                    GeneratorKind.FirstName
                name.has("lname", "surname", "lastname", "familyname") || name.has("last", "family") && name.has("name") ->
                    GeneratorKind.LastName
                name.has("username", "login", "handle", "nick", "nickname", "screenname", "userid") ||
                    name.has("user", "screen") && name.has("name") -> GeneratorKind.Username
                name.has("company", "organization", "organisation", "org", "employer", "vendor", "supplier", "brand", "manufacturer",
                    "publisher", "merchant", "agency") -> GeneratorKind.Company
                name.has("product", "item", "sku") && name.has("name") || name.has("product") -> GeneratorKind.ProductName
                name.has("job", "occupation", "designation", "position", "profession") -> GeneratorKind.JobTitle
                name.has("street", "address", "addr", "line") -> GeneratorKind.Street
                name.has("city", "town", "municipality") -> GeneratorKind.City
                name.has("province", "region", "county", "prefecture") -> GeneratorKind.Region
                name.has("country") && (name.has("code", "iso", "iso2", "cc") || size <= 3) -> GeneratorKind.CountryCode
                name.has("country", "nationality") -> GeneratorKind.Country
                name.has("zip", "zipcode", "postal", "postcode", "postalcode") -> GeneratorKind.PostalCode
                name.has("currency", "ccy") -> GeneratorKind.CurrencyCode
                name.has("language", "lang", "locale") -> GeneratorKind.LanguageCode
                name.has("color", "colour") -> if (name.has("hex") || size in 6..7) GeneratorKind.HexColor else GeneratorKind.Color
                name.has("status", "state") -> GeneratorKind.Status
                name.has("slug", "permalink") -> GeneratorKind.Slug
                name.has("password", "passwd", "pwd", "hash", "digest", "checksum", "token", "secret", "salt", "signature", "fingerprint") ->
                    GeneratorKind.Hash
                name.has("file", "filename", "attachment", "path") -> GeneratorKind.FileName
                name.has("json", "metadata", "meta", "settings", "preferences", "config", "attributes", "properties", "payload", "extra") ->
                    GeneratorKind.Json
                name.has("description", "desc", "bio", "about", "summary", "body", "content", "notes", "note", "comment", "comments",
                    "message", "text", "details", "remarks", "review", "feedback", "biography") ->
                    if (size < 160) GeneratorKind.Sentence else GeneratorKind.Paragraph
                name.has("title", "subject", "headline", "caption", "label", "heading", "topic") -> GeneratorKind.Title
                name.has("sku", "code", "ref", "reference", "serial", "barcode", "invoice", "number", "no") -> GeneratorKind.Code
                name.isFlag() -> GeneratorKind.Boolean
                name.has("name", "fullname", "displayname", "author", "owner", "customer", "contact", "person", "employee", "member",
                    "recipient", "sender", "manager", "client", "assignee", "reporter") -> GeneratorKind.FullName
                // SQLite stores times as text more often than not.
                temporal(name, dateOnly = false) != null -> temporal(name, dateOnly = false)!!
                name.has("timestamp", "datetime", "at") -> GeneratorKind.Timestamp
                name.has("date", "day", "on") -> GeneratorKind.Date
                name.has("time") -> GeneratorKind.Time
                name.has("id", "key", "uid") -> if (size >= 36) GeneratorKind.Uuid else GeneratorKind.Code
                name.has("category", "type", "kind", "tag", "tags", "group", "role", "gender", "genre", "keyword") -> GeneratorKind.Word
                size <= 24 -> GeneratorKind.Word
                else -> GeneratorKind.Sentence
            }
        return if (unique && kind == GeneratorKind.Word && size >= 36) plan(GeneratorKind.Uuid, null, null) else plan(kind, null, null)
    }

    /** A column name split into lower-case words: `userEmail`, `user_email` and `user-email` are all `user`, `email`. */
    private class Name(raw: String) {
        val words: List<String> =
            raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
                .replace(Regex("([a-zA-Z])([0-9])"), "$1 $2")
                .lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.isNotEmpty() && it.any(Char::isLetter) }

        /** All words run together, to catch compounds written as one word: `firstname`, `phonenumber`. */
        val joined = words.joinToString("")

        fun has(vararg candidates: String): kotlin.Boolean = candidates.any { it in words || it == joined }

        /** `is_active`, `has_paid`, `enabled`, `deleted`: a yes/no column. */
        fun isFlag(): kotlin.Boolean {
            val first = words.firstOrNull() ?: return false
            return first in FlagPrefixes && words.size > 1 || words.size <= 2 && words.last() in FlagWords
        }

        companion object {
            private val FlagPrefixes = setOf("is", "has", "can", "should", "allow", "allows", "enable", "use", "was", "must", "show")
            private val FlagWords =
                setOf(
                    "flag", "enabled", "disabled", "active", "deleted", "verified", "published", "archived", "visible", "hidden",
                    "locked", "confirmed", "approved", "admin", "blocked", "banned", "featured", "public", "private", "default",
                    "subscribed", "read", "done", "completed",
                )
        }
    }
}
