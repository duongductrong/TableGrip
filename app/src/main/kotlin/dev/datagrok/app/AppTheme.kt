package dev.datagrok.app

import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

enum class AppTheme {
    Light,
    LightWithLightHeader,
    Dark,
    System;

    fun isDark(): Boolean = (if (this == System) fromSystemTheme(currentSystemTheme) else this) == Dark

    fun next(): AppTheme =
        when (this) {
            Light -> LightWithLightHeader
            LightWithLightHeader -> Dark
            Dark,
            System -> Light
        }

    companion object {
        fun fromSystemTheme(systemTheme: SystemTheme): AppTheme = if (systemTheme == SystemTheme.LIGHT) Light else Dark
    }
}
