package dev.tablegrip.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import dev.tablegrip.app.AppState
import dev.tablegrip.app.grid.isMac
import dev.tablegrip.core.i18n.tr
import java.awt.Desktop

/**
 * TableGrip's item in the macOS menu bar (the system tray on Windows and Linux): opens the main window, a connection,
 * a new console or Settings from any app, checks for updates, and quits. Settings → Startup & Layout turns it off.
 */
@Composable
fun ApplicationScope.MenuBarItem(app: AppState) {
    if (!app.settings.showInMenuBar || !isTraySupported) return
    // macOS draws a template image in the menu bar's own colour, light or dark; elsewhere the coloured app icon reads better.
    val icon = remember { if (isMac) MenuBarGlyph else AppIcon ?: MenuBarGlyph }
    val current = app.currentConnection
    Tray(icon = icon, tooltip = "TableGrip", onAction = { app.showMainWindow() }) {
        Item(tr("Open TableGrip"), onClick = { app.showMainWindow() })
        Item(tr("Welcome to TableGrip…"), onClick = { app.showWelcome() })
        Separator()
        Menu(tr("Connections")) {
            val profiles = app.connections.profiles
            profiles.forEach { config ->
                CheckboxItem(config.name, checked = config.id == current?.id, onCheckedChange = {
                    app.showMainWindow()
                    app.pickConnection(config)
                })
            }
            if (profiles.isEmpty()) Item(tr("No connections"), enabled = false, onClick = {})
            Separator()
            Item(tr("New Connection…"), onClick = {
                app.showMainWindow()
                app.connections.openNew()
            })
        }
        Item(
            if (current != null) tr("New Console for {0}", current.name) else tr("New Console"),
            enabled = current != null,
            onClick = {
                current ?: return@Item
                app.showMainWindow()
                app.settingsOpen = false
                app.workspace.openConsole(current)
            },
        )
        Separator()
        Item(tr("Settings…"), onClick = {
            app.showMainWindow()
            app.settingsOpen = true
        })
        Item(tr("Check for Updates…"), enabled = app.updater.unavailableReason == null, onClick = { app.updater.checkNow() })
        Separator()
        Item(tr("Quit TableGrip"), onClick = {
            // Quitting may first ask about unsaved work, in the main window.
            if (app.quitWouldAsk) app.showMainWindow()
            app.quitApp()
        })
    }
}

/** Brings TableGrip to the front from another app, as clicking a menu bar item doesn't activate it. */
internal fun activateApp() {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
            Desktop.getDesktop().requestForeground(true)
        }
    }
}

/**
 * The app icon's G holding a table cell, as a black glyph on transparent: macOS tints it to suit the menu bar when
 * `apple.awt.enableTemplateImages` is on ([Main] sets it). Drawn from the geometry in assets/branding/tools/build_icon.py,
 * in its 1024-unit space.
 */
private val MenuBarGlyph: Painter =
    object : Painter() {
        override val intrinsicSize = Size.Unspecified

        override fun DrawScope.onDraw() {
            // The glyph spans 172…852; a margin keeps it the size of the system's own menu bar icons.
            val from = 44f
            val span = 936f
            val unit = minOf(size.width, size.height) / span
            translate((size.width - span * unit) / 2, (size.height - span * unit) / 2) {
                scale(unit, unit, pivot = Offset.Zero) {
                    translate(-from, -from) { drawGlyph() }
                }
            }
        }

        private fun DrawScope.drawGlyph() {
            val c = 512f
            val outer = 340f
            val inner = 224f
            val ring =
                Path().apply {
                    // A ring open at the top right, from -38° the long way round to 0°, where the crossbar starts.
                    arcTo(Rect(Offset(c, c), outer), -38f, -322f, forceMoveTo = true)
                    lineTo(c + inner, c)
                    arcTo(Rect(Offset(c, c), inner), 0f, 322f, forceMoveTo = false)
                    close()
                }
            drawPath(ring, Color.Black)
            drawRect(Color.Black, topLeft = Offset(623f, 454f), size = Size(229f, 116f))
            drawRoundRect(Color.Black, topLeft = Offset(423f, 423f), size = Size(178f, 178f), cornerRadius = CornerRadius(24f))
        }
    }
