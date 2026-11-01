package dev.datagrok.app.ui

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

internal fun copyToClipboard(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

/** [text] for plain-text targets plus [html] for those that read rich content, so spreadsheets get a real table. */
internal fun copyToClipboard(text: String, html: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(HtmlSelection(text, html), null)

private val HtmlFlavor = DataFlavor("text/html;class=java.lang.String")

private class HtmlSelection(private val text: String, private val html: String) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(HtmlFlavor, DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == HtmlFlavor || flavor == DataFlavor.stringFlavor

    override fun getTransferData(flavor: DataFlavor): Any =
        when (flavor) {
            HtmlFlavor -> html
            DataFlavor.stringFlavor -> text
            else -> throw UnsupportedFlavorException(flavor)
        }
}

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
