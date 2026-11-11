package dev.tablegrip.core.imports

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.ParsedConnection
import dev.tablegrip.core.connection.SshAuth
import dev.tablegrip.core.connection.SshConfig
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * DBeaver's `data-sources*.json`, one set per project, with the logins it keeps in the project's
 * `credentials-config.json`. That file is encrypted with a key built into DBeaver (the same on every install), so it can
 * be read unless the user protected it with a master password.
 */
internal object DBeaverImporter {

    /** Every project's data source files in the workspace of this OS. */
    fun locations(home: Path, env: Map<String, String>): List<Path> {
        val roots =
            listOfNotNull(
                home.resolve("Library/DBeaverData/workspace6"),
                home.resolve(".local/share/DBeaverData/workspace6"),
                env["APPDATA"]?.let { Path.of(it, "DBeaverData", "workspace6") },
            )
        return roots.filter { it.isDirectory() }.flatMap { root ->
            root.listDirectoryEntries().filter { it.isDirectory() && !it.name.startsWith(".") }.sorted().flatMap { project ->
                val config = project.resolve(".dbeaver")
                if (!config.isDirectory()) emptyList()
                else config.listDirectoryEntries("data-sources*.json").filter { it.isRegularFile() }.sorted()
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: Path): ImportScan {
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val connections = root["connections"] as? JsonObject ?: return ImportScan()
        val credentials = file.parent?.resolve("credentials-config.json")?.takeIf { it.isRegularFile() }?.let { runCatching { decryptCredentials(it.readBytes()) }.getOrNull() }
        // The workspace's default project is "General"; others become part of the group.
        val project = file.parent?.parent?.name?.takeIf { it != "General" }
        val candidates = mutableListOf<ImportCandidate>()
        val skipped = mutableListOf<String>()
        for ((id, element) in connections) {
            val connection = element as? JsonObject ?: continue
            val name = connection.string("name") ?: id
            val configuration = connection["configuration"] as? JsonObject ?: JsonObject(emptyMap())
            val type = typeOf(connection.string("provider"), connection.string("driver"))
            val url = configuration.string("url")
            val parsed =
                when {
                    type == null && url != null && url.startsWith("jdbc:") -> ConnectionImport.parseOrNull(url)
                    type == null -> null
                    configuration.string("configurationType") == "URL" || (configuration.string("host").isNullOrEmpty() && type.isServer) ->
                        url?.let(ConnectionImport::parseOrNull)?.let { if (it.type.family == type.family) it else it.copy(type = type) }
                    else -> fields(type, configuration)
                }
            if (parsed == null) {
                skipped += tr("DBeaver: “{0}” ({1}) isn't supported.", name, connection.string("provider") ?: "unknown")
                continue
            }
            val login = credentials?.get(id)
            val ssh = ssh(configuration, login)
            val folder = connection.string("folder").orEmpty()
            val config =
                ConnectionImport.fromParsed(
                    parsed.copy(user = login?.connection?.first ?: configuration.string("user") ?: parsed.user),
                    name = name,
                    group = listOfNotNull(project, folder.ifEmpty { null }).joinToString("/"),
                    environment = ConnectionImport.environmentOf(configuration.string("type")),
                    readOnly = connection.boolean("read-only") ?: configuration.boolean("read-only") ?: false,
                    ssh = ssh,
                )
            val password = login?.connection?.second ?: configuration.string("password") ?: parsed.password
            val notes = buildList {
                addAll(parsed.warnings)
                if (password == null && connection.boolean("save-password") == true && credentials == null) add(tr("DBeaver's saved password couldn't be read"))
                if (parsed.type == DatabaseType.OtherJdbc) add(tr("Choose the driver's jar when connecting"))
            }
            candidates += ImportCandidate(config, password = password?.ifEmpty { null }, sshSecret = login?.ssh?.second, origin = file.toString(), notes = notes)
        }
        return ImportScan(candidates, skipped)
    }

    /** DBeaver keeps host, port and database as separate fields (the port as a string). */
    private fun fields(type: DatabaseType, configuration: JsonObject): ParsedConnection {
        val properties = (configuration["properties"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        return ParsedConnection(
            type = type,
            host = configuration.string("host").takeIf { type.isServer },
            port = configuration.string("port")?.toIntOrNull().takeIf { type.isServer },
            database = configuration.string("database") ?: configuration.string("url")?.takeIf { type.isFile }?.substringAfter(':')?.substringAfter(':'),
            user = configuration.string("user"),
            properties = properties,
        )
    }

    private fun ssh(configuration: JsonObject, login: Login?): SshConfig? {
        val handler = (configuration["handlers"] as? JsonObject)?.get("ssh_tunnel") as? JsonObject ?: return null
        if (handler.boolean("enabled") != true) return null
        val properties = handler["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val host = properties.string("host") ?: return null
        val auth = properties.string("authType")
        return SshConfig(
            host = host,
            port = properties.string("port")?.toIntOrNull() ?: 22,
            user = login?.ssh?.first ?: handler.string("user") ?: properties.string("user") ?: System.getProperty("user.name"),
            auth = if (auth == "PASSWORD") SshAuth.Password else SshAuth.PrivateKey,
            privateKeyPath = properties.string("keyPath")?.takeIf { auth == "PUBLIC_KEY" && it.isNotBlank() },
        )
    }

    /** DBeaver's provider and driver ids; Redis and MongoDB come from its commercial editions. */
    private fun typeOf(provider: String?, driver: String?): DatabaseType? {
        val d = driver.orEmpty().lowercase()
        return when {
            "cockroach" in d -> DatabaseType.CockroachDB
            "timescale" in d -> DatabaseType.TimescaleDB
            "yugabyte" in d -> DatabaseType.YugabyteDB
            "oracle" in d -> DatabaseType.Oracle
            "firebird" in d || "jaybird" in d -> DatabaseType.Firebird
            "trino" in d -> DatabaseType.Trino
            "presto" in d -> DatabaseType.Presto
            "redshift" in d -> DatabaseType.Redshift
            "questdb" in d -> DatabaseType.QuestDB
            "oceanbase" in d -> DatabaseType.OceanBase
            "doris" in d -> DatabaseType.Doris
            "starrocks" in d -> DatabaseType.StarRocks
            "mariadb" in d -> DatabaseType.MariaDB
            "tidb" in d -> DatabaseType.TiDB
            "duckdb" in d -> DatabaseType.DuckDB
            "clickhouse" in d -> DatabaseType.ClickHouse
            "sqlite" in d -> DatabaseType.SQLite
            d.startsWith("h2") -> DatabaseType.H2
            "hsql" in d -> DatabaseType.HSQLDB
            "derby" in d -> DatabaseType.Derby
            else ->
                when (provider?.lowercase()) {
                    "postgresql" -> DatabaseType.PostgreSQL
                    "mysql" -> DatabaseType.MySQL
                    "sqlite" -> DatabaseType.SQLite
                    "sqlserver", "mssql" -> DatabaseType.SqlServer
                    "clickhouse" -> DatabaseType.ClickHouse
                    "duckdb" -> DatabaseType.DuckDB
                    "redis" -> DatabaseType.Redis
                    "mongodb" -> DatabaseType.MongoDB
                    else -> null
                }
        }
    }

    /** User and password for the database ([connection]) and for the SSH tunnel ([ssh]). */
    data class Login(val connection: Pair<String?, String?>?, val ssh: Pair<String?, String?>?)

    /** DBeaver's built-in key for `credentials-config.json`: AES-128-CBC, the IV in the first 16 bytes. */
    private val Key = "babb4a9f774ab853c96c2d653dfe544a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun decryptCredentials(bytes: ByteArray): Map<String, Login> {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Key, "AES"), IvParameterSpec(bytes.copyOfRange(0, 16)))
        val text = cipher.doFinal(bytes, 16, bytes.size - 16).toString(Charsets.UTF_8)
        return json.parseToJsonElement(text).jsonObject.mapValues { (_, value) ->
            val entry = value as? JsonObject
            fun pair(key: String) = (entry?.get(key) as? JsonObject)?.let { it.string("user") to it.string("password") }
            Login(connection = pair("#connection"), ssh = pair("network/ssh_tunnel"))
        }
    }

    /** For tests: the inverse of [decryptCredentials]. */
    internal fun encryptCredentials(text: String, iv: ByteArray = ByteArray(16)): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Key, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(text.toByteArray())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
