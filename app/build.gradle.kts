@file:Suppress("UnstableApiUsage")

import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeDesktop)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin {
    jvmToolchain(jdkLevel)
    compilerOptions {
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("org.jetbrains.jewel.foundation.ExperimentalJewelApi")
    }
}

/** Datagrok's adapters for non-JDBC clients: shipped as jars inside the app, loaded only with their client's download. */
val engineAdapters by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    engineAdapters(projects.engines.redis)
    engineAdapters(projects.engines.mongodb)
    implementation(projects.core)

    implementation(libs.jewel.intUi.standalone)
    implementation(libs.jewel.intUi.decoratedWindow)
    implementation(libs.intellijPlatform.icons)
    implementation(libs.jbr.api)

    implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(compose.desktop.uiTestJUnit4)
    // Drivers never ship with the app (see verifyNoBundledDrivers); tests that open databases bring their own.
    testRuntimeOnly(libs.jdbc.sqlite)
    // The Redis adapter and its client, on the classpath for UI tests against a real server.
    testImplementation(projects.engines.redis)
    testRuntimeOnly(libs.lettuce)
    testImplementation(projects.engines.mongodb)
    testRuntimeOnly(libs.mongodb.driver.sync)
}

compose.desktop {
    application {
        mainClass = "dev.datagrok.app.MainKt"
        // Skiko and the SQLite driver load native libraries.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Trackpad pinch-to-zoom reads macOS magnify gestures (see PinchZoom).
        jvmArgs("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Datagrok"
            packageVersion = "1.0.0"
            description = "A modern database client"
            vendor = "Datagrok"
            // java.net.http downloads drivers; the rest are what downloaded drivers commonly need (GSSAPI/Kerberos and
            // SASL logins, XML parsing), since they can't add modules to the bundled runtime themselves.
            modules(
                "java.sql", "java.naming", "java.management", "jdk.unsupported", "java.net.http",
                "java.security.jgss", "java.security.sasl", "java.xml",
            )

            macOS {
                dockName = "Datagrok"
                bundleID = "dev.datagrok.app"
            }
        }
    }
}

/**
 * Fails if a JDBC driver ends up in the app's runtime classpath, and so in the installer. Drivers are downloaded by the
 * user on demand; shipping one would mean redistributing it under its license.
 */
val verifyNoBundledDrivers by tasks.registering {
    group = "verification"
    description = "Fails if any jar the app ships registers a java.sql.Driver."
    val jars = configurations.runtimeClasspath.map { classpath -> classpath.incoming.artifacts.resolvedArtifacts.get().map { it.file } }
    doLast {
        val offenders =
            jars.get().filter { it.name.endsWith(".jar") }.filter { jar ->
                ZipFile(jar).use { zip -> zip.getEntry("META-INF/services/java.sql.Driver") != null }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException("These jars would ship a JDBC driver with Datagrok: ${offenders.joinToString { it.name }}. Drivers must be downloaded on demand.")
        }
    }
}

// region Third-party notices
//
// Every library the app ships, with the license it ships under. A person checked each one (POM, parent POM, or the
// project's LICENSE file when the POM says nothing); an artifact from a group not listed here, or under a license the
// allowlist doesn't take, fails the build until someone reads its license. Keys ending in "." cover subgroups.

val bundledLicenses =
    mapOf(
        "androidx." to "Apache-2.0",
        "org.jetbrains.compose." to "Apache-2.0",
        "org.jetbrains.androidx." to "Apache-2.0",
        "org.jetbrains.skiko" to "Apache-2.0",
        "org.jetbrains.jewel" to "Apache-2.0",
        "org.jetbrains.kotlin" to "Apache-2.0",
        "org.jetbrains.kotlinx" to "Apache-2.0",
        "org.jetbrains.intellij.deps.kotlinx" to "Apache-2.0",
        "org.jetbrains" to "Apache-2.0",
        "org.jetbrains.runtime" to "Apache-2.0",
        "com.jetbrains.intellij.platform" to "Apache-2.0",
        "at.favre.lib" to "Apache-2.0",
        "com.hierynomus" to "Apache-2.0",
        "com.zaxxer" to "Apache-2.0",
        "org.jspecify" to "Apache-2.0",
        // Dual-licensed Apache-2.0 / LGPL-2.1: shipped under Apache-2.0.
        "net.java.dev.jna" to "Apache-2.0",
        // Dual-licensed EPL-1.0 / LGPL-2.1: shipped under EPL-1.0.
        "ch.qos.logback" to "EPL-1.0",
        "org.slf4j" to "MIT",
        "com.github.hypfvieh" to "MIT",
        "de.swiesend" to "MIT",
        // The Bouncy Castle Licence is the MIT license text.
        "org.bouncycastle" to "MIT",
        "pt.davidafsilva.apple" to "BSD-2-Clause",
        "com.github.javakeyring" to "BSD-3-Clause",
    )
val shippableLicenses = setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "EPL-1.0", "EPL-2.0", "MPL-2.0")

val noticesFile = layout.buildDirectory.file("generated/notices/THIRD-PARTY-NOTICES.txt")

val thirdPartyNotices by tasks.registering {
    group = "verification"
    description = "Writes THIRD-PARTY-NOTICES.txt for every shipped library; fails on one whose license nobody reviewed."
    val components =
        configurations.runtimeClasspath.map { classpath ->
            classpath.incoming.artifacts.resolvedArtifacts.get()
                .mapNotNull { it.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .map { "${it.group}:${it.module}:${it.version}" }
                .distinct()
                .sorted()
        }
    val licenses = bundledLicenses
    val allowed = shippableLicenses
    val output = noticesFile
    inputs.property("components", components)
    inputs.property("licenses", licenses)
    outputs.file(output)
    doLast {
        fun licenseOf(group: String): String? =
            licenses[group] ?: licenses.entries.filter { it.key.endsWith(".") && group.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
        val byLicense = components.get().groupBy { coordinates ->
            val group = coordinates.substringBefore(':')
            val license = licenseOf(group) ?: throw GradleException("No reviewed license for $coordinates. Read it and add its group to bundledLicenses.")
            if (license !in allowed) throw GradleException("$coordinates is under $license, which Datagrok may not ship.")
            license
        }
        val text = buildString {
            appendLine("Datagrok includes the following open source libraries, under the licenses named.")
            appendLine("License texts: https://spdx.org/licenses/<id>.html. JDBC drivers are not included: they are")
            appendLine("downloaded separately at the user's request, under their own licenses.")
            byLicense.toSortedMap().forEach { (license, items) ->
                appendLine()
                appendLine("$license (https://spdx.org/licenses/$license.html)")
                items.forEach { appendLine("  $it") }
            }
        }
        output.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    }
}

// endregion

tasks {
    check { dependsOn(verifyNoBundledDrivers, thirdPartyNotices) }
    processResources {
        from(thirdPartyNotices)
        // Renamed to the adapter's name, which DriverLibrary looks up as engines/<name>.jar.
        from(engineAdapters) {
            into("engines")
            rename { it.substringBefore('-').removeSuffix(".jar") + ".jar" }
        }
    }
    matching { it.name.startsWith("package") }.configureEach { dependsOn(verifyNoBundledDrivers) }

    test {
        useJUnitPlatform()
        providers.gradleProperty("snapshot.dir").orNull?.let { systemProperty("snapshot.dir", it) }
        providers.gradleProperty("snapshot.db").orNull?.let { systemProperty("snapshot.db", it) }
        listOf("it.network", "it.postgres", "it.redis", "it.mongo", "it.etcd").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
    }

    withType<JavaExec> {
        // The Compose Gradle plugin registers its run tasks in afterEvaluate.
        afterEvaluate {
            // Jewel needs the UI fixes in the JetBrains Runtime.
            javaLauncher =
                project.javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(jdkLevel)
                    vendor = JvmVendorSpec.JETBRAINS
                }
            doFirst { setExecutable(javaLauncher.get().executablePath.asFile.absolutePath) }
        }
        jvmArgs("-Dcompose.interop.blending=true")
        // `./gradlew :app:run -Pdatagrok.home=/tmp/dg` runs against a throwaway profile/settings directory.
        providers.gradleProperty("datagrok.home").orNull?.let { systemProperty("datagrok.home", it) }
    }
}
