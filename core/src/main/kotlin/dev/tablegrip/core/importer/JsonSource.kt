package dev.tablegrip.core.importer

import dev.tablegrip.core.i18n.tr
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence

/**
 * A JSON file of records: a top-level array (streamed element by element), NDJSON (streamed line by line), or one
 * object wrapping the array (`{"data": [...]}`, read into memory up to [MAX_DOCUMENT_BYTES]). Columns are the union of
 * the records' keys in first-seen order, so [open][ImportReader.open] reads a streamed file twice: once for keys, once
 * for rows. Scalars become their text (strings unquoted), nested values compact JSON; an element that isn't an object
 * becomes a `value` column.
 */
internal class JsonSource(private val file: File, requested: ImportOptions, previewRows: Int?) : ImportSource {
    private enum class Shape { Array, Lines, Document }

    override val options: ImportOptions
    override val columns: List<String>
    override val sheets: List<String> = emptyList()

    private val charset: Charset
    private val bomLength: Int
    private val shape: Shape
    private var document: List<JsonElement> = emptyList()
    private var buffered: List<JsonElement>? = null
    private val open = ArrayList<Closeable>()
    private var consumed = false

    init {
        val head = file.inputStream().use { it.readNBytes(4) }
        val bom = bomCharset(head)
        charset = requested.charset ?: bom?.first ?: StandardCharsets.UTF_8
        bomLength = if (bom != null && bom.first == charset) bom.second else 0
        options = requested.copy(charset = charset)
        try {
            shape = detectShape()
            val keys = LinkedHashSet<String>()
            if (previewRows != null) {
                buffered = records().take(previewRows).map { it.second }.toList().onEach { collectKeys(it, keys) }
            } else {
                records().forEach { collectKeys(it.second, keys) }
            }
            columns = keys.toList()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override fun rows(): Sequence<ImportRow> {
        check(!consumed) { "rows() can only be read once" }
        consumed = true
        val source = buffered?.asSequence()?.mapIndexed { i, record -> i + 1L to record } ?: records()
        return source.map { (index, record) -> ImportRow(index, values(record)) }
    }

    override fun close() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun values(record: JsonElement): List<String?> =
        if (record is JsonObject) columns.map { record[it]?.let(::text) } else columns.map { if (it == VALUE_COLUMN) text(record) else null }

    private fun collectKeys(record: JsonElement, keys: MutableSet<String>) {
        if (record is JsonObject) keys += record.keys else keys += VALUE_COLUMN
    }

    private fun text(element: JsonElement): String? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive -> element.content
            else -> element.toString()
        }

    private fun stream(): InputStream = file.inputStream().also { open += it }.apply { skipNBytes(bomLength.toLong()) }

    private fun reader(): Reader = InputStreamReader(stream(), charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE))

    /** kotlinx's stream decoder only reads UTF-8, so other encodings are re-encoded on the fly. */
    private fun utf8Stream(): InputStream = if (charset == StandardCharsets.UTF_8) stream().buffered() else Utf8InputStream(reader())

    /**
     * `[` is an array. `{` is NDJSON when the first line is a whole JSON value and more follows; otherwise the file is one
     * document: a wrapper around the records, a single record, or several pretty-printed objects back to back.
     */
    private fun detectShape(): Shape {
        reader().buffered().use { reader ->
            var c = reader.read()
            while (c != -1 && c.toChar().isWhitespace()) c = reader.read()
            when (c) {
                '['.code -> return Shape.Array
                '{'.code -> Unit
                -1 -> return Shape.Lines // empty file: no records
                else -> throw ImportException(tr("{0} doesn't start with [ or {; expected a JSON array of objects, an object wrapping one, or NDJSON", file.name))
            }
            val firstLine = StringBuilder("{")
            while (firstLine.length < MAX_LINE_CHARS) {
                c = reader.read()
                if (c == -1 || c == '\n'.code) break
                firstLine.append(c.toChar())
            }
            val complete = c == -1 || c == '\n'.code
            if (complete && runCatching { Json.parseToJsonElement(firstLine.toString()) }.isSuccess) {
                while (c != -1 && c.toChar().isWhitespace()) c = reader.read()
                if (c != -1) return Shape.Lines
            }
        }
        if (file.length() > MAX_DOCUMENT_BYTES) {
            throw ImportException(
                tr("{0} is one JSON object of {1} MB; an object wrapping the rows is read into memory and must be ", file.name, file.length() / (1024 * 1024)) +
                    tr("under {0} MB. Save it as a top-level array or NDJSON (one object per line) instead.", MAX_DOCUMENT_BYTES / (1024 * 1024)),
            )
        }
        val values = decode(DecodeSequenceMode.WHITESPACE_SEPARATED).map { it.second }.toList()
        document = values.singleOrNull()?.let(::unwrap) ?: values
        return Shape.Document
    }

    /** The largest array of objects inside a single object, or null when it has none (it's then one record). */
    private fun unwrap(element: JsonElement): List<JsonElement>? =
        (element as? JsonObject)?.values?.filterIsInstance<JsonArray>()?.filter { array -> array.isNotEmpty() && array.all { it is JsonObject } }?.maxByOrNull { it.size }

    /** Records with their 1-based index, read from the file afresh on every call. */
    private fun records(): Sequence<Pair<Long, JsonElement>> =
        when (shape) {
            Shape.Array -> decode(DecodeSequenceMode.ARRAY_WRAPPED)
            Shape.Document -> document.asSequence().mapIndexed { i, record -> i + 1L to record }
            Shape.Lines -> lines()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decode(mode: DecodeSequenceMode): Sequence<Pair<Long, JsonElement>> =
        sequence {
            val iterator = Json.decodeToSequence(utf8Stream(), JsonElement.serializer(), mode).iterator()
            var index = 0L
            while (true) {
                val next =
                    try {
                        if (!iterator.hasNext()) break
                        iterator.next()
                    } catch (e: SerializationException) {
                        throw ImportException(tr("Invalid JSON in record {0}: {1}", index + 1, e.message?.lineSequence()?.first()), index + 1, e)
                    } catch (e: IllegalArgumentException) {
                        throw ImportException(tr("Invalid JSON in record {0}: {1}", index + 1, e.message?.lineSequence()?.first()), index + 1, e)
                    }
                yield(++index to next)
            }
        }

    /** NDJSON: one value per line, blank lines ignored. Parse errors name the physical line. */
    private fun lines(): Sequence<Pair<Long, JsonElement>> =
        sequence {
            val reader = BufferedReader(reader())
            var line = 0L
            var index = 0L
            while (true) {
                val text = reader.readLine() ?: break
                line++
                if (text.isBlank()) continue
                val element =
                    try {
                        Json.parseToJsonElement(text)
                    } catch (e: SerializationException) {
                        throw ImportException(tr("Line {0} isn't valid JSON: {1}", line, e.message?.lineSequence()?.first()), line, e)
                    }
                yield(++index to element)
            }
        }

    internal companion object {
        /** A wrapped document is parsed into a tree several times its size, so this keeps it within a desktop heap. */
        const val MAX_DOCUMENT_BYTES = 256L * 1024 * 1024
        const val MAX_LINE_CHARS = 16 * 1024 * 1024
        const val VALUE_COLUMN = "value"
    }
}

/** Re-encodes a [Reader] as UTF-8 bytes. */
private class Utf8InputStream(private val reader: Reader) : InputStream() {
    private val encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val chars: CharBuffer = CharBuffer.allocate(8192).flip()
    private val bytes: ByteBuffer = ByteBuffer.allocate(8192 * 3 + 16).flip()
    private var endOfInput = false
    private var flushed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!bytes.hasRemaining()) if (!fill()) return -1
        val n = minOf(len, bytes.remaining())
        bytes.get(b, off, n)
        return n
    }

    private fun fill(): Boolean {
        if (flushed) return false
        chars.compact()
        if (!endOfInput && reader.read(chars) < 0) endOfInput = true
        chars.flip()
        bytes.clear()
        // A high surrogate cut off at the end of the buffer stays in [chars] until its pair arrives.
        encoder.encode(chars, bytes, endOfInput)
        if (endOfInput) {
            encoder.flush(bytes)
            flushed = true
        }
        bytes.flip()
        return true
    }

    override fun close() = reader.close()
}
