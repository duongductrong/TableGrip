package dev.tablegrip.core.importer

import dev.tablegrip.core.i18n.tr
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * RFC 4180 record reader over a [Reader], tolerant of what real exports contain: a stray quote inside an unquoted field
 * is kept literally, text after a closing quote is appended (`"ab"c` -> `abc`), spaces before an opening quote are
 * padding, and blank lines are skipped. Line breaks may be CRLF, LF or CR; inside quotes they are kept verbatim.
 */
internal class CsvParser(
    private val reader: Reader,
    private val delimiter: Char,
    private val quote: Char,
    private val emptyAsNull: Boolean,
    private val encoding: String = "",
) {
    private val buffer = CharArray(64 * 1024)
    private var pos = 0
    private var limit = 0
    private var started = false

    /** Physical line of the next unread character. */
    var line = 1L
        private set

    /** Physical line on which the record last returned by [next] starts. */
    var recordLine = 0L
        private set

    private fun read(): Int {
        if (pos == limit) {
            val n =
                try {
                    reader.read(buffer)
                } catch (e: CharacterCodingException) {
                    throw ImportException(tr("The file isn't valid {0} text near line {1}; choose its encoding (e.g. windows-1252) and try again", encoding, line), line, e)
                }
            if (n <= 0) return -1
            pos = 0
            limit = n
            // A BOM decodes to U+FEFF; drop it so it doesn't end up in the first header name.
            if (!started) {
                started = true
                if (buffer[0] == '﻿') return if (++pos == limit) read() else buffer[pos++].code
            }
        }
        return buffer[pos++].code
    }

    /** After a CR: swallows the LF of a CRLF. `pos--` is safe because [read] just returned a char from the buffer. */
    private fun skipLf() {
        val c = read()
        if (c != LF && c != -1) pos--
    }

    /** Skips [count] physical lines (ignoring quotes: these are report titles and the like, not records). */
    fun skipLines(count: Int) {
        var left = count
        while (left > 0) {
            when (read()) {
                -1 -> return
                LF -> { line++; left-- }
                CR -> { skipLf(); line++; left-- }
            }
        }
    }

    /** The next record's fields, or null at end of input. */
    fun next(): List<String?>? {
        val q = quote.code
        val d = delimiter.code
        val fields = ArrayList<String?>()
        val field = StringBuilder()
        while (true) {
            recordLine = line
            fields.clear()
            field.setLength(0)
            var state = START
            var quoted = false
            var firstQuoted = false

            fun endField() {
                if (fields.isEmpty()) firstQuoted = quoted
                fields.add(if (!quoted && emptyAsNull && field.isEmpty()) null else field.toString())
                field.setLength(0)
                quoted = false
                state = START
            }

            fun append(c: Int) {
                if (field.length >= MAX_FIELD_CHARS) {
                    throw ImportException(
                        tr("A field in the record starting on line {0} is longer than {1}M characters; ", recordLine, MAX_FIELD_CHARS / 1_000_000) +
                            tr("the file probably has an unclosed quote or a wrong delimiter"),
                        recordLine,
                    )
                }
                field.append(c.toChar())
            }

            record@ while (true) {
                val c = read()
                when (state) {
                    START ->
                        when (c) {
                            -1 -> if (fields.isEmpty() && field.isEmpty()) return null else { endField(); break@record }
                            q -> { field.setLength(0); quoted = true; state = QUOTED }
                            d -> endField()
                            LF -> { line++; endField(); break@record }
                            CR -> { skipLf(); line++; endField(); break@record }
                            // Stay in START so `a, "b"` still reads b as quoted; the spaces are dropped if a quote follows.
                            SPACE -> append(c)
                            else -> { append(c); state = UNQUOTED }
                        }
                    UNQUOTED ->
                        when (c) {
                            -1 -> { endField(); break@record }
                            d -> endField()
                            LF -> { line++; endField(); break@record }
                            CR -> { skipLf(); line++; endField(); break@record }
                            else -> append(c)
                        }
                    QUOTED ->
                        when (c) {
                            -1 -> throw ImportException(tr("Unclosed quote in the record starting on line {0}", recordLine), recordLine)
                            q -> state = AFTER_QUOTE
                            LF -> { append(c); line++ }
                            CR -> {
                                append(c)
                                val after = read()
                                if (after == LF) append(after) else if (after != -1) pos--
                                line++
                            }
                            else -> append(c)
                        }
                    AFTER_QUOTE ->
                        when (c) {
                            q -> { append(c); state = QUOTED }
                            -1 -> { endField(); break@record }
                            d -> endField()
                            LF -> { line++; endField(); break@record }
                            CR -> { skipLf(); line++; endField(); break@record }
                            SPACE -> Unit
                            else -> { append(c); state = UNQUOTED }
                        }
                }
            }
            if (fields.size == 1 && !firstQuoted && fields[0].isNullOrBlank()) continue
            return fields.toList()
        }
    }

    private companion object {
        const val START = 0
        const val UNQUOTED = 1
        const val QUOTED = 2
        const val AFTER_QUOTE = 3
        const val LF = '\n'.code
        const val CR = '\r'.code
        const val SPACE = ' '.code

        /** Caps memory when an unclosed quote would otherwise swallow the rest of a multi-GB file. */
        const val MAX_FIELD_CHARS = 16_000_000
    }
}

/**
 * A CSV/TSV file. Opening reads a bounded sample to detect the encoding and delimiter, then streams records; without a
 * header the first [NO_HEADER_SCAN] records are buffered to size the columns.
 */
internal class CsvSource(file: File, requested: ImportOptions) : ImportSource {
    override val options: ImportOptions
    override val columns: List<String>
    override val sheets: List<String> = emptyList()

    private val reader: Reader
    private val parser: CsvParser
    private val pending = ArrayDeque<ImportRow>()
    private var consumed = false

    init {
        val charset = requested.charset ?: detectCharset(file)
        val delimiter = requested.delimiter ?: detectDelimiter(file, charset, requested)
        options = requested.copy(charset = charset, delimiter = delimiter)
        reader = DecodingReader(file.inputStream(), decoder(charset))
        try {
            parser = CsvParser(reader, delimiter, requested.quote, requested.emptyAsNull, charset.displayName())
            parser.skipLines(requested.skipRows)
            columns =
                if (requested.hasHeader) {
                    normalizeColumnNames(parser.next().orEmpty())
                } else {
                    while (pending.size < NO_HEADER_SCAN) {
                        val values = parser.next() ?: break
                        pending += ImportRow(parser.recordLine, values)
                    }
                    generatedColumnNames(pending.maxOfOrNull { it.values.size } ?: 0)
                }
        } catch (e: Throwable) {
            reader.close()
            throw e
        }
    }

    override fun rows(): Sequence<ImportRow> {
        check(!consumed) { "rows() can only be read once" }
        consumed = true
        return sequence {
            while (pending.isNotEmpty()) pending.removeFirst().let { yield(fitRow(it.line, it.values, columns.size, tr("Line"))) }
            while (true) {
                val values = parser.next() ?: break
                yield(fitRow(parser.recordLine, values, columns.size, tr("Line")))
            }
        }
    }

    override fun close() = reader.close()

    internal companion object {
        const val NO_HEADER_SCAN = 100
        const val SAMPLE_RECORDS = 20
        const val SAMPLE_CHARS = 256 * 1024
        private const val SNIFF_BYTES = 1024 * 1024
        val DELIMITERS = listOf(',', ';', '\t', '|')
        val WINDOWS_1252: Charset = Charset.forName("windows-1252")

        /** BOM first; else UTF-8 if the first MB decodes cleanly (a cut multi-byte sequence at the end is fine), else windows-1252. */
        fun detectCharset(file: File): Charset {
            val head = file.inputStream().use { it.readNBytes(SNIFF_BYTES) }
            bomCharset(head)?.let { return it.first }
            val result =
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.size), head.size < SNIFF_BYTES)
            return if (result.isError) WINDOWS_1252 else StandardCharsets.UTF_8
        }

        /**
         * Strict for UTF-8 so a misdetected file fails loudly (naming the line) instead of importing U+FFFD; single-byte
         * code pages map nearly every byte, and the few gaps are replaced.
         */
        fun decoder(charset: Charset) =
            charset.newDecoder().apply {
                val action = if (charset == StandardCharsets.UTF_8) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
                onMalformedInput(action)
                onUnmappableCharacter(action)
            }

        private fun detectDelimiter(file: File, charset: Charset, options: ImportOptions): Char =
            detectDelimiter(readSample(file, charset, options.skipRows), options.quote)

        /** Up to [SAMPLE_CHARS] chars after [skipLines] physical lines; the flag says whether that's the whole rest of the file. */
        private fun readSample(file: File, charset: Charset, skipLines: Int): Pair<String, Boolean> {
            val lenient = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            InputStreamReader(file.inputStream(), lenient).buffered().use { reader ->
                var left = skipLines
                while (left > 0) {
                    when (reader.read()) {
                        -1 -> return "" to true
                        '\n'.code -> left--
                        '\r'.code -> {
                            reader.mark(1)
                            if (reader.read() != '\n'.code) reader.reset()
                            left--
                        }
                    }
                }
                val chars = CharArray(SAMPLE_CHARS)
                var n = 0
                while (n < chars.size) {
                    val read = reader.read(chars, n, chars.size - n)
                    if (read < 0) break
                    n += read
                }
                val complete = n < chars.size || reader.read() == -1
                return String(chars, 0, n).removePrefix("﻿") to complete
            }
        }

        /**
         * Picks the candidate whose field count (above 1) is shared by the most of the first [SAMPLE_RECORDS] records,
         * parsing with quotes so delimiters inside quoted text don't count. Ties go to the wider split, then to list
         * order; nothing splitting means a one-column file, read with a comma.
         */
        fun detectDelimiter(sample: Pair<String, Boolean>, quote: Char): Char {
            val (text, complete) = sample
            var best = ','
            var bestScore = 0 to 0
            for (candidate in DELIMITERS) {
                if (candidate == quote) continue
                val parser = CsvParser(StringReader(text), candidate, quote, true)
                val counts = ArrayList<Int>()
                var hitEnd = false
                try {
                    while (counts.size <= SAMPLE_RECORDS) {
                        val record = parser.next()
                        if (record == null) { hitEnd = true; break }
                        counts += record.size
                    }
                } catch (_: ImportException) {
                    // An unclosed quote in a cut-off sample: the records before it are still evidence.
                }
                // The last record of a cut-off sample may be partial.
                if (hitEnd && !complete) counts.removeLastOrNull()
                val firstRecords = counts.take(SAMPLE_RECORDS)
                val (width, matches) = firstRecords.filter { it > 1 }.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key }))?.toPair() ?: continue
                val score = matches to width
                if (compareValuesBy(score, bestScore, { it.first }, { it.second }) > 0) {
                    best = candidate
                    bestScore = score
                }
            }
            return best
        }
    }
}

/** The charset a byte-order mark announces, and the mark's length. */
internal fun bomCharset(head: ByteArray): Pair<Charset, Int>? {
    fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> StandardCharsets.UTF_8 to 3
        at(0) == 0xFE && at(1) == 0xFF -> StandardCharsets.UTF_16BE to 2
        at(0) == 0xFF && at(1) == 0xFE -> StandardCharsets.UTF_16LE to 2
        else -> null
    }
}

/**
 * Like InputStreamReader, but on malformed input it first returns the characters decoded before it and throws only on
 * the next read, so the parser's line count points at the bad bytes. (InputStreamReader throws and drops them.)
 */
internal class DecodingReader(private val input: InputStream, private val decoder: CharsetDecoder) : java.io.Reader() {
    private val bytes: ByteBuffer = ByteBuffer.allocate(64 * 1024).flip()
    private var endOfInput = false
    private var flushed = false

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (flushed) return -1
        val out = CharBuffer.wrap(buffer, offset, length)
        while (true) {
            val result = decoder.decode(bytes, out, endOfInput)
            if (result.isError) {
                // The input stays at the bad bytes, so the next call hits the error again with nothing decoded.
                if (out.position() > offset) break
                result.throwException()
            }
            if (result.isOverflow) break
            if (endOfInput) {
                decoder.flush(out)
                flushed = true
                break
            }
            if (out.position() > offset) break
            bytes.compact()
            val n = input.read(bytes.array(), bytes.position(), bytes.remaining())
            if (n < 0) endOfInput = true else bytes.position(bytes.position() + n)
            bytes.flip()
        }
        val count = out.position() - offset
        return if (count == 0 && flushed) -1 else count
    }

    override fun close() = input.close()
}
