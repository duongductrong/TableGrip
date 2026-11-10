package dev.tablegrip.core.golden

import dev.tablegrip.core.backup.BackupFrequency
import dev.tablegrip.core.backup.BackupSchedule
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DataFileOptions
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.InMemorySecretStore
import dev.tablegrip.core.connection.SshAuth
import dev.tablegrip.core.connection.SshConfig
import dev.tablegrip.core.history.HistoryEntry
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.queries.QueryLibrary
import dev.tablegrip.core.session.Session
import dev.tablegrip.core.session.SessionStore
import dev.tablegrip.core.session.SessionTab
import dev.tablegrip.core.session.WindowBounds
import dev.tablegrip.core.settings.KeyBinding
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.SettingsStore
import dev.tablegrip.core.settings.ThemeSelection
import dev.tablegrip.core.theme.SyntaxStyle
import dev.tablegrip.core.theme.ThemeFamily
import dev.tablegrip.core.theme.ThemeJson
import dev.tablegrip.core.theme.ThemeOverride
import dev.tablegrip.core.theme.ThemeSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes a sample `~/.tablegrip` with the real Kotlin stores into `conformance/core/fixtures/home/`, so the Rust port
 * (`tg-core`) can be held to reading and writing what Kotlin writes. Everything is fixed: ids, times and texts. There
 * are no secrets; passwords live in the keychain, never in these files.
 *
 * `-Pconformance.record=true` rewrites the fixture; without it the test fails when the stores would write something
 * else, so a change to a stored format can't go unnoticed.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class StorageFixtureTest {
    private val record = System.getProperty("conformance.record") == "true"

    @Test
    fun fixture() {
        val generated = Files.createTempDirectory("tablegrip-home")
        try {
            generate(generated)
            val committed = File(root, "fixtures/home").toPath()
            if (record) {
                if (Files.exists(committed)) committed.deleteRecursively()
                committed.createDirectories()
                for (file in generated.walk().filter { it.isRegularFile() }) {
                    val target = committed.resolve(file.relativeTo(generated).toString())
                    target.parent.createDirectories()
                    Files.copy(file, target)
                }
            } else {
                assertEquals(files(generated), files(committed), "Re-record with -Pconformance.record=true if the change is intended.")
            }
        } finally {
            generated.deleteRecursively()
        }
    }

    private fun files(dir: Path): Map<String, String> =
        if (!Files.isDirectory(dir)) emptyMap()
        else dir.walk().filter { it.isRegularFile() }.associate { it.relativeTo(dir).toString().replace('\\', '/') to String(it.readBytes(), Charsets.UTF_8) }

    private fun generate(home: Path) {
        connections(home)
        settings(home)
        theme(home)
        history(home)
        queries(home)
        session(home)
    }

    private fun connections(home: Path) {
        val file = home.resolve("connections.json")
        file.writeText(
            """[{"id":"future","name":"From the future","type":"QuantumDB","host":"q.example.com","extra":{"nested":[1,2,{"a":null}]}}]"""
        )
        val store = ConnectionStore(file, InMemorySecretStore())
        DatabaseType.entries.forEachIndexed { i, type ->
            val base = ConnectionConfig(id = "conn-%02d".format(i), name = "${type.displayName} sample", type = type)
            val config =
                when {
                    type.isDataFile ->
                        base.copy(
                            database = "/data/sample.${type.id.lowercase().removeSuffix("file")}",
                            dataFile = if (i % 2 == 0) DataFileOptions(delimiter = ';', hasHeader = false, charset = "windows-1252") else DataFileOptions(),
                        )
                    type.isFile -> base.copy(database = if (type == DatabaseType.DuckDB) ":memory:" else "/data/sample-$i.db", environment = Environment.Local)
                    type.connection == dev.tablegrip.core.connection.ConnectionKind.Url ->
                        base.copy(url = "jdbc:oracle:thin:@db.example.com:1521:orcl", user = "scott", driver = DriverId.Generic, driverJars = listOf("/jars/ojdbc11.jar", "/jars/orai18n.jar"))
                    else ->
                        base.copy(
                            host = "db$i.example.com",
                            port = type.defaultPort?.plus(i % 3),
                            database = "app_$i",
                            user = "user$i",
                            environment = Environment.entries[i % Environment.entries.size],
                            readOnly = i % 4 == 0,
                            confirmWrites = i % 3 == 0,
                            properties = if (i % 2 == 0) linkedMapOf("sslmode" to "require", "connectTimeout" to "10") else emptyMap(),
                            group = if (i % 5 == 0) "Client A" else if (i % 7 == 0) "Client B/Staging" else "",
                            favorite = i % 6 == 0,
                            aiAccess = when (i % 4) { 1 -> AiAccess.Off; 2 -> AiAccess.SchemaAndSamples; else -> null },
                        )
                }
            store.save(config, password = null)
        }
        // SSH: a key with a passphrase, the default keys or the agent, and a password.
        store.save(
            ConnectionConfig(
                id = "ssh-key", name = "Via bastion (key)", type = DatabaseType.PostgreSQL, host = "10.0.0.5", port = 5432, database = "prod", user = "app",
                environment = Environment.Production, readOnly = true,
                ssh = SshConfig(host = "bastion.example.com", port = 2222, user = "deploy", auth = SshAuth.PrivateKey, privateKeyPath = "~/.ssh/id_ed25519"),
            ),
            password = null,
        )
        store.save(
            ConnectionConfig(
                id = "ssh-agent", name = "Via bastion (agent)", type = DatabaseType.MySQL, host = "10.0.0.6", database = "shop", user = "app",
                ssh = SshConfig(host = "bastion.example.com", user = "deploy"),
            ),
            password = null,
        )
        store.save(
            ConnectionConfig(
                id = "ssh-password", name = "Via bastion (password)", type = DatabaseType.Redis, host = "cache.internal", user = "default",
                ssh = SshConfig(host = "jump.example.com", port = 22, user = "ops", auth = SshAuth.Password),
            ),
            password = null,
        )
        store.save(
            ConnectionConfig(
                id = "by-url", name = "Replicated MariaDB", type = DatabaseType.MariaDB, driver = DriverId.MariaDB,
                url = "jdbc:mariadb:replication://primary:3306,replica:3306/db?useSSL=false", user = "root", group = "Client A", favorite = true,
            ),
            password = null,
        )
        store.save(
            ConnectionConfig(id = "unicode", name = "Café \"quotes\" and \\ back\tslash ✓", type = DatabaseType.SQLite, database = "/Users/me/données.db"),
            password = null,
        )
    }

    private fun settings(home: Path) {
        SettingsStore(home.resolve("settings.json")).save(
            Settings(
                theme = ThemeSelection(mode = "Dark", dark = "One Dark", light = "One Light"),
                editorFontSize = 15,
                pageSize = 500,
                maxResultRows = 5_000,
                queryTimeoutSeconds = 30,
                filterMode = "Sql",
                driverRepository = "https://repo.example.com/maven",
                autoDownloadDrivers = true,
                agent = "claude",
                agentModels = linkedMapOf("claude" to "sonnet", "codex" to "gpt-5"),
                agentMode = "Provider",
                agentProvider = "OpenAi",
                agentBaseUrl = "https://llm.example.com/v1",
                agentModel = "gpt-5-mini",
                aiAccess = "Off",
                toolPaths = linkedMapOf("PgDump" to "/opt/pg/bin/pg_dump"),
                scheduledBackups = true,
                backupSchedules =
                    listOf(
                        BackupSchedule("sched-1", "conn-00", frequency = BackupFrequency.Hourly, keep = 24),
                        BackupSchedule("sched-2", "ssh-key", database = "prod", frequency = BackupFrequency.Weekly, folder = "/backups/prod", keep = 0, enabled = false, lastRun = 1_790_000_000_000L, lastError = "pg_dump: connection refused"),
                        BackupSchedule("sched-3", "conn-03"),
                    ),
                accentColor = "#D95C9A",
                themeOverrides =
                    linkedMapOf(
                        "One Dark" to ThemeOverride(colors = linkedMapOf("accent" to "#E5C07B"), syntax = linkedMapOf("keyword" to SyntaxStyle("#FF79C6"), "comment" to SyntaxStyle("#5C6370", fontStyle = "italic")), chart = listOf("#E5C07B", "#61AFEF")),
                        "Empty" to ThemeOverride(),
                    ),
                uiFontSize = 14,
                islandCornerRadius = 6,
                zoom = 125,
                syntaxScheme = "Darcula",
                syntaxColors = linkedMapOf("dark.keyword" to "#FF0000", "light.string" to "#00AA00"),
                environmentColors = linkedMapOf("Production" to "#CC0000"),
                editorFontFamily = "Fira Code",
                editorLineHeight = 140,
                tabSize = 2,
                indentWithTabs = true,
                autoCloseBrackets = false,
                completionWhileTyping = false,
                highlightCurrentLine = false,
                showLineNumbers = false,
                formatKeywordCase = "Lower",
                gridDensity = "Compact",
                nullText = "∅",
                dateTimeFormat = "yyyy-MM-dd HH:mm",
                dateTimeZone = "Utc",
                numberGrouping = true,
                zebraRows = true,
                keymap = "VsCode",
                keyBindings = linkedMapOf("Format" to KeyBinding(76, "L", primary = true, shift = true), "Run" to KeyBinding(10, "⏎", ctrl = true, alt = true)),
                rememberWindowBounds = false,
                rememberPanelSizes = false,
                restoreTabs = false,
                startupTool = "Explorer",
                startupAgent = "LastUsed",
                statusBreadcrumbs = false,
                statusSelectionStats = false,
                stripeHidden = listOf("Queries", "Activity"),
                stripeOrder = listOf("Explorer", "Search"),
                checkForUpdates = false,
                installUpdatesAutomatically = false,
                updateChannel = "Beta",
            )
        )
    }

    private fun theme(home: Path) {
        val themes = home.resolve("themes").createDirectories()
        val family =
            ThemeFamily(
                name = "Mine",
                author = "Fixture",
                license = "MIT",
                themes =
                    listOf(
                        ThemeSpec(
                            name = "Mine Dark",
                            colors = linkedMapOf("accent" to "#FF8800", "island.background" to "#1E1F22", "some.future.key" to "#000000"),
                            syntax = linkedMapOf("keyword" to SyntaxStyle("#C678DD"), "comment" to SyntaxStyle("#5C6370", fontStyle = "italic"), "string" to SyntaxStyle(fontWeight = "bold")),
                            chart = listOf("#4D78CC", "#E5C07B"),
                        ),
                        ThemeSpec(name = "Mine Light", appearance = "light"),
                    ),
            )
        themes.resolve("mine.json").writeText(ThemeJson.encode(family))
    }

    private fun history(home: Path) {
        val history = QueryHistory(home.resolve("history.jsonl"))
        history.append(HistoryEntry("conn-00", "SELECT 1", executedAt = 1_790_000_000_000L, elapsedMillis = 3))
        history.append(HistoryEntry("conn-00", "SELECT *\nFROM \"users\"\nWHERE name = 'Zoë' -- ✓", executedAt = 1_790_000_001_000L, elapsedMillis = 42))
        history.append(HistoryEntry("ssh-key", "UPDATE t SET a = 1", executedAt = 1_790_000_002_000L, elapsedMillis = 0, error = "permission denied for table t"))
    }

    private fun queries(home: Path) {
        val library = QueryLibrary(home.resolve("queries"))
        library.save("Top users", "", "SELECT * FROM users ORDER BY score DESC LIMIT 10;\n", connection = null)
        library.save("monthly", "reports/finance", "SELECT date_trunc('month', at), sum(amount)\nFROM payments\nGROUP BY 1;\n", connection = "Via bastion (key)")
        library.save("Zoë's check", "reports", "-- no connection\nSELECT 1;\n", connection = null)
    }

    private fun session(home: Path) {
        SessionStore(home.resolve("session.json")).save(
            Session(
                tabs =
                    listOf(
                        SessionTab.Console("conn-00", "SELECT 1;\nSELECT 2;\n", file = "/q/a.sql", caret = 9, savedText = "SELECT 1;"),
                        SessionTab.Console("ssh-key", ""),
                        SessionTab.Table("conn-00", catalog = "app", schema = "public", name = "orders", structure = true, filter = "status = 'paid'"),
                        SessionTab.Table("conn-01", name = "users"),
                    ),
                selected = 2,
                leftTool = null,
                connectionId = "conn-00",
                agentOpen = true,
                agentChat = "chat-1",
                window = WindowBounds(120.5f, 80f, 1440f, 900.25f),
                sidebarSplit = 0.25f,
                agentSplit = 0.7f,
            )
        )
    }

    private companion object {
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "conformance/core") }.firstOrNull { it.isDirectory }
                ?: error("conformance/core not found above ${File("").absolutePath}")
        }
    }
}
