package dev.tablegrip.core.imports

import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.SshAuth
import dev.tablegrip.core.connection.SshConfig
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.w3c.dom.Element

/**
 * Data sources of IDE database tools: each project's `.idea/dataSources.xml` (the
 * shared part: name, driver, JDBC URL) and `dataSources.local.xml` (user name, SSH, read-only), plus the IDE-wide SSH
 * configurations they refer to. Projects are found through each IDE's recent projects list. Passwords stay in the
 * IDE's own credential store.
 */
internal object IdeDataSourcesImporter {

    /** The IDEs' configuration folders, newest version of each product last so it wins when they disagree. */
    private fun configDirs(home: Path): List<Path> {
        val base =
            listOf(
                home.resolve("Library/Application Support/JetBrains"),
                home.resolve(".config/JetBrains"),
                System.getenv("APPDATA")?.let { Path.of(it, "JetBrains") },
            ).filterNotNull().filter { it.isDirectory() }
        return base.flatMap { root -> root.listDirectoryEntries().filter { it.isDirectory() && it.resolve("options").isDirectory() }.sorted() }
    }

    /** `dataSources.xml` of every recently opened project, and the IDEs' global data sources. */
    fun locations(home: Path): List<Path> {
        val files = linkedSetOf<Path>()
        for (dir in configDirs(home)) {
            dir.resolve("options/dataSources.xml").takeIf { it.isRegularFile() }?.let(files::add)
            val recent = dir.resolve("options/recentProjects.xml").takeIf { it.isRegularFile() } ?: continue
            val document = runCatching { parse(recent) }.getOrNull() ?: continue
            for (entry in document.elements("entry")) {
                val key = entry.getAttribute("key").ifEmpty { continue }
                val project = Path.of(key.replace("\$USER_HOME\$", home.toString()))
                project.resolve(".idea/dataSources.xml").takeIf { it.isRegularFile() }?.let { files.add(it.normalize()) }
            }
        }
        return files.toList()
    }

    fun read(file: Path, home: Path): ImportScan {
        val shared = parse(file)
        val local = file.resolveSibling(file.name.replace("dataSources", "dataSources.local")).takeIf { it.isRegularFile() }?.let(::parse)
        val localById = local?.elements("data-source")?.associateBy { it.getAttribute("uuid") }.orEmpty()
        val sshConfigs = sshConfigs(home)
        // `$PROJECT_DIR$` points at the folder holding `.idea`; global data sources have none.
        val projectDir = file.parent?.takeIf { it.name == ".idea" }?.parent
        val candidates = mutableListOf<ImportCandidate>()
        val skipped = mutableListOf<String>()
        for (source in shared.elements("data-source")) {
            val name = source.getAttribute("name")
            val uuid = source.getAttribute("uuid")
            val details = localById[uuid]
            val rawUrl = source.child("jdbc-url") ?: run {
                skipped += "IDE data sources: “$name” has no JDBC URL."
                continue
            }
            val url = expandMacros(rawUrl, projectDir, home)
            val parsed = ConnectionImport.parseOrNull(url)
            if (parsed == null) {
                skipped += "IDE data sources: “$name” isn't supported ($url)."
                continue
            }
            val type = refine(parsed.type, source.child("driver-ref"), details?.firstElement("database-info")?.getAttribute("dbms"))
            val ssh = details?.firstElement("ssh-properties")?.takeIf { it.child("enabled") == "true" }?.child("ssh-config-id")?.let(sshConfigs::get)
            val config =
                ConnectionImport.fromParsed(
                    parsed.copy(user = details?.child("user-name") ?: parsed.user),
                    name = name,
                    group = source.getAttribute("group").ifEmpty { projectDir?.name.orEmpty() },
                    readOnly = details?.child("read-only") == "true" || source.child("read-only") == "true",
                    ssh = ssh,
                    type = type,
                )
            val notes = buildList {
                addAll(parsed.warnings)
                if (type == DatabaseType.OtherJdbc) add("Choose the driver's jar when connecting")
            }
            candidates += ImportCandidate(config, password = parsed.password, origin = file.toString(), notes = notes)
        }
        return ImportScan(candidates, skipped)
    }

    /** The JDBC URL says the protocol; the IDE's driver and the server it saw say which engine of the family it is. */
    private fun refine(type: DatabaseType, driverRef: String?, dbms: String?): DatabaseType {
        val hint =
            when (dbms?.uppercase() ?: driverRef?.substringBefore('.')?.uppercase()) {
                "MARIADB" -> DatabaseType.MariaDB
                "COCKROACH", "COCKROACHDB" -> DatabaseType.CockroachDB
                "TIDB" -> DatabaseType.TiDB
                "AZURE", "AZURESQL" -> DatabaseType.AzureSql
                "VALKEY" -> DatabaseType.Valkey
                else -> null
            }
        return if (hint != null && hint.family == type.family) hint else type
    }

    private fun expandMacros(url: String, projectDir: Path?, home: Path): String =
        url.replace("\$USER_HOME\$", home.toString())
            .let { if (projectDir != null) it.replace("\$PROJECT_DIR\$", projectDir.toString()).replace("\$ProjectFileDir\$", projectDir.toString()) else it }

    /** The IDE-wide SSH configurations, by id. */
    private fun sshConfigs(home: Path): Map<String, SshConfig> =
        configDirs(home).mapNotNull { it.resolve("options/sshConfigs.xml").takeIf { f -> f.isRegularFile() } }.flatMap { file ->
            runCatching { parse(file) }.getOrNull()?.elements("sshConfig").orEmpty().mapNotNull { element ->
                val id = element.getAttribute("id").ifEmpty { return@mapNotNull null }
                val host = element.getAttribute("host").ifEmpty { return@mapNotNull null }
                val auth = element.getAttribute("authType")
                id to SshConfig(
                    host = host,
                    port = element.getAttribute("port").toIntOrNull() ?: 22,
                    user = element.getAttribute("username").ifEmpty { System.getProperty("user.name") },
                    auth = if (auth == "PASSWORD") SshAuth.Password else SshAuth.PrivateKey,
                    privateKeyPath = element.getAttribute("keyPath").ifEmpty { null }?.replace("\$USER_HOME\$", home.toString()),
                )
            }
        }.toMap()

    private fun parse(file: Path): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // These are the user's own files, but there is no reason to resolve anything external in them.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(file.toFile()).documentElement
    }

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.firstElement(tag: String): Element? = elements(tag).firstOrNull()

    private fun Element.child(tag: String): String? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == tag) return node.textContent.trim()
        }
        return null
    }
}
