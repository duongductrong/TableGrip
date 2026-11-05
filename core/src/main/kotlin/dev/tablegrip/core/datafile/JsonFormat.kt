package dev.tablegrip.core.datafile

import dev.tablegrip.core.connection.DataFileOptions
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.importer.ImportException
import dev.tablegrip.core.importer.ImportFormat
import dev.tablegrip.core.importer.ImportOptions
import dev.tablegrip.core.importer.ImportReader
import dev.tablegrip.core.importer.ImportSource
import dev.tablegrip.core.importer.bomCharset
import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.nameWithoutExtension
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeToSequence

/**
 * JSON records: a top-level array of objects or NDJSON (one object per line), one table named after the file. Columns
 * are the union of the objects' keys; nested objects and arrays are compact JSON text. Writing keeps the shape, the
 * indentation and each column's JSON type (numbers, booleans and nested values stay unquoted), and leaves out keys that
 * were missing rather than null. Other layouts (an object wrapping the records, UTF-16) open read-only.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonFormat : DataFileFormat {
    override val type: DatabaseType = DatabaseType.JsonFile
    override val extensions: Set<String> = setOf("json", "ndjson", "jsonl")

    override fun open(path: Path, options: DataFileOptions): DataFileDocument {
        val head = path.inputStream().use { it.readNBytes(HEAD_BYTES) }
        val bom = bomCharset(head)
        if (bom != null && bom.first != Charsets.UTF_8) return readOnly(path, "Only UTF-8 JSON files can be saved; this one is ${bom.first.displayName()}.")
        val bomLength = bom?.second ?: 0
        val text = String(head, bomLength, head.size - bomLength, Charsets.UTF_8)
        val shape =
            when (text.trimStart().firstOrNull()) {
                '[' -> Shape.Array
                '{' -> if (isLines(text)) Shape.Lines else return readOnly(path, "Only a top-level array of objects or NDJSON (one object per line) can be saved.")
                null -> Shape.Lines
                else -> throw DataFileException("${path.fileName} doesn't start with [ or {; expected a JSON array of objects or NDJSON")
            }
        val document = Document(path, path.nameWithoutExtension.ifBlank { "data" }, shape, bomLength, layout(text, shape))
        document.scanKeys()
        return document
    }

    private enum class Shape { Array, Lines }

    /** How records are laid out: [indent] is null for compact records, else one level of pretty-printing. */
    private data class Layout(val recordPerLine: Boolean, val indent: String?)

    private enum class Kind {
        String, Number, Boolean, Nested, Mixed;

        fun merge(other: Kind): Kind = if (this == other) this else Mixed
    }

    private class ColumnState(val name: String) {
        var kind: Kind? = null
        var absent = false
        var nulls = false
    }

    private class Document(
        private val path: Path,
        private val name: String,
        private val shape: Shape,
        private val bomLength: Int,
        private val layout: Layout,
    ) : DataFileDocument {
        private val columns = LinkedHashMap<String, ColumnState>()
        override val multiTable = false

        override val notes: List<String>
            get() = columns.values.filter { it.kind == Kind.Mixed }.map { "Column \"${it.name}\" mixes JSON types; saving writes its values as strings." }

        fun scanKeys() = records().forEach { (_, record) -> record.keys.forEach { columns.getOrPut(it) { ColumnState(it) } } }

        override val tables: List<SourceTable>
            get() =
                listOf(
                    object : SourceTable {
                        override val name = this@Document.name
                        override val columns = this@Document.columns.keys.toList()

                        override fun rows(): Sequence<List<String?>> =
                            records().map { (_, record) ->
                                this@Document.columns.values.map { column ->
                                    val element = record[column.name]
                                    when {
                                        element == null -> null.also { column.absent = true }
                                        element is JsonNull -> null.also { column.nulls = true }
                                        else -> {
                                            val kind =
                                                when {
                                                    element !is JsonPrimitive -> Kind.Nested
                                                    element.isString -> Kind.String
                                                    element.booleanOrNull != null -> Kind.Boolean
                                                    else -> Kind.Number
                                                }
                                            column.kind = column.kind?.merge(kind) ?: kind
                                            if (element is JsonPrimitive) element.content else element.toString()
                                        }
                                    }
                                }
                            }
                    },
                )

        /** Records with their 1-based index; reading fails on anything but an object. */
        private fun records(): Sequence<Pair<Long, JsonObject>> =
            when (shape) {
                Shape.Array -> arrayRecords()
                Shape.Lines -> lineRecords()
            }.map { (index, element) ->
                index to (element as? JsonObject ?: throw DataFileException("Record $index isn't a JSON object; only arrays of objects open as a table."))
            }

        private fun stream(): InputStream = path.inputStream().buffered().apply { skipNBytes(bomLength.toLong()) }

        private fun arrayRecords(): Sequence<Pair<Long, JsonElement>> =
            sequence {
                stream().use { input ->
                    val iterator = Json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).iterator()
                    var index = 0L
                    while (true) {
                        val next =
                            try {
                                if (!iterator.hasNext()) break
                                iterator.next()
                            } catch (e: SerializationException) {
                                throw DataFileException("Invalid JSON in record ${index + 1}: ${e.message?.lineSequence()?.first()}", e)
                            } catch (e: IllegalArgumentException) {
                                throw DataFileException("Invalid JSON in record ${index + 1}: ${e.message?.lineSequence()?.first()}", e)
                            }
                        yield(++index to next)
                    }
                }
            }

        private fun lineRecords(): Sequence<Pair<Long, JsonElement>> =
            sequence {
                BufferedReader(stream().reader(Charsets.UTF_8)).use { reader ->
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
                                throw DataFileException("Line $line isn't valid JSON: ${e.message?.lineSequence()?.first()}", e)
                            }
                        yield(++index to element)
                    }
                }
            }

        override fun write(target: Path, tables: List<OutputTable>, removed: Set<String>) {
            val table = tables.single()
            val states = table.columns.map { columns[it.name] }
            val json = if (layout.indent != null) Json { prettyPrint = true; prettyPrintIndent = layout.indent } else Json
            Files.newOutputStream(target).buffered(64 * 1024).writer(Charsets.UTF_8).use { out ->
                if (bomLength > 0) out.write('﻿'.code)
                if (shape == Shape.Array) out.write(if (layout.recordPerLine) "[\n" else "[")
                var first = true
                for (row in table.rows()) {
                    val record = LinkedHashMap<String, JsonElement>()
                    row.forEachIndexed { i, value ->
                        val column = table.columns[i]
                        val state = states[i]
                        val element = element(value, state?.kind ?: kindOf(column.kind))
                        if (element == JsonNull && state != null && state.absent && !state.nulls) return@forEachIndexed
                        record[column.name] = element
                    }
                    val text = json.encodeToString(JsonElement.serializer(), JsonObject(record))
                    when (shape) {
                        Shape.Lines -> out.write(text + "\n")
                        Shape.Array -> {
                            if (!first) out.write(if (layout.recordPerLine) ",\n" else ",")
                            out.write(if (layout.indent != null) text.prependIndent(layout.indent) else text)
                        }
                    }
                    first = false
                }
                if (shape == Shape.Array) out.write(if (layout.recordPerLine) "\n]\n" else "]")
            }
        }

        private fun kindOf(kind: ColumnKind): Kind = if (kind == ColumnKind.Text) Kind.String else Kind.Number

        private fun element(value: Any?, kind: Kind): JsonElement {
            val text = DataValues.text(value) ?: return JsonNull
            return when {
                // "123" stays a string even though the working copy typed its column as numbers.
                kind == Kind.String || kind == Kind.Mixed -> JsonPrimitive(text)
                value is Long || value is Int -> JsonPrimitive((value as Number).toLong())
                value is Double && value.isFinite() -> JsonUnquotedLiteral(DataValues.real(value))
                kind == Kind.Number && JsonNumber.matches(text) -> JsonUnquotedLiteral(text)
                kind == Kind.Boolean && (text == "true" || text == "false") -> JsonPrimitive(text == "true")
                kind == Kind.Nested -> runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                else -> JsonPrimitive(text)
            }
        }
    }

    /** Opened through the importer's reader, which reads every layout but can't say how to write it back. */
    private fun readOnly(path: Path, reason: String): DataFileDocument {
        val source: ImportSource =
            try {
                ImportReader.open(path.toFile(), ImportOptions(ImportFormat.Json))
            } catch (e: ImportException) {
                throw DataFileException(e.message ?: "Couldn't read ${path.fileName}", e)
            }
        return object : DataFileDocument {
            override val multiTable = false
            override val readOnlyReason = reason
            override val tables: List<SourceTable> =
                listOf(
                    object : SourceTable {
                        override val name = path.nameWithoutExtension.ifBlank { "data" }
                        override val columns = source.columns

                        override fun rows(): Sequence<List<String?>> =
                            sequence {
                                try {
                                    for (row in source.rows()) yield(row.values)
                                } catch (e: ImportException) {
                                    throw DataFileException(e.message ?: "Couldn't read ${path.fileName}", e)
                                }
                            }
                    },
                )

            override fun write(target: Path, tables: List<OutputTable>, removed: Set<String>) = throw DataFileException(reason)

            override fun close() = source.close()
        }
    }

    /** NDJSON when the first line is a whole JSON value and more follows it. */
    private fun isLines(text: String): Boolean {
        val trimmed = text.trimStart()
        val newline = trimmed.indexOf('\n')
        if (newline < 0) return false
        val first = trimmed.substring(0, newline)
        return runCatching { Json.parseToJsonElement(first) }.isSuccess && trimmed.substring(newline).isNotBlank()
    }

    /** From the start of the file: whether records start on lines of their own, and the indent of a pretty-printed one. */
    private fun layout(text: String, shape: Shape): Layout {
        if (shape == Shape.Lines) return Layout(recordPerLine = true, indent = null)
        val open = text.indexOf('[')
        val record = text.indexOf('{', open + 1)
        if (record < 0) return Layout(recordPerLine = true, indent = null)
        val recordPerLine = '\n' in text.substring(open, record)
        val end = text.indexOf('}', record)
        val body = if (end < 0) text.substring(record) else text.substring(record, end)
        if ('\n' !in body) return Layout(recordPerLine, indent = null)
        val keyLine = body.substringAfter('\n')
        val keyIndent = keyLine.takeWhile { it == ' ' || it == '\t' }
        val recordIndent = text.substring(text.lastIndexOf('\n', record) + 1, record).takeWhile { it == ' ' || it == '\t' }
        val unit = keyIndent.removePrefix(recordIndent).ifEmpty { "  " }
        return Layout(recordPerLine = true, indent = unit)
    }

    private val JsonNumber = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
    private const val HEAD_BYTES = 64 * 1024
}
