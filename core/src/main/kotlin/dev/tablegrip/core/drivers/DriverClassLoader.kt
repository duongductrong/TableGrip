package dev.tablegrip.core.drivers

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * Loads one driver's jars, preferring them over the app's own classes so a driver's dependencies can't clash with
 * TableGrip's. The JDK and SLF4J come from the app, so drivers log through TableGrip's logging.
 */
internal class DriverClassLoader(name: String, jars: List<URL>, parent: ClassLoader) : URLClassLoader(name, jars.toTypedArray(), parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            if (SharedPackages.any(name::startsWith)) return super.loadClass(name, resolve)
            val loaded = try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                return super.loadClass(name, resolve)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

    override fun getResource(name: String): URL? = findResource(name) ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> =
        Collections.enumeration(findResources(name).toList() + parent.getResources(name).toList())

    /** Driver classes the jars register for [java.util.ServiceLoader], in their own jars only. */
    fun registeredDrivers(): List<String> =
        findResources("META-INF/services/java.sql.Driver").toList().flatMap { url ->
            url.openStream().use { it.readBytes().decodeToString() }.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        }

    private companion object {
        val SharedPackages = listOf("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.slf4j.", "org.w3c.", "org.xml.", "org.ietf.")
    }
}
