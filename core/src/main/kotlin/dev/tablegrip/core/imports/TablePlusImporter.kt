package dev.tablegrip.core.imports

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.EngineFamily
import dev.tablegrip.core.connection.ParsedConnection
import dev.tablegrip.core.connection.SshAuth
import dev.tablegrip.core.connection.SshConfig
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import org.w3c.dom.Element

/**
 * TablePlus' `Connections.plist` (macOS), with folder names from `ConnectionGroups.plist` next to it. Passwords stay
 * in TablePlus' Keychain items, which other apps can't read.
 */
internal object TablePlusImporter {

    /** The App Store / direct download build, then the Setapp one. */
    fun locations(home: Path): List<Path> =
        listOf("com.tinyapp.TablePlus", "com.tinyapp.TablePlus-setapp").map {
            home.resolve("Library/Application Support/$it/Data/Connections.plist")
        }

    fun read(file: Path): ImportScan {
        val connections = readPlist(file) as? List<*> ?: return ImportScan(skipped = listOf("$file isn't a TablePlus connection list."))
        val groups =
            (file.resolveSibling("ConnectionGroups.plist").takeIf { it.isRegularFile() }?.let { runCatching { readPlist(it) }.getOrNull() } as? List<*>)
                .orEmpty()
                .filterIsInstance<Map<*, *>>()
                .mapNotNull { group -> group.string("ID")?.let { id -> id to group.string("Name").orEmpty() } }
                .toMap()
        val candidates = mutableListOf<ImportCandidate>()
        val skipped = mutableListOf<String>()
        for (entry in connections.filterIsInstance<Map<*, *>>()) {
            val name = entry.string("ConnectionName").orEmpty()
            val driver = entry.string("Driver").orEmpty()
            val type = typeOf(driver)
            if (type == null) {
                skipped += "TablePlus: “$name” uses $driver, which TableGrip doesn't support yet."
                continue
            }
            val tls = (entry.string("tLSMode")?.toIntOrNull() ?: 0) > 0
            val parsed =
                ParsedConnection(
                    type = type,
                    host = entry.string("DatabaseHost")?.ifEmpty { null }.takeIf { type.isServer },
                    port = entry.string("DatabasePort")?.toIntOrNull().takeIf { type.isServer },
                    database = if (type.isFile) entry.string("DatabasePath") else entry.string("DatabaseName"),
                    user = entry.string("DatabaseUser"),
                    properties =
                        when {
                            !tls -> emptyMap()
                            type.family == EngineFamily.PostgreSQL -> mapOf("sslmode" to "require")
                            type.family == EngineFamily.Redis -> mapOf("ssl" to "true")
                            else -> emptyMap()
                        },
                )
            val config =
                ConnectionImport.fromParsed(
                    parsed,
                    name = name,
                    group = entry.string("GroupID")?.let(groups::get).orEmpty(),
                    environment = ConnectionImport.environmentOf(entry.string("Enviroment") ?: entry.string("Environment")),
                    ssh = ssh(entry),
                )
            val notes = buildList {
                if (driver.equals("Redshift", ignoreCase = true)) add("Redshift is imported as PostgreSQL")
                if (tls && parsed.properties.isEmpty()) add("TLS was on in TablePlus: turn it on under Options › Driver properties")
            }
            candidates += ImportCandidate(config, origin = file.toString(), notes = notes)
        }
        return ImportScan(candidates, skipped)
    }

    private fun ssh(entry: Map<*, *>): SshConfig? {
        if (!entry.boolean("isOverSSH")) return null
        val host = entry.string("ServerAddress")?.ifEmpty { null } ?: return null
        val useKey = entry.boolean("isUsePrivateKey")
        val keyPath = listOf("PrivateKeyPath", "ServerPrivateKeyPath", "PrivateKey").firstNotNullOfOrNull { key -> entry.string(key)?.takeIf { it.startsWith("/") || it.startsWith("~") } }
        return SshConfig(
            host = host,
            port = entry.string("ServerPort")?.toIntOrNull() ?: 22,
            user = entry.string("ServerUser")?.ifEmpty { null } ?: System.getProperty("user.name"),
            auth = if (useKey) SshAuth.PrivateKey else SshAuth.Password,
            privateKeyPath = keyPath.takeIf { useKey },
        )
    }

    private fun typeOf(driver: String): DatabaseType? =
        when (driver.lowercase()) {
            "postgresql", "postgres", "redshift" -> DatabaseType.PostgreSQL
            "mysql" -> DatabaseType.MySQL
            "mariadb" -> DatabaseType.MariaDB
            "sqlite" -> DatabaseType.SQLite
            "sqlserver", "microsoft sql server", "mssql" -> DatabaseType.SqlServer
            "cockroachdb", "cockroach" -> DatabaseType.CockroachDB
            "clickhouse" -> DatabaseType.ClickHouse
            "duckdb" -> DatabaseType.DuckDB
            "redis" -> DatabaseType.Redis
            "mongodb", "mongo" -> DatabaseType.MongoDB
            else -> null
        }

    private fun Map<*, *>.string(key: String): String? = this[key]?.toString()

    private fun Map<*, *>.boolean(key: String): Boolean = this[key].let { it == true || it?.toString() == "1" || it?.toString().equals("true", ignoreCase = true) }

    // region Property lists

    /** Reads an XML property list, or a binary one through macOS' `plutil`. */
    fun readPlist(file: Path): Any? {
        val head = file.inputStream().use { it.readNBytes(8) }
        if (String(head, Charsets.US_ASCII).startsWith("bplist")) {
            val process = ProcessBuilder("plutil", "-convert", "xml1", "-o", "-", file.toString()).redirectErrorStream(false).start()
            val xml = process.inputStream.use { it.readBytes() }
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "plutil couldn't read $file" }
            return parseXmlPlist(xml.inputStream())
        }
        return file.inputStream().use(::parseXmlPlist)
    }

    fun parseXmlPlist(input: InputStream): Any? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Property lists start with Apple's DOCTYPE: skip loading its DTD rather than refusing it.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isExpandEntityReferences = false
            isIgnoringComments = true
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        return root.elementChildren().firstOrNull()?.let(::value)
    }

    private fun value(element: Element): Any? =
        when (element.tagName) {
            "dict" -> {
                val children = element.elementChildren()
                children.chunked(2).filter { it.size == 2 && it[0].tagName == "key" }.associate { (key, value) -> key.textContent to value(value) }
            }
            "array" -> element.elementChildren().map(::value)
            "true" -> true
            "false" -> false
            "integer" -> element.textContent.trim().toLongOrNull()
            "real" -> element.textContent.trim().toDoubleOrNull()
            else -> element.textContent
        }

    private fun Element.elementChildren(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    // endregion
}
