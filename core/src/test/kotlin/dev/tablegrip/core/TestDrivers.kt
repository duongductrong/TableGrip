package dev.tablegrip.core

import dev.tablegrip.core.connection.DriverId
import dev.tablegrip.core.drivers.DriverLibrary
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * Drivers shipped as one jar with unrelocated copies of libraries the tests use too (Kotlin, Guava, gRPC). They stay off
 * the test classpath; Gradle passes their jars in `tablegrip.test.isolatedDrivers`, and [library] loads each in a class
 * loader of its own, as the app does.
 */
object TestDrivers {
    private val jars: List<Path> =
        System.getProperty("tablegrip.test.isolatedDrivers").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map(Path::of)

    private val byDriver: Map<DriverId, Path> =
        mapOf(DriverId.Spanner to "google-cloud-spanner-jdbc", DriverId.BigQuery to "google-cloud-bigquery-jdbc")
            .mapNotNull { (driver, prefix) -> jars.firstOrNull { it.fileName.toString().startsWith(prefix) }?.let { driver to it } }
            .toMap()

    /** The drivers [library] holds. */
    val isolated: Set<DriverId> = setOf(DriverId.Spanner, DriverId.BigQuery)

    /** Classpath drivers, plus the isolated ones as the user's own jars. */
    val library: DriverLibrary by lazy {
        DriverLibrary(Files.createTempDirectory("tablegrip-test-drivers")).also { library ->
            runBlocking { byDriver.forEach { (driver, jar) -> library.useJars(driver, listOf(jar)) } }
        }
    }
}
