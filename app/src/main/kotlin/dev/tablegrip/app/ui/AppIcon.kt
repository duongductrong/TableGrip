package dev.tablegrip.app.ui

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap

/**
 * The app icon for every window, which Windows and Linux show in the taskbar (without it, Java's own). macOS takes the
 * Dock icon from the app bundle instead. Exported from assets/branding/ by its tools/export_app_icon.py.
 */
internal val AppIcon: Painter? by lazy {
    AppIconResource::class.java.getResourceAsStream("/icon/TableGrip.png")?.use { stream ->
        BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap())
    }
}

private object AppIconResource
