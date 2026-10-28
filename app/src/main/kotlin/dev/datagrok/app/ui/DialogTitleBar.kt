package dev.datagrok.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import dev.datagrok.app.grid.isMac
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.window.defaultTitleBarStyle

/** What an [AppDialog]'s title bar content can do. The bar itself drags the dialog; see [interactive]. */
internal interface DialogTitleBarScope : BoxScope {
    /** Space the window's own buttons take: the traffic lights on macOS, the caption buttons on Windows. */
    val insets: PaddingValues

    /** Keeps clicks on this element instead of letting them drag the dialog; put it on buttons, fields and menus. */
    fun Modifier.interactive(): Modifier
}

/** The dialog's title, centered on the whole bar the way macOS and Windows center window titles. */
@Composable
internal fun DialogTitleBarScope.DialogTitle(title: String) {
    val direction = LocalLayoutDirection.current
    // The wider inset on both sides, so the title stays centered on the window rather than on the free space.
    val inset = maxOf(insets.calculateLeftPadding(direction), insets.calculateRightPadding(direction))
    Text(
        title,
        Modifier.align(Alignment.Center).padding(horizontal = inset + 8.dp),
        color = JewelTheme.defaultTitleBarStyle.colors.content,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Hands the top of [dialog] to Compose, keeping the native window buttons; null when that isn't supported. */
@Composable
internal fun rememberCustomTitleBar(dialog: ComposeDialog, height: Dp): WindowDecorations.CustomTitleBar? {
    val dark = JewelTheme.defaultTitleBarStyle.colors.background.luminance() < 0.5f
    return remember(dialog, height, dark) {
        val supported = (isMac || System.getProperty("os.name").startsWith("Windows")) && JBR.isAvailable() && JBR.isWindowDecorationsSupported()
        if (!supported) return@remember null
        val decorations = JBR.getWindowDecorations()
        decorations.createCustomTitleBar().also {
            it.height = height.value
            // Windows draws its caption buttons itself; tell it which variant fits the background.
            it.putProperty("controls.dark", dark)
            decorations.setCustomTitleBar(dialog, it)
        }
    }
}

@Composable
internal fun DialogTitleBar(
    dialog: ComposeDialog,
    titleBar: WindowDecorations.CustomTitleBar,
    height: Dp,
    content: @Composable DialogTitleBarScope.() -> Unit,
) {
    val regions = remember { TitleBarRegions() }
    TitleBarHitTestEffect(dialog, titleBar, regions)
    val colors = JewelTheme.defaultTitleBarStyle.colors
    Box(Modifier.fillMaxWidth().height(height).background(colors.background)) {
        val scope = remember(this, regions, titleBar.leftInset, titleBar.rightInset) {
            TitleBarScopeImpl(this, regions, PaddingValues(start = titleBar.leftInset.dp, end = titleBar.rightInset.dp))
        }
        scope.content()
    }
}

/**
 * Tells the JetBrains Runtime, as the pointer moves, whether it's over an [DialogTitleBarScope.interactive] element.
 * Compose consumes every mouse event, so without this the runtime would treat the whole bar as content and never drag.
 */
@Composable
private fun TitleBarHitTestEffect(dialog: ComposeDialog, titleBar: WindowDecorations.CustomTitleBar, regions: TitleBarRegions) {
    DisposableEffect(dialog, dialog.graphicsConfiguration, titleBar) {
        val transform = dialog.graphicsConfiguration?.defaultTransform
        val scaleX = transform?.scaleX ?: 1.0
        val scaleY = transform?.scaleY ?: 1.0
        val listener =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = update(e)

                override fun mouseReleased(e: MouseEvent) = update(e)

                override fun mouseDragged(e: MouseEvent) = update(e)

                override fun mouseMoved(e: MouseEvent) = update(e)

                // Compose lays out in pixels, AWT reports points.
                private fun update(e: MouseEvent) = titleBar.forceHitTest(regions.isInteractive(Offset((e.x * scaleX).toFloat(), (e.y * scaleY).toFloat())))
            }
        dialog.addMouseListener(listener)
        dialog.addMouseMotionListener(listener)
        onDispose {
            dialog.removeMouseListener(listener)
            dialog.removeMouseMotionListener(listener)
        }
    }
}

/** Where the title bar's interactive elements are, in window pixels. Compose and AWT both touch it on the EDT. */
internal class TitleBarRegions {
    private val bounds = HashMap<Any, Rect>()

    fun isInteractive(point: Offset): Boolean = bounds.values.any { it.contains(point) }

    internal operator fun set(owner: Any, rect: Rect) {
        bounds[owner] = rect
    }

    internal fun remove(owner: Any) {
        bounds.remove(owner)
    }
}

private class TitleBarScopeImpl(box: BoxScope, private val regions: TitleBarRegions, override val insets: PaddingValues) :
    DialogTitleBarScope, BoxScope by box {
    override fun Modifier.interactive(): Modifier = interactiveRegion(regions)
}

internal fun Modifier.interactiveRegion(regions: TitleBarRegions): Modifier = this then InteractiveRegionElement(regions)

private data class InteractiveRegionElement(val regions: TitleBarRegions) : ModifierNodeElement<InteractiveRegionNode>() {
    override fun create() = InteractiveRegionNode(regions)

    override fun update(node: InteractiveRegionNode) {
        if (node.regions === regions) return
        node.regions.remove(node)
        node.regions = regions
    }
}

private class InteractiveRegionNode(var regions: TitleBarRegions) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        regions[this] = coordinates.boundsInWindow()
    }

    override fun onDetach() = regions.remove(this)
}
