package dev.tablegrip.app

import dev.tablegrip.core.i18n.tr
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

enum class AppTheme(val label: String) {
    System(tr("Sync with OS")),
    Light(tr("Light")),
    Dark(tr("Dark"));

    fun isDark(): Boolean = (if (this == System) fromSystemTheme(currentSystemTheme) else this) == Dark

    companion object {
        /** The mode by its settings name; unknown names follow the OS. */
        fun of(name: String): AppTheme = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: System

        fun fromSystemTheme(systemTheme: SystemTheme): AppTheme = if (systemTheme == SystemTheme.LIGHT) Light else Dark
    }
}
