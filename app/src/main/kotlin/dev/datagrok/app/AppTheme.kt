package dev.datagrok.app

import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

enum class AppTheme(val label: String) {
    System("Sync with OS"),
    Light("Light"),
    Dark("Dark");

    fun isDark(): Boolean = (if (this == System) fromSystemTheme(currentSystemTheme) else this) == Dark

    companion object {
        /** The mode by its settings name; unknown names follow the OS. */
        fun of(name: String): AppTheme = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: System

        fun fromSystemTheme(systemTheme: SystemTheme): AppTheme = if (systemTheme == SystemTheme.LIGHT) Light else Dark
    }
}
