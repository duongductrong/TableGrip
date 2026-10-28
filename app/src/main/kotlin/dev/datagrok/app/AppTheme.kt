package dev.datagrok.app

import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

enum class AppTheme(val label: String) {
    System("Sync with OS"),
    Light("Light"),
    Dark("Dark");

    fun isDark(): Boolean = (if (this == System) fromSystemTheme(currentSystemTheme) else this) == Dark

    companion object {
        fun fromSystemTheme(systemTheme: SystemTheme): AppTheme = if (systemTheme == SystemTheme.LIGHT) Light else Dark
    }
}
