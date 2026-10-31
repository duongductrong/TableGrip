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

    /** Saved queries: plain `.sql` files, in folders, so they can be edited elsewhere or kept in git. */
    val queries: Path
        get() = home.resolve("queries")

    /** Tabs and layout to reopen on the next start. */
    val session: Path
        get() = home.resolve("session.json")

    /** Downloaded JDBC drivers and the user's own driver jars. */
    val drivers: Path
        get() = home.resolve("drivers")

    /** SQLite working copies of open data files (CSV, Excel…); each lives only while its connection is open. */
    val workingCopies: Path
        get() = home.resolve("cache").resolve("datafiles")

    /** Where CLI agents run: a directory of their own, so an agent with file tools works there, not in the user's home. */
    val agentWorkspace: Path
        get() = home.resolve("agent")
}
