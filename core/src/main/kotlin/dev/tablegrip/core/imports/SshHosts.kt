package dev.tablegrip.core.imports

import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

/** A `Host` entry of OpenSSH's client configuration, resolved to what an SSH tunnel needs. */
data class SshHost(val alias: String, val hostName: String, val port: Int?, val user: String?, val identityFile: String?)

/**
 * Reads `~/.ssh/config` so a tunnel can be set up by picking a host the user already has, the way `ssh alias` would
 * reach it. Follows `Include`; wildcard patterns (`Host *`) only supply defaults to the hosts below them.
 */
object SshHosts {

    fun read(home: Path = Path.of(System.getProperty("user.home"))): List<SshHost> {
        val config = home.resolve(".ssh/config")
        if (!config.isRegularFile()) return emptyList()
        val blocks = mutableListOf<Block>()
        collect(config, home, blocks, depth = 0)
        val wildcards = blocks.filter { block -> block.patterns.any { '*' in it || '?' in it } }
        return blocks
            .flatMap { block -> block.patterns.filter { '*' !in it && '?' !in it && !it.startsWith("!") }.map { it to block } }
            .distinctBy { it.first }
            .map { (alias, block) ->
                // ssh uses the first value it sees for each option: the host's own block, then matching wildcards.
                fun option(name: String) = block.options[name] ?: wildcards.firstOrNull { w -> w.patterns.any { matches(it, alias) } }?.options?.get(name)
                SshHost(
                    alias = alias,
                    hostName = option("hostname")?.replace("%h", alias) ?: alias,
                    port = option("port")?.toIntOrNull(),
                    user = option("user"),
                    identityFile = option("identityfile")?.let { ConnectionImport.expandHome(it.removeSurrounding("\""), home) },
                )
            }
    }

    private class Block(val patterns: List<String>, val options: MutableMap<String, String> = mutableMapOf())

    private fun collect(file: Path, home: Path, blocks: MutableList<Block>, depth: Int) {
        if (depth > 8) return
        var current: Block? = null
        for (raw in runCatching { file.readLines() }.getOrDefault(emptyList())) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val keyword = line.split(Regex("""[\s=]+"""), limit = 2)
            val name = keyword[0].lowercase()
            val value = keyword.getOrNull(1)?.trim().orEmpty()
            when (name) {
                "host" -> current = Block(value.split(Regex("""\s+""")).map { it.removeSurrounding("\"") }).also(blocks::add)
                // A Match block's conditions can't be evaluated here: ignore its options.
                "match" -> current = Block(emptyList())
                "include" ->
                    value.split(Regex("""\s+""")).forEach { pattern ->
                        val expanded = ConnectionImport.expandHome(pattern, home)
                        val path = if (Path.of(expanded).isAbsolute) expanded else home.resolve(".ssh").resolve(expanded).toString()
                        expand(path).forEach { collect(it, home, blocks, depth + 1) }
                    }
                else -> current?.options?.putIfAbsent(name, value)
            }
        }
    }

    /** A path that may contain `*` in its last segment. */
    private fun expand(path: String): List<Path> {
        val p = Path.of(path)
        if ('*' !in path && '?' !in path) return listOf(p).filter { it.isRegularFile() }
        val dir = p.parent ?: return emptyList()
        if (!dir.isDirectory()) return emptyList()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${p.fileName}")
        return dir.listDirectoryEntries().filter { it.isRegularFile() && matcher.matches(it.fileName) }.sorted()
    }

    private fun matches(pattern: String, host: String): Boolean =
        Regex(pattern.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }).matches(host)
}
