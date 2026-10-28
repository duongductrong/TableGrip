package dev.datagrok.core.driver

import dev.datagrok.core.connection.ConnectionConfig
import dev.datagrok.core.connection.Paradigm
import dev.datagrok.core.connection.SshTunnel
import dev.datagrok.core.drivers.DriverLibrary
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens [KeyValueConnection]s: the profile's client adapter, through its SSH tunnel, with read-only enforced here. */
object KeyValueStore {
    suspend fun connect(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): KeyValueConnection =
        withContext(Dispatchers.IO) {
            require(config.type.paradigm == Paradigm.KeyValue) { "${config.type.displayName} isn't a key-value store" }
            val client = drivers.loadClient(config.effectiveDriver) as KeyValueClient
            val tunnel = config.ssh?.let { ssh -> SshTunnel.open(ssh, sshSecret, config.host.trim(), config.port ?: defaultPort(config)) }
            try {
                val host = if (tunnel != null) "127.0.0.1" else config.host.trim().ifEmpty { "localhost" }
                val port = tunnel?.localPort ?: config.port ?: defaultPort(config)
                Guarded(client.connect(config, host, port, password), config.readOnly) { tunnel?.close() }
            } catch (e: Throwable) {
                tunnel?.close()
                throw e
            }
        }

    /** Connects and disconnects, e.g. for "Test Connection"; returns the server's description. */
    suspend fun test(config: ConnectionConfig, password: String?, sshSecret: String? = null, drivers: DriverLibrary = DriverLibrary.shared): String =
        connect(config, password, sshSecret, drivers).use { it.server() }

    private fun defaultPort(config: ConnectionConfig) = config.type.defaultPort ?: throw SQLException("${config.type.displayName} needs a port.")

    /**
     * Splits a console line into arguments the way redis-cli does: spaces separate, double quotes allow escapes
     * (`\n`, `\"`, `\xHH`), single quotes are literal.
     */
    fun splitArguments(line: String): List<String> {
        val arguments = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i].isWhitespace()) i++
            if (i >= line.length) break
            val current = StringBuilder()
            var quote: Char? = null
            while (i < line.length) {
                val c = line[i]
                when {
                    quote == null && c.isWhitespace() -> break
                    quote == null && (c == '"' || c == '\'') -> quote = c
                    quote != null && c == quote -> quote = null
                    quote == '"' && c == '\\' && i + 1 < line.length -> {
                        i++
                        when (val e = line[i]) {
                            'n' -> current.append('\n')
                            'r' -> current.append('\r')
                            't' -> current.append('\t')
                            'x' -> line.substring(i + 1, minOf(i + 3, line.length)).toIntOrNull(16)?.let {
                                current.append(it.toChar())
                                i += 2
                            } ?: current.append('x')
                            else -> current.append(e)
                        }
                    }
                    else -> current.append(c)
                }
                i++
            }
            if (quote != null) throw IllegalArgumentException("Unbalanced quotes")
            arguments += current.toString()
        }
        return arguments
    }

    /**
     * Commands that only read, which is what a read-only profile may run from the console. Anything not listed is
     * refused there: a missing read command costs a little convenience, a missing write command would cost data.
     */
    val ReadCommands =
        setOf(
            "GET", "MGET", "GETRANGE", "STRLEN", "EXISTS", "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME", "KEYS", "SCAN", "RANDOMKEY",
            "DBSIZE", "INFO", "PING", "ECHO", "TIME", "LASTSAVE", "ROLE", "MEMORY", "OBJECT", "DUMP", "LCS",
            "HGET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HLEN", "HEXISTS", "HSTRLEN", "HSCAN", "HRANDFIELD", "HTTL", "HPTTL",
            "LRANGE", "LINDEX", "LLEN", "LPOS",
            "SMEMBERS", "SISMEMBER", "SMISMEMBER", "SCARD", "SSCAN", "SRANDMEMBER", "SINTER", "SUNION", "SDIFF", "SINTERCARD",
            "ZRANGE", "ZRANGEBYSCORE", "ZREVRANGE", "ZREVRANGEBYSCORE", "ZRANGEBYLEX", "ZSCORE", "ZMSCORE", "ZCARD", "ZCOUNT", "ZRANK",
            "ZREVRANK", "ZSCAN", "ZRANDMEMBER", "ZLEXCOUNT", "ZINTER", "ZUNION", "ZDIFF",
            "XRANGE", "XREVRANGE", "XLEN", "XINFO", "XPENDING",
            "PFCOUNT", "GETBIT", "BITCOUNT", "BITPOS", "GEOPOS", "GEODIST", "GEOHASH", "GEOSEARCH",
            "JSON.GET", "JSON.MGET", "JSON.TYPE", "JSON.STRLEN", "JSON.ARRLEN", "JSON.OBJKEYS", "JSON.OBJLEN",
            "CLIENT", "CLUSTER", "COMMAND", "SLOWLOG", "LATENCY", "SELECT",
        )

    /** Applies the profile's read-only setting, and closes the tunnel with the connection. */
    private class Guarded(private val inner: KeyValueConnection, private val readOnly: Boolean, private val onClose: () -> Unit) :
        KeyValueConnection by inner {
        private fun refuse(what: String): Nothing = throw ReadOnlyViolationException(what)

        override suspend fun edit(keyspace: Int, key: String, edit: KeyEdit) = if (readOnly) refuse("editing keys") else inner.edit(keyspace, key, edit)

        override suspend fun delete(keyspace: Int, keys: List<String>): Long = if (readOnly) refuse("deleting keys") else inner.delete(keyspace, keys)

        override suspend fun rename(keyspace: Int, from: String, to: String) = if (readOnly) refuse("renaming keys") else inner.rename(keyspace, from, to)

        override suspend fun expire(keyspace: Int, key: String, ttlMillis: Long?) = if (readOnly) refuse("changing TTL") else inner.expire(keyspace, key, ttlMillis)

        override suspend fun command(keyspace: Int, arguments: List<String>): String {
            val name = arguments.firstOrNull()?.uppercase() ?: return ""
            if (readOnly && name !in ReadCommands) refuse(name)
            return inner.command(keyspace, arguments)
        }

        override fun close() {
            try {
                inner.close()
            } finally {
                onClose()
            }
        }
    }
}
