package dev.tablegrip.mcp

/** `tablegrip mcp` flags. They can only narrow what Settings allow, never widen it. */
data class McpOptions(
    /** Only these connections, by id or name. */
    val connections: Set<String>? = null,
    val readOnly: Boolean = false,
    val maxRows: Int? = null,
    val timeoutSeconds: Int? = null,
    /** Downloads a missing, permissively licensed catalog driver instead of failing. */
    val allowDriverDownload: Boolean = false,
    val home: String? = null,
) {
    companion object {
        const val Usage =
            """Usage: tablegrip mcp [options]

Runs TableGrip's MCP server on stdin/stdout, for AI apps such as Claude, Cursor and VS Code.
It serves the connections saved in TableGrip, under the rules set in Settings › MCP and in
each connection's Options. Nothing is served until Settings › MCP is turned on.

Options:
  --connections <a,b>      Only these connections, by id or name
  --read-only              Refuse every write, whatever the connections allow
  --max-rows <n>           Fewer rows per result than Settings › MCP allows
  --timeout <seconds>      A shorter statement timeout than Settings › MCP allows
  --allow-driver-download  Download a missing driver (checked like the app does) instead of failing
  --home <dir>             Use the TableGrip profile in <dir> instead of ~/.tablegrip
  -h, --help               Show this help

Set it up for an app from TableGrip › Settings › MCP, or by hand, e.g. for Claude Code:
  claude mcp add --scope user tablegrip -- tablegrip mcp
"""

        /** Throws [IllegalArgumentException] with a message for the user on a bad flag. */
        fun parse(args: List<String>): McpOptions {
            var options = McpOptions()
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i)?.takeIf { !it.startsWith("--") } ?: throw IllegalArgumentException("$flag needs a value")
            fun number(flag: String): Int = value(flag).toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag needs a positive number")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--connections" -> options = options.copy(connections = value(arg).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
                    "--read-only" -> options = options.copy(readOnly = true)
                    "--max-rows" -> options = options.copy(maxRows = number(arg))
                    "--timeout" -> options = options.copy(timeoutSeconds = number(arg))
                    "--allow-driver-download" -> options = options.copy(allowDriverDownload = true)
                    "--home" -> options = options.copy(home = value(arg))
                    else -> throw IllegalArgumentException("Unknown option: $arg (see tablegrip mcp --help)")
                }
                i++
            }
            return options
        }
    }
}
