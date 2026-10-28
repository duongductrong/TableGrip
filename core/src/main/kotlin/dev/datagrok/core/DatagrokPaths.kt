package dev.datagrok.core

import java.nio.file.Path

/** Where Datagrok keeps its files. Override with `-Ddatagrok.home=...` for development or tests. */
object DatagrokPaths {
    val home: Path
        get() = System.getProperty("datagrok.home")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".datagrok")

    val connections: Path
        get() = home.resolve("connections.json")

    val settings: Path
        get() = home.resolve("settings.json")

    val history: Path
        get() = home.resolve("history.jsonl")

    /** Downloaded JDBC drivers and the user's own driver jars. */
    val drivers: Path
        get() = home.resolve("drivers")
}
