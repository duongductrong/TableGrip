package dev.datagrok.core.drivers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a catalog driver is licensed, which decides how the UI offers it. */
@Serializable
enum class LicenseTier {
    /** Apache, MIT, BSD and the like. */
    Permissive,

    /** LGPL: offered only as a separate download the user asks for, used unmodified and replaceable. */
    WeakCopyleft,
}

/** One jar of a catalog driver, as published on Maven Central. [path] is relative to the repository root. */
@Serializable
data class LockedArtifact(val coordinates: String, val path: String, val sha256: String, val size: Long, val license: String) {
    val fileName: String
        get() = path.substringAfterLast('/')
}

/** A driver Datagrok offers to download: its version, entry class, license and every jar it needs. */
@Serializable
data class LockedDriver(
    val id: String,
    val name: String,
    val version: String,
    val className: String,
    /** SPDX id of the driver's own jar. */
    val license: String,
    val tier: LicenseTier,
    val licenseUrl: String,
    val sourceUrl: String,
    val artifacts: List<LockedArtifact>,
) {
    val size: Long
        get() = artifacts.sumOf { it.size }
}

/**
 * The driver catalog pinned at build time (`./gradlew :core:lockDrivers`). Downloads must match these checksums, so what
 * runs is exactly what was reviewed.
 */
@Serializable
data class DriverLock(val drivers: List<LockedDriver>) {
    fun byId(id: String): LockedDriver? = drivers.firstOrNull { it.id == id }

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /** The lockfile built into this version of Datagrok. */
        fun bundled(): DriverLock {
            val text =
                DriverLock::class.java.getResourceAsStream("drivers.lock.json")?.use { it.readBytes().decodeToString() }
                    ?: error("drivers.lock.json is missing from the build")
            return json.decodeFromString(text)
        }
    }
}
