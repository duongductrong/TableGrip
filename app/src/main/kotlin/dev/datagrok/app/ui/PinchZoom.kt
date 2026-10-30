package dev.datagrok.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import java.lang.reflect.Proxy
import javax.swing.JComponent
import org.slf4j.LoggerFactory

/**
 * Trackpad pinch on macOS. Compose doesn't deliver magnify gestures, so they're read from AWT's gesture events on the
 * window's root pane (needs `--add-exports java.desktop/com.apple.eawt.event=ALL-UNNAMED`) and handed to whichever
 * [onPinch] area the pointer is over.
 */
object PinchZoom {
    private val log = LoggerFactory.getLogger(PinchZoom::class.java)
    private var target: ((Float) -> Unit)? = null

    /** Listens on [root]; returns how to stop, or null where the gestures aren't available (other OSes, missing export). */
    fun install(root: JComponent): (() -> Unit)? =
        try {
            val listenerType = Class.forName("com.apple.eawt.event.MagnificationListener")
            val baseType = Class.forName("com.apple.eawt.event.GestureListener")
            val utilities = Class.forName("com.apple.eawt.event.GestureUtilities")
            val magnification = Class.forName("com.apple.eawt.event.MagnificationEvent").getMethod("getMagnification")
            val listener =
                Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                    when (method.name) {
                        "magnify" -> target?.invoke(1f + (magnification.invoke(args[0]) as Double).toFloat().coerceAtLeast(-0.5f)).let { null }
                        "equals" -> proxy === args[0]
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "PinchZoom"
                        else -> null
                    }
                }
            utilities.getMethod("addGestureListenerTo", JComponent::class.java, baseType).invoke(null, root, listener)
            val stop = { utilities.getMethod("removeGestureListenerFrom", JComponent::class.java, baseType).invoke(null, root, listener); Unit }
            stop
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: Exception) {
            log.debug("Trackpad pinch unavailable", e)
            null
        }

    internal fun claim(handler: (Float) -> Unit) {
        target = handler
    }

    internal fun release(handler: (Float) -> Unit) {
        if (target === handler) target = null
    }
}

/** Calls [onZoom] with a scale factor and the pointer position while the user pinches over this element. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.onPinch(onZoom: (factor: Float, anchor: Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(onZoom)
    val pointer = remember { PointerPosition() }
    val handler = remember { { factor: Float -> current(factor, pointer.position) } }
    DisposableEffect(handler) { onDispose { PinchZoom.release(handler) } }
    return this
        .onPointerEvent(PointerEventType.Enter) { event ->
            pointer.position = event.changes.first().position
            PinchZoom.claim(handler)
        }
        .onPointerEvent(PointerEventType.Move) { event ->
            pointer.position = event.changes.first().position
            PinchZoom.claim(handler)
        }
        .onPointerEvent(PointerEventType.Exit) { PinchZoom.release(handler) }
}

private class PointerPosition {
    var position = Offset.Zero
}
