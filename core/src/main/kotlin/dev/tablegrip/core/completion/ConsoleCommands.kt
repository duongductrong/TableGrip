package dev.tablegrip.core.completion

import dev.tablegrip.core.driver.KeyValueStore

/** The command catalogs of the key-value consoles. */
object ConsoleCommands {
    /**
     * Redis commands (also Valkey, KeyDB, Dragonfly) that make sense one line at a time; blocking subscriptions and
     * transactions, which need a connection of their own, are left out. A command reads when a read-only profile may
     * run it, as [KeyValueStore.ReadCommands] decides.
     */
    val Redis: CommandCatalog by lazy {
        CommandCatalog(
            commands = parse(RedisTable) { name -> name.substringBefore(' ').uppercase() in KeyValueStore.ReadCommands || name.equals("CLEAR", true) },
            upperCase = true,
            keyParameters = setOf("key", "key1", "key2", "source", "destination", "destkey", "sourcekey"),
            patternParameters = setOf("pattern"),
            values =
                mapOf(
                    "type" to listOf("string", "list", "set", "zset", "hash", "stream", "ReJSON-RL").map { it to "type" },
                    "section" to
                        listOf(
                            "server" to "version, uptime", "clients" to "connections", "memory" to "memory use", "persistence" to "RDB and AOF",
                            "stats" to "general statistics", "replication" to "primary/replica", "cpu" to "CPU use", "commandstats" to "per-command stats",
                            "latencystats" to "latency percentiles", "keyspace" to "keys per database", "modules" to "loaded modules", "errorstats" to "errors",
                            "cluster" to "cluster state", "all" to "all sections", "everything" to "all sections and modules",
                        ),
                    "parameter" to
                        listOf(
                            "maxmemory", "maxmemory-policy", "maxclients", "timeout", "databases", "save", "appendonly", "appendfsync", "dir", "dbfilename",
                            "notify-keyspace-events", "slowlog-log-slower-than", "slowlog-max-len", "lazyfree-lazy-eviction", "hz", "requirepass", "bind", "port",
                            "loglevel", "protected-mode", "*",
                        ).map { it to "parameter" },
                    "unit" to listOf("m", "km", "ft", "mi").map { it to "unit" },
                ),
        )
    }

    /** etcdctl's commands as the etcd console runs them. */
    val Etcd: CommandCatalog by lazy {
        CommandCatalog(
            commands = parse(EtcdTable) { name -> KeyValueStore.isEtcdRead(name.split(' ')) || name == "clear" },
            upperCase = false,
            keyParameters = setOf("key"),
            flagsAnywhere = true,
        )
    }

    private fun parse(table: String, reads: (String) -> Boolean): List<CommandSpec> =
        table.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val (name, syntax, summary) = line.split(" | ").map { it.trim() }.let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            CommandSpec(name, syntax, summary, reads(name))
        }

    // NAME | syntax | summary
    private val RedisTable =
        """
        GET | key | the value of a key
        SET | key value [NX|XX] [GET] [EX seconds|PX milliseconds|EXAT unix-time-seconds|PXAT unix-time-milliseconds|KEEPTTL] | set a string value
        SETNX | key value | set unless the key exists
        SETEX | key seconds value | set with a TTL in seconds
        PSETEX | key milliseconds value | set with a TTL in milliseconds
        GETSET | key value | set and return the old value
        GETDEL | key | get and delete
        GETEX | key [EX seconds|PX milliseconds|EXAT unix-time-seconds|PXAT unix-time-milliseconds|PERSIST] | get and change the TTL
        MGET | key [key ...] | the values of several keys
        MSET | key value [key value ...] | set several keys
        MSETNX | key value [key value ...] | set several keys unless any exists
        APPEND | key value | append to a string
        STRLEN | key | the length of a string
        INCR | key | add 1
        INCRBY | key increment | add an integer
        INCRBYFLOAT | key increment | add a float
        DECR | key | subtract 1
        DECRBY | key decrement | subtract an integer
        GETRANGE | key start end | a substring
        SETRANGE | key offset value | overwrite part of a string
        LCS | key1 key2 [LEN] [IDX] [MINMATCHLEN min-match-len] [WITHMATCHLEN] | longest common substring
        DEL | key [key ...] | delete keys
        UNLINK | key [key ...] | delete keys in the background
        EXISTS | key [key ...] | how many of the keys exist
        TYPE | key | the type of a key
        TTL | key | seconds to live
        PTTL | key | milliseconds to live
        EXPIRE | key seconds [NX|XX|GT|LT] | set a TTL in seconds
        PEXPIRE | key milliseconds [NX|XX|GT|LT] | set a TTL in milliseconds
        EXPIREAT | key unix-time-seconds [NX|XX|GT|LT] | expire at a time
        PEXPIREAT | key unix-time-milliseconds [NX|XX|GT|LT] | expire at a time in ms
        EXPIRETIME | key | when a key expires
        PEXPIRETIME | key | when a key expires, in ms
        PERSIST | key | remove the TTL
        RENAME | key newkey | rename a key
        RENAMENX | key newkey | rename unless the new name exists
        COPY | source destination [DB destination-db] [REPLACE] | copy a key
        MOVE | key db | move a key to another database
        DUMP | key | serialize a key
        RESTORE | key ttl serialized-value [REPLACE] [ABSTTL] [IDLETIME seconds] [FREQ frequency] | create a key from DUMP
        KEYS | pattern | keys matching a pattern (blocks the server)
        SCAN | cursor [MATCH pattern] [COUNT count] [TYPE type] | iterate keys
        RANDOMKEY |  | a random key
        TOUCH | key [key ...] | update last access time
        SORT | key [BY pattern] [LIMIT offset count] [GET pattern] [ASC|DESC] [ALPHA] [STORE destination] | sort a list, set or sorted set
        SORT_RO | key [BY pattern] [LIMIT offset count] [GET pattern] [ASC|DESC] [ALPHA] | sort, read-only
        OBJECT ENCODING | key | internal encoding
        OBJECT FREQ | key | access frequency
        OBJECT IDLETIME | key | seconds since last access
        OBJECT REFCOUNT | key | reference count
        HGET | key field | a hash field's value
        HSET | key field value [field value ...] | set hash fields
        HSETNX | key field value | set a field unless it exists
        HMSET | key field value [field value ...] | set hash fields
        HMGET | key field [field ...] | several fields' values
        HGETALL | key | all fields and values
        HDEL | key field [field ...] | delete fields
        HEXISTS | key field | whether a field exists
        HKEYS | key | all field names
        HVALS | key | all values
        HLEN | key | number of fields
        HSTRLEN | key field | length of a field's value
        HINCRBY | key field increment | add an integer to a field
        HINCRBYFLOAT | key field increment | add a float to a field
        HSCAN | key cursor [MATCH pattern] [COUNT count] [NOVALUES] | iterate fields
        HRANDFIELD | key [count [WITHVALUES]] | random fields
        HEXPIRE | key seconds [NX|XX|GT|LT] FIELDS numfields field [field ...] | TTL for fields
        HTTL | key FIELDS numfields field [field ...] | fields' TTL
        HPERSIST | key FIELDS numfields field [field ...] | remove fields' TTL
        LPUSH | key element [element ...] | prepend to a list
        RPUSH | key element [element ...] | append to a list
        LPUSHX | key element [element ...] | prepend if the list exists
        RPUSHX | key element [element ...] | append if the list exists
        LPOP | key [count] | remove the first elements
        RPOP | key [count] | remove the last elements
        LRANGE | key start stop | a range of elements
        LINDEX | key index | an element by index
        LLEN | key | list length
        LSET | key index element | set an element
        LINSERT | key BEFORE|AFTER pivot element | insert next to an element
        LREM | key count element | remove elements
        LTRIM | key start stop | keep a range
        LPOS | key element [RANK rank] [COUNT num-matches] [MAXLEN len] | index of an element
        LMOVE | source destination LEFT|RIGHT LEFT|RIGHT | move an element between lists
        RPOPLPUSH | source destination | move the last element to another list
        LMPOP | numkeys key [key ...] LEFT|RIGHT [COUNT count] | pop from the first non-empty list
        SADD | key member [member ...] | add members
        SREM | key member [member ...] | remove members
        SMEMBERS | key | all members
        SISMEMBER | key member | whether it's a member
        SMISMEMBER | key member [member ...] | whether each is a member
        SCARD | key | number of members
        SPOP | key [count] | remove random members
        SRANDMEMBER | key [count] | random members
        SINTER | key [key ...] | intersection
        SUNION | key [key ...] | union
        SDIFF | key [key ...] | difference
        SINTERCARD | numkeys key [key ...] [LIMIT limit] | size of the intersection
        SINTERSTORE | destination key [key ...] | store the intersection
        SUNIONSTORE | destination key [key ...] | store the union
        SDIFFSTORE | destination key [key ...] | store the difference
        SMOVE | source destination member | move a member
        SSCAN | key cursor [MATCH pattern] [COUNT count] | iterate members
        ZADD | key [NX|XX] [GT|LT] [CH] [INCR] score member [score member ...] | add members with scores
        ZREM | key member [member ...] | remove members
        ZSCORE | key member | a member's score
        ZMSCORE | key member [member ...] | several members' scores
        ZINCRBY | key increment member | add to a score
        ZCARD | key | number of members
        ZCOUNT | key min max | members within a score range
        ZRANGE | key start stop [BYSCORE|BYLEX] [REV] [LIMIT offset count] [WITHSCORES] | a range of members
        ZRANGEBYSCORE | key min max [WITHSCORES] [LIMIT offset count] | members by score
        ZREVRANGE | key start stop [WITHSCORES] | a range, highest first
        ZREVRANGEBYSCORE | key max min [WITHSCORES] [LIMIT offset count] | members by score, highest first
        ZRANGEBYLEX | key min max [LIMIT offset count] | members by name
        ZLEXCOUNT | key min max | members within a name range
        ZRANK | key member [WITHSCORE] | a member's rank
        ZREVRANK | key member [WITHSCORE] | a member's rank, highest first
        ZPOPMIN | key [count] | remove the lowest
        ZPOPMAX | key [count] | remove the highest
        ZRANDMEMBER | key [count [WITHSCORES]] | random members
        ZREMRANGEBYRANK | key start stop | remove by rank
        ZREMRANGEBYSCORE | key min max | remove by score
        ZINTER | numkeys key [key ...] [WEIGHTS weight] [AGGREGATE SUM|MIN|MAX] [WITHSCORES] | intersection
        ZUNION | numkeys key [key ...] [WEIGHTS weight] [AGGREGATE SUM|MIN|MAX] [WITHSCORES] | union
        ZDIFF | numkeys key [key ...] [WITHSCORES] | difference
        ZINTERSTORE | destination numkeys key [key ...] [WEIGHTS weight] [AGGREGATE SUM|MIN|MAX] | store the intersection
        ZUNIONSTORE | destination numkeys key [key ...] [WEIGHTS weight] [AGGREGATE SUM|MIN|MAX] | store the union
        ZSCAN | key cursor [MATCH pattern] [COUNT count] | iterate members
        XADD | key [NOMKSTREAM] [MAXLEN|MINID threshold] *|id field value [field value ...] | append an entry
        XRANGE | key start end [COUNT count] | entries in an ID range
        XREVRANGE | key end start [COUNT count] | entries, newest first
        XLEN | key | number of entries
        XDEL | key id [id ...] | delete entries
        XTRIM | key MAXLEN|MINID threshold [LIMIT count] | trim the stream
        XREAD | [COUNT count] STREAMS key [key ...] | read entries
        XACK | key group id [id ...] | acknowledge entries
        XPENDING | key group [start end count] | pending entries
        XINFO STREAM | key [FULL] | stream details
        XINFO GROUPS | key | consumer groups
        XINFO CONSUMERS | key group | consumers of a group
        XGROUP CREATE | key group id [MKSTREAM] | create a consumer group
        XGROUP DESTROY | key group | delete a consumer group
        XGROUP SETID | key group id | set a group's last ID
        XAUTOCLAIM | key group consumer min-idle-time start [COUNT count] [JUSTID] | claim idle entries
        PFADD | key [element [element ...]] | add to a HyperLogLog
        PFCOUNT | key [key ...] | approximate distinct count
        PFMERGE | destkey [sourcekey [sourcekey ...]] | merge HyperLogLogs
        SETBIT | key offset value | set a bit
        GETBIT | key offset | a bit's value
        BITCOUNT | key [start end] [BYTE|BIT] | count set bits
        BITPOS | key bit [start end] [BYTE|BIT] | first bit set or clear
        BITOP | AND|OR|XOR|NOT destkey key [key ...] | bitwise operation
        GEOADD | key [NX|XX] [CH] longitude latitude member [longitude latitude member ...] | add locations
        GEOPOS | key member [member ...] | members' coordinates
        GEODIST | key member1 member2 [M|KM|FT|MI] | distance between members
        GEOHASH | key member [member ...] | members' geohashes
        GEOSEARCH | key [FROMMEMBER member] [FROMLONLAT longitude latitude] [BYRADIUS radius unit] [BYBOX width height unit] [ASC|DESC] [COUNT count] [WITHCOORD] [WITHDIST] [WITHHASH] | members within an area
        JSON.GET | key [path ...] | a JSON value
        JSON.SET | key path value [NX|XX] | set a JSON value
        JSON.MERGE | key path value | merge into a JSON value
        JSON.DEL | key [path] | delete a JSON value
        JSON.MGET | key [key ...] | JSON values of several keys
        JSON.TYPE | key [path] | type of a JSON value
        JSON.NUMINCRBY | key path value | add to a JSON number
        JSON.STRLEN | key [path] | length of a JSON string
        JSON.ARRAPPEND | key path value [value ...] | append to a JSON array
        JSON.ARRLEN | key [path] | length of a JSON array
        JSON.OBJKEYS | key [path] | keys of a JSON object
        JSON.OBJLEN | key [path] | size of a JSON object
        PUBLISH | channel message | post a message
        PUBSUB CHANNELS | [pattern] | active channels
        PUBSUB NUMSUB | [channel ...] | subscribers per channel
        PUBSUB NUMPAT |  | pattern subscriptions
        EVAL | script numkeys [key [key ...]] | run a Lua script
        EVALSHA | sha1 numkeys [key [key ...]] | run a cached script
        EVAL_RO | script numkeys [key [key ...]] | run a read-only script
        SCRIPT LOAD | script | cache a script
        SCRIPT EXISTS | sha1 [sha1 ...] | whether scripts are cached
        SCRIPT FLUSH | [ASYNC|SYNC] | empty the script cache
        FCALL | function numkeys [key [key ...]] | call a function
        FUNCTION LIST | [LIBRARYNAME library-name-pattern] [WITHCODE] | loaded functions
        PING | [message] | check the connection
        ECHO | message | echo a message
        SELECT | index | switch database
        SWAPDB | index1 index2 | swap two databases
        DBSIZE |  | number of keys
        INFO | [section ...] | server information
        TIME |  | server time
        LASTSAVE |  | time of the last save
        ROLE |  | replication role
        SAVE |  | save synchronously
        BGSAVE | [SCHEDULE] | save in the background
        BGREWRITEAOF |  | rewrite the append-only file
        FLUSHDB | [ASYNC|SYNC] | delete every key of this database
        FLUSHALL | [ASYNC|SYNC] | delete every key of every database
        CONFIG GET | parameter [parameter ...] | configuration values
        CONFIG SET | parameter value [parameter value ...] | change configuration
        CONFIG RESETSTAT |  | reset INFO statistics
        CONFIG REWRITE |  | write the configuration file
        CLIENT LIST | [TYPE NORMAL|MASTER|REPLICA|PUBSUB] | connected clients
        CLIENT INFO |  | this connection
        CLIENT ID |  | this connection's ID
        CLIENT GETNAME |  | this connection's name
        CLIENT SETNAME | connection-name | name this connection
        CLIENT KILL | [ID client-id] [ADDR ip:port] [USER username] [SKIPME YES|NO] | close connections
        CLIENT PAUSE | timeout [WRITE|ALL] | pause clients
        CLIENT UNPAUSE |  | resume clients
        SLOWLOG GET | [count] | slow commands
        SLOWLOG LEN |  | slow log length
        SLOWLOG RESET |  | empty the slow log
        LATENCY LATEST |  | latest latency samples
        LATENCY DOCTOR |  | latency analysis
        LATENCY HISTORY | event | latency history of an event
        LATENCY RESET | [event ...] | reset latency data
        MEMORY USAGE | key [SAMPLES count] | bytes a key uses
        MEMORY STATS |  | memory use details
        MEMORY DOCTOR |  | memory analysis
        COMMAND COUNT |  | number of commands
        COMMAND INFO | [command-name ...] | details of commands
        COMMAND DOCS | [command-name ...] | documentation of commands
        COMMAND LIST |  | command names
        CLUSTER INFO |  | cluster state
        CLUSTER NODES |  | cluster nodes
        CLUSTER SHARDS |  | cluster shards
        CLUSTER SLOTS |  | slot ranges
        CLUSTER KEYSLOT | key | a key's hash slot
        CLUSTER MYID |  | this node's ID
        ACL WHOAMI |  | the signed-in user
        ACL LIST |  | ACL rules
        ACL USERS |  | user names
        ACL CAT | [category] | command categories
        ACL LOG | [count] | denied commands
        MODULE LIST |  | loaded modules
        CLEAR |  | empty this view
        """.trimIndent()

    private val EtcdTable =
        """
        get | key [--prefix] [--keys-only] [--limit=n] | a key, or every key with a prefix
        put | key value [--lease=id] | set a key
        del | key [--prefix] | delete a key, or every key with a prefix
        lease grant | ttl | a lease with a TTL in seconds
        lease revoke | id | revoke a lease and delete its keys
        lease timetolive | id | a lease's remaining TTL
        lease list |  | active leases
        member list |  | cluster members
        endpoint status |  | the endpoint's status
        clear |  | empty this view
        """.trimIndent()
}
