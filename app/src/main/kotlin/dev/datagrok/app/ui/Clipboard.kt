package dev.datagrok.app.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

internal fun copyToClipboard(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

/** Plain text on a clipboard; the system one in the app, a fake in tests. */
interface TextClipboard {
    fun get(): String?

    fun set(text: String)

    object System : TextClipboard {
        override fun get(): String? =
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()

        override fun set(text: String) = copyToClipboard(text)
    }
}
