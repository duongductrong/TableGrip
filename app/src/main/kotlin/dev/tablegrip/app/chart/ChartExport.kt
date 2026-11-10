package dev.tablegrip.app.chart

import dev.tablegrip.core.i18n.tr
import dev.tablegrip.core.chart.ChartFrame
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copying and saving a chart as an image, drawn at the size it has on screen in the theme it is shown in. Drawing and
 * writing happen in the background; only the file dialog needs the UI thread.
 */
object ChartExport {
    suspend fun copy(renderer: ChartRenderer, frame: ChartFrame, colors: ChartColors, width: Int, height: Int) {
        val image = withContext(ChartDispatcher) { ImageIO.read(ByteArrayInputStream(renderer.export(frame, colors, ChartImageFormat.Png, width, height))) }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)
    }

    /** Asks where to save, as PNG unless the name given ends in `.svg`; null when cancelled. Call on the UI thread. */
    fun chooseFile(suggested: String): File? {
        val dialog = FileDialog(null as Frame?, tr("Save Chart"), FileDialog.SAVE)
        dialog.file = "$suggested.png"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, if ('.' in name) name else "$name.${ChartImageFormat.Png.extension}")
    }

    suspend fun save(renderer: ChartRenderer, frame: ChartFrame, colors: ChartColors, width: Int, height: Int, file: File) {
        val format = if (file.name.endsWith(".svg", ignoreCase = true)) ChartImageFormat.Svg else ChartImageFormat.Png
        val bytes = withContext(ChartDispatcher) { renderer.export(frame, colors, format, width, height) }
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
    }

    private class ImageSelection(private val image: Image) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
    }
}
