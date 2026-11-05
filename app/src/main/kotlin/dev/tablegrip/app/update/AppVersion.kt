package dev.tablegrip.app.update

import java.util.Properties

/** The running app's version, from `tablegrip.version` and `tablegrip.build` in gradle.properties. */
data class AppVersion(
    /** What users see: `1.4.0`, or `1.4.0-beta.2` for a pre-release. */
    val version: String,
    /** Only ever goes up from one release to the next; updaters compare this, not [version]. */
    val build: Int,
) {
    val isPrerelease: Boolean get() = '-' in version

    override fun toString(): String = "$version ($build)"

    companion object {
        private const val Resource = "/tablegrip-version.properties"

        private val properties: Properties by lazy {
            Properties().also { properties -> AppVersion::class.java.getResourceAsStream(Resource)?.use(properties::load) }
        }

        /** The base64 Ed25519 key updates must be signed with (`tablegrip.sparklePublicKey`); blank in builds without one. */
        val updatePublicKey: String
            get() = properties.getProperty("sparklePublicKey").orEmpty()

        val current: AppVersion by lazy {
            AppVersion(
                version = properties.getProperty("version") ?: "0.0.0-dev",
                build = properties.getProperty("build")?.toIntOrNull() ?: 0,
            )
        }
    }
}
