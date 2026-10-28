package dev.datagrok.app.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class AppDialogTest {
    @Test
    fun `a bare Esc press closes, but not its release, other keys or Esc with modifiers`() {
        assertTrue(isCloseKey(KeyEvent(Key.Escape, KeyEventType.KeyDown)))
        assertFalse(isCloseKey(KeyEvent(Key.Escape, KeyEventType.KeyUp)))
        assertFalse(isCloseKey(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
        assertFalse(isCloseKey(KeyEvent(Key.Escape, KeyEventType.KeyDown, isShiftPressed = true)))
        assertFalse(isCloseKey(KeyEvent(Key.Escape, KeyEventType.KeyDown, isMetaPressed = true)))
    }
}
