package dev.tablegrip.mcp

import dev.tablegrip.core.agent.AgentCapability
import dev.tablegrip.core.agent.AgentDecision
import dev.tablegrip.core.agent.AgentPolicy
import dev.tablegrip.core.connection.AiAccess
import dev.tablegrip.core.connection.ConnectionConfig
import dev.tablegrip.core.connection.ConnectionOpener
import dev.tablegrip.core.connection.ConnectionStore
import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverLibrary
import dev.tablegrip.core.drivers.DriverResolution
import dev.tablegrip.core.drivers.LicenseTier
import dev.tablegrip.core.driver.DatabaseConnection
import dev.tablegrip.core.driver.QuerySession
import dev.tablegrip.core.driver.RelationalConnection
import dev.tablegrip.core.history.HistoryEntry
import dev.tablegrip.core.history.QueryHistory
import dev.tablegrip.core.queries.QueryLibrary
import dev.tablegrip.core.settings.Settings
import dev.tablegrip.core.settings.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Everything the tools share in one server process: the profiles and settings (read fresh on each call, so changes made
 * in the app apply at once), the policy built from them, and the live connections.
 */
class Workspace(
    val options: McpOptions,
    private val connections: ConnectionStore = ConnectionStore(),
    private val settingsStore: SettingsStore = SettingsStore(),
    val drivers: DriverLibrary = DriverLibrary.shared,
    val history: QueryHistory = QueryHistory(),
    val library: QueryLibrary = QueryLibrary(),
    val audit: AuditLog = AuditLog(),
    /** Closes a connection nobody used for this long. */
    private val idleMillis: Long = 5 * 60_000,
) : AutoCloseable {
    private val live = ConcurrentHashMap<String, Live>()

    fun settings(): Settings = settingsStore.load()

    fun policy(settings: Settings = settings()): AgentPolicy =
        AgentPolicy(settings.mcpEnabled, AiAccess.of(settings.aiAccess), forceReadOnly = options.readOnly, only = options.connections)

    /** Rows per result: the setting, lowered by `--max-rows`, never above 5,000. */
    fun maxRows(settings: Settings = settings()): Int = listOfNotNull(settings.mcpMaxRows.coerceIn(1, MaxRows), options.maxRows).min()

    /** Seconds per statement; 0 is no limit. */
    fun timeoutSeconds(settings: Settings = settings()): Int {
        val configured = settings.mcpTimeoutSeconds.coerceAtLeast(0)
        val flag = options.timeoutSeconds ?: return configured
        return if (configured == 0) flag else minOf(configured, flag)
    }

    fun resultBudget(settings: Settings = settings()): Int = settings.mcpResultBudget.coerceIn(4_000, 1_000_000)

    /** The profiles agents may see. */
    fun visible(): List<ConnectionConfig> {
        val policy = policy()
        return connections.load().filter(policy::visible)
    }

    /** A visible profile by id, exact name, or name in any case. */
    fun resolve(reference: String): ConnectionConfig {
        val policy = policy()
        if (!policy.enabled) throw ToolException(AgentPolicy.Disabled)
        val all = connections.load().filter(policy::visible)
        return all.firstOrNull { it.id == reference }
            ?: all.firstOrNull { it.name == reference }
            ?: all.filter { it.name.equals(reference, ignoreCase = true) }.singleOrNull()
            ?: throw ToolException("There is no connection '$reference' available to agents. Call list_connections to see the ones there are.")
    }

    /** Throws [ToolException] unless the policy allows [capability] on [config]. */
    fun require(capability: AgentCapability, config: ConnectionConfig) {
        when (val decision = policy().decide(capability, config)) {
            AgentDecision.Allowed -> Unit
            is AgentDecision.Denied -> throw ToolException(decision.reason)
        }
    }

    /**
     * Runs [block] with the live connection to [config], opening it first if needed. One call at a time per connection:
     * agents send bursts, and a database connection runs one statement at a time anyway.
     */
    suspend fun <T> use(config: ConnectionConfig, block: suspend (Live) -> T): T {
        // What actually connects: read-only whenever agents may not write, so the driver and TableGrip's own guards refuse
        // writes too, not only the checks in the tools.
        val effective = config.copy(readOnly = config.readOnly || !policy().writes(config).allowsData)
        val entry = live.computeIfAbsent(config.id) { Live(it) }
        return entry.mutex.withLock {
            entry.lastUsed = System.currentTimeMillis()
            if (entry.connection != null && entry.config != effective) entry.closeQuietly()
            if (entry.connection == null) {
                entry.connection = open(effective)
                entry.config = effective
            }
            try {
                block(entry)
            } finally {
                entry.lastUsed = System.currentTimeMillis()
            }
        }
    }

    suspend fun <T> relational(config: ConnectionConfig, block: suspend (Live, RelationalConnection) -> T): T =
        use(config) { entry ->
            val database = entry.connection as? RelationalConnection ?: throw ToolException("'${config.name}' is a ${config.type.displayName} connection, which has no tables or SQL.")
            block(entry, database)
        }

    private suspend fun open(config: ConnectionConfig): DatabaseConnection {
        val settings = settings()
        drivers.repository = settings.driverRepository
        ensureDriver(config)
        return try {
            ConnectionOpener.open(config, { connections.password(config.id) }, { connections.sshSecret(config.id) }, drivers)
        } catch (e: Exception) {
            log.warn("Couldn't connect to {}", config.name, e)
            throw ToolException("Couldn't connect to '${config.name}': ${e.message ?: e}")
        }
    }

    private suspend fun ensureDriver(config: ConnectionConfig) {
        val missing = "The ${config.effectiveDriver.displayName} driver isn't installed on this computer."
        when (val resolution = drivers.resolve(config)) {
            is DriverResolution.Ready -> Unit
            is DriverResolution.BringYourOwn ->
                throw ToolException("$missing Its jar has to be chosen in TableGrip: edit the connection '${config.name}' there.")
            is DriverResolution.Download ->
                if (options.allowDriverDownload && resolution.locked.tier == LicenseTier.Permissive && config.effectiveDriver != DriverId.Generic) {
                    withContext(Dispatchers.IO) { drivers.install(config.effectiveDriver) }
                } else {
                    throw ToolException("$missing Connect to '${config.name}' once in TableGrip to download it, then try again.")
                }
        }
    }

    /** Closes connections left idle, until [scope] ends. */
    fun startReaper(scope: CoroutineScope) =
        scope.launch {
            while (isActive) {
                delay(30_000)
                val now = System.currentTimeMillis()
                live.values.filter { now - it.lastUsed > idleMillis }.forEach { entry ->
                    if (entry.mutex.tryLock()) {
                        try {
                            entry.closeQuietly()
                        } finally {
                            entry.mutex.unlock()
                        }
                    }
                }
            }
        }

    /** Records a statement in TableGrip's query history, marked with the client that ran it. */
    fun remember(config: ConnectionConfig, sql: String, startedAt: Long, error: String?, client: String) {
        runCatching {
            history.append(HistoryEntry(config.id, sql, startedAt, System.currentTimeMillis() - startedAt, error, source = "mcp:$client"))
        }.onFailure { log.warn("Couldn't write the query history", it) }
    }

    override fun close() {
        live.values.forEach { it.closeQuietly() }
        live.clear()
    }

    /** One connection's live state; guarded by [mutex]. */
    class Live(val id: String) {
        val mutex = Mutex()
        var config: ConnectionConfig? = null
        var connection: DatabaseConnection? = null
        private var session: QuerySession? = null

        @Volatile
        var lastUsed: Long = System.currentTimeMillis()

        /** The console-like session statements run in, reopened when the server dropped it. */
        suspend fun session(): QuerySession {
            session?.let { if (it.isValid()) return it else runCatching { it.close() } }
            val database = connection as? RelationalConnection ?: throw ToolException("This connection has no SQL sessions.")
            return database.openSession().also { session = it }
        }

        fun closeQuietly() {
            runCatching { session?.close() }
            runCatching { connection?.close() }
            session = null
            connection = null
            config = null
        }
    }

    companion object {
        const val MaxRows = 5_000
        private val log = LoggerFactory.getLogger(Workspace::class.java)
    }
}
