package dev.tablegrip.core.driver

/**
 * How binary cells read into a grid appear as text: `0x` and their hex digits, like DataGrip shows them. Values longer
 * than [PreviewBytes] keep only their start, followed by `… (N bytes)`, so a result of large blobs stays small.
 */
object BinaryText {
    const val PreviewBytes = 16 * 1024

    private val Pattern = Regex("""^0x([0-9A-F]*)(?:… \((\d+) bytes\))?$""")
    private val Digits = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, limit: Int = PreviewBytes): String {
        val shown = minOf(bytes.size, limit)
        val out = StringBuilder(2 + shown * 2 + 24).append("0x")
        for (i in 0 until shown) {
            val b = bytes[i].toInt() and 0xFF
            out.append(Digits[b ushr 4]).append(Digits[b and 0x0F])
        }
        if (shown < bytes.size) out.append("… (").append(bytes.size).append(" bytes)")
        return out.toString()
    }

    /** The bytes [text] shows, and the value's full size; null if it isn't [encode]'s output. */
    fun decode(text: String): Preview? {
        val match = Pattern.matchEntire(text) ?: return null
        val hex = match.groupValues[1]
        if (hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }
        val total = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: bytes.size
        return Preview(bytes, total)
    }

    /** The first [bytes] of a binary value of [totalSize] bytes. */
    class Preview(val bytes: ByteArray, val totalSize: Int) {
        val complete: Boolean
            get() = bytes.size == totalSize
    }
}
