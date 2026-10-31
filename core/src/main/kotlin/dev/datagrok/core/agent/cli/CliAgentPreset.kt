package dev.datagrok.core.agent.cli

/**
 * The CLI agents Datagrok knows how to run headless. Each preset is a binary and the arguments that make it answer one
 * prompt on stdout and exit.
 *
 * Datagrok runs every query itself, after the user agrees in the chat, so these are configured to *answer*, not to act:
 * built-in tools are switched off where the CLI allows it, and elsewhere it runs in its read-only, ask or plan mode
 * with nothing auto-approved. A request for a tool then fails inside the agent instead of running behind the user's
 * back; there is no terminal for a permission prompt to hang on, and stdin is closed.
 *
 * Every flag here was checked against the CLI's own `--help`: an unknown flag makes these tools print their usage and
 * exit, which arrives as a failed turn with no obvious cause.
 */
enum class CliAgentPreset(
    val title: String,
    val binary: String,
    private val arguments: List<String>,
    val output: CliOutputFormat = CliOutputFormat.PlainText,
    val timeoutSeconds: Long = 180,
    /** The flag that picks a model, put where [arguments] say `{model}`; null when the CLI can't be told one. */
    val modelFlag: String? = null,
    val models: CliModelList = CliModelList.None,
) {
    // `--tools ""` removes every built-in tool and `--strict-mcp-config` (with no config given) every MCP server.
    Claude(
        "Claude Code", "claude",
        listOf(
            "--safe-mode", "--no-session-persistence", "--tools", "", "--strict-mcp-config", "--permission-mode", "dontAsk",
            "-p", "--verbose", "--output-format", "stream-json", "--include-partial-messages", MODEL, PROMPT,
        ),
        CliOutputFormat.ClaudeStreamJson,
        modelFlag = "--model",
        models = CliModelList.ClaudeAliases,
    ),
    // Codex can't drop its shell, but the read-only sandbox keeps commands from writing or reaching the network.
    Codex(
        "Codex", "codex",
        listOf("exec", "--skip-git-repo-check", "--ephemeral", "--sandbox", "read-only", "--ignore-rules", "--json", MODEL, PROMPT),
        CliOutputFormat.CodexJson,
        modelFlag = "--model",
        models = CliModelList.Codex,
    ),
    // Without yolo, headless Gemini leaves out the tools that would need approval (shell, edits).
    // Gemini CLI can't list its models; one is typed by hand.
    Gemini(
        "Gemini CLI", "gemini",
        listOf("--approval-mode", "default", "--output-format", "stream-json", MODEL, PROMPT),
        CliOutputFormat.GeminiStreamJson,
        modelFlag = "--model",
    ),
    // Ask mode is Cursor's read-only Q&A mode.
    Cursor(
        "Cursor Agent", "cursor-agent",
        listOf("--mode", "ask", "--trust", MODEL, "-p", "--output-format", "stream-json", "--stream-partial-output", PROMPT),
        CliOutputFormat.ClaudeStreamJson,
        modelFlag = "--model",
        models = CliModelList.Cursor,
    ),
    // dontAsk denies anything not explicitly allowed. `streaming-messages-json` is Claude Code's stream-json, deltas included.
    Grok(
        "Grok", "grok",
        listOf("--permission-mode", "dontAsk", "--no-plan", MODEL, "--output-format", "streaming-messages-json", "--include-partial-messages", "-p", PROMPT),
        CliOutputFormat.ClaudeStreamJson,
        modelFlag = "--model",
        models = CliModelList.Grok,
    ),
    // `run` is OpenCode's headless subcommand; its built-in plan agent doesn't edit. JSON reports a failure its exit code
    // doesn't, and `--thinking` adds the reasoning parts.
    OpenCode(
        "OpenCode", "opencode",
        listOf("run", "--agent", "plan", "--format", "json", "--thinking", MODEL, PROMPT),
        CliOutputFormat.OpenCodeJson,
        modelFlag = "--model",
        models = CliModelList.OpenCode,
    ),
    // Antigravity starts slowly (~10s of process startup before the model is asked), hence the longer timeout. Without
    // `--dangerously-skip-permissions` its tool requests are denied and the turn carries on.
    Antigravity(
        "Antigravity", "agy",
        listOf("--mode", "plan", MODEL, "--output-format", "stream-json", "-p", PROMPT),
        CliOutputFormat.AntigravityStreamJson,
        timeoutSeconds = 300,
        modelFlag = "--model",
        models = CliModelList.Antigravity,
    ),
    Pi("Pi", "pi", listOf("--no-session", "-p", PROMPT)),
    Hermes("Hermes", "hermes", listOf("-z", PROMPT));

    /**
     * The argv after the binary, with the prompt passed as one argument: no shell, so nothing in it needs quoting.
     * A blank [model] leaves the flag out, and the CLI answers with whatever model it is set up to use.
     */
    fun arguments(prompt: String, model: String? = null): List<String> {
        val chosen = model?.trim().orEmpty()
        return arguments.flatMap {
            when (it) {
                PROMPT -> listOf(prompt)
                MODEL -> if (modelFlag != null && chosen.isNotEmpty()) listOf(modelFlag, chosen) else emptyList()
                else -> listOf(it)
            }
        }
    }

    val choosesModel: Boolean
        get() = modelFlag != null

    val id: String
        get() = "cli:$binary"
}

private const val PROMPT = "{prompt}"
private const val MODEL = "{model}"
