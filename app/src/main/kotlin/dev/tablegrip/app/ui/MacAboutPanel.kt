package dev.tablegrip.app.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.tablegrip.app.update.AppVersion
import java.awt.Desktop
import org.slf4j.LoggerFactory

/** What the About panel shows. */
internal data class AboutInfo(
    val name: String,
    /** The version users see, pre-release suffix included (Info.plist only holds MAJOR.MINOR.PATCH). */
    val version: String,
    /** Shown in brackets after [version]: the build number, or "Development" for a copy run from source. */
    val build: String,
    val copyright: String,
    val description: String,
    val link: String,
    /**
     * The icon as a classpath resource, for a copy run from source: macOS would show a folder, having no app bundle to
     * take it from. Null in an installed copy, whose bundle has the icon for every macOS version (Liquid Glass included).
     */
    val iconResource: String? = null,
) {
    companion object {
        const val Copyright = "© 2026 The TableGrip Authors"
        const val Description = "A modern database client"
        const val Link = "https://github.com/duongductrong/tablegrip"
        const val IconResource = "/icon/TableGrip-macOS.png"

        /**
         * [packaged] is false for `:app:run`, where the panel would otherwise describe Java: there's no app bundle, so
         * macOS reads the `java` launcher's.
         */
        fun of(
            version: AppVersion = AppVersion.current,
            packaged: Boolean = System.getProperty("jpackage.app-path") != null,
            name: String? = System.getProperty("apple.awt.application.name"),
        ) = AboutInfo(
            name = name?.takeIf { it.isNotBlank() } ?: "TableGrip",
            version = version.version,
            build = if (packaged) version.build.toString() else "Development",
            copyright = Copyright,
            description = Description,
            link = Link,
            iconResource = if (packaged) null else IconResource,
        )
    }
}

/**
 * The app menu's "About TableGrip" as the system's own About panel (`orderFrontStandardAboutPanelWithOptions:`), filled
 * in with TableGrip's name and version. Without it, a copy run from source shows Java's, and an installed one only the
 * MAJOR.MINOR.PATCH of its Info.plist.
 */
internal object MacAboutPanel {
    private val log = LoggerFactory.getLogger(MacAboutPanel::class.java)

    /** Does nothing off macOS, or when the Objective-C runtime can't be reached (macOS then shows its default panel). */
    fun install() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.APP_ABOUT) } ?: return
        val runtime =
            runCatching { Native.load("objc", ObjC::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")) }
                .onFailure { log.warn("Couldn't load the Objective-C runtime; the About panel stays the system default", it) }
                .getOrNull() ?: return
        val info = AboutInfo.of()
        desktop.setAboutHandler { runCatching { show(runtime, info) }.onFailure { log.warn("Couldn't show the About panel", it) } }
    }

    /** Called on the AWT event thread; the panel itself opens on AppKit's main thread. */
    private fun show(objc: ObjC, info: AboutInfo) {
        val pool = objc.objc_autoreleasePoolPush()
        try {
            val m = Messages(objc)
            val options = m.mutableDictionary()
            m.put(options, "ApplicationName", m.string(info.name))
            m.put(options, "ApplicationVersion", m.string(info.version))
            m.put(options, "Version", m.string(info.build))
            m.put(options, "Copyright", m.string(info.copyright))
            m.put(options, "Credits", m.credits(info.description, info.link))
            info.iconResource?.let { MacAboutPanel::class.java.getResourceAsStream(it)?.use { stream -> stream.readBytes() } }
                ?.let { m.image(it) }
                ?.let { m.put(options, "ApplicationIcon", it) }
            val app = m.send(m.cls("NSApplication"), "sharedApplication")
            objc.objc_msgSend(app, m.sel("performSelectorOnMainThread:withObject:waitUntilDone:"), m.sel("orderFrontStandardAboutPanelWithOptions:"), options, 0.toByte())
        } finally {
            objc.objc_autoreleasePoolPop(pool)
        }
    }

    /** Builds the Foundation and AppKit objects the panel takes; everything returned is autoreleased. */
    private class Messages(private val objc: ObjC) {
        fun cls(name: String): Pointer = objc.objc_getClass(name) ?: error("No Objective-C class $name")

        fun sel(name: String): Pointer = objc.sel_registerName(name)

        fun send(receiver: Pointer, selector: String): Pointer = objc.objc_msgSend(receiver, sel(selector)) ?: error("$selector returned nil")

        fun string(value: String): Pointer = objc.objc_msgSend(cls("NSString"), sel("stringWithUTF8String:"), value) ?: error("Couldn't make an NSString")

        fun image(png: ByteArray): Pointer? {
            val data = objc.objc_msgSend(cls("NSData"), sel("dataWithBytes:length:"), png, png.size.toLong()) ?: return null
            val image = objc.objc_msgSend(send(cls("NSImage"), "alloc"), sel("initWithData:"), data) ?: return null
            return autorelease(image)
        }

        fun mutableDictionary(): Pointer = send(cls("NSMutableDictionary"), "dictionary")

        fun put(dictionary: Pointer, key: String, value: Pointer) {
            objc.objc_msgSend(dictionary, sel("setObject:forKey:"), value, string(key))
        }

        /** Centered secondary-label text, which follows Light and Dark, with the project link under it. */
        fun credits(description: String, link: String): Pointer {
            val style = autorelease(send(cls("NSMutableParagraphStyle"), "new"))
            objc.objc_msgSend(style, sel("setAlignment:"), CenterAlignment)
            val font = objc.objc_msgSend(cls("NSFont"), sel("systemFontOfSize:"), 11.0) ?: error("No system font")
            val textAttributes = mutableDictionary()
            put(textAttributes, "NSParagraphStyle", style)
            put(textAttributes, "NSFont", font)
            put(textAttributes, "NSColor", send(cls("NSColor"), "secondaryLabelColor"))
            val linkAttributes = mutableDictionary()
            put(linkAttributes, "NSParagraphStyle", style)
            put(linkAttributes, "NSFont", font)
            val url = objc.objc_msgSend(cls("NSURL"), sel("URLWithString:"), string(link)) ?: error("Bad URL $link")
            put(linkAttributes, "NSLink", url)

            val credits = attributed("NSMutableAttributedString", "$description\n", textAttributes)
            objc.objc_msgSend(credits, sel("appendAttributedString:"), attributed("NSAttributedString", link.removePrefix("https://"), linkAttributes))
            return credits
        }

        private fun attributed(className: String, text: String, attributes: Pointer): Pointer {
            val allocated = send(cls(className), "alloc")
            val made = objc.objc_msgSend(allocated, sel("initWithString:attributes:"), string(text), attributes) ?: error("Couldn't make an $className")
            return autorelease(made)
        }

        private fun autorelease(owned: Pointer): Pointer = send(owned, "autorelease")
    }

    /** NSTextAlignmentCenter: 1 where AppKit uses iOS's values (Apple silicon), 2 on Intel. */
    private val CenterAlignment: Long = if (System.getProperty("os.arch") == "aarch64") 1L else 2L
}

/**
 * The few Objective-C runtime functions the panel needs. `objc_msgSend` must be called with the callee's exact,
 * non-variadic signature (variadic calls pass arguments wrongly on Apple silicon), so each shape gets an overload.
 */
@Suppress("FunctionName")
internal interface ObjC : Library {
    fun objc_getClass(name: String): Pointer?

    fun sel_registerName(name: String): Pointer

    fun objc_autoreleasePoolPush(): Pointer

    fun objc_autoreleasePoolPop(pool: Pointer)

    fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Pointer): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: String): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Long): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Double): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, first: Pointer, second: Pointer): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, bytes: ByteArray, length: Long): Pointer?

    fun objc_msgSend(receiver: Pointer, selector: Pointer, first: Pointer, second: Pointer, flag: Byte): Pointer?
}
