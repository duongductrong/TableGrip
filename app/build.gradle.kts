@file:Suppress("UnstableApiUsage")

import java.net.URI
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeDesktop)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

// region Version
//
// One source for every version string: `tablegrip.version` (what users see, e.g. 1.4.0 or 1.4.0-beta.2) and
// `tablegrip.build` (an integer that only ever goes up; CI passes the commit count of master). Updaters compare the
// build, so two releases must never share one.

val appVersion = providers.gradleProperty("tablegrip.version").get()
val appBuild = providers.gradleProperty("tablegrip.build").get()
val versionMatch =
    Regex("""([1-9]\d*)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?""").matchEntire(appVersion)
        ?: throw GradleException("tablegrip.version must look like 1.4.0 or 1.4.0-beta.2, not '$appVersion'.")
if (appBuild.toIntOrNull()?.takeIf { it in 1..65535 } == null) throw GradleException("tablegrip.build must be an integer from 1 to 65535, not '$appBuild'.")
val (versionMajor, versionMinor, versionPatch) = versionMatch.destructured

/** Writes the version the app reads at runtime (see `dev.tablegrip.app.update.AppVersion`). */
val versionResource by tasks.registering {
    val version = appVersion
    val build = appBuild
    // Windows reads the update key from here; macOS from Info.plist.
    val updateKey = providers.gradleProperty("tablegrip.sparklePublicKey").orNull.orEmpty()
    val output = layout.buildDirectory.file("generated/version/tablegrip-version.properties")
    inputs.property("version", version)
    inputs.property("build", build)
    inputs.property("updateKey", updateKey)
    outputs.file(output)
    doLast { output.get().asFile.apply { parentFile.mkdirs() }.writeText("version=$version\nbuild=$build\nsparklePublicKey=$updateKey\n") }
}

// endregion

// region Updater
//
// macOS updates through Sparkle: the framework and the small bridge the app calls (app/src/macos/updater) go into
// Contents/Frameworks, and the app is signed again around them. Other systems don't get an updater yet.

val isMacHost = System.getProperty("os.name").startsWith("Mac")
val sparkleVersion = "2.10.1"
val sparkleSha256 = "1a856d2a9edb8e628de88121d7be04cf0e2167f8641650b78fca00b14244cf27"
/** Base64 Ed25519 public key that every update must be signed with (Sparkle's generate_keys prints it). */
val sparklePublicKey = providers.gradleProperty("tablegrip.sparklePublicKey").orNull.orEmpty()
val macEntitlements = layout.projectDirectory.file("src/macos/entitlements.plist")
/** The app icon for each system, exported from assets/branding/ by its tools/export_app_icon.py. */
val appIcons = layout.projectDirectory.dir("src/icons")
val sparkleDir = layout.buildDirectory.dir("sparkle/$sparkleVersion")
val updaterBridge = layout.buildDirectory.file("updater/libTableGripUpdater.dylib")

/**
 * The JetBrains Runtime for `:app:run` on a Mac, under the name the Dock shows: macOS 26 ignores -Xdock:name and labels
 * an app that has no bundle after its executable. A copy of the runtime's home made of links, whose bin/java is a
 * script running a link named "TableGrip Dev". The script is needed because Gradle resolves links in the executable.
 */
object DevRuntime {
    class Launcher(private val jbr: JavaLauncher, private val home: Directory) : JavaLauncher {
        override fun getMetadata(): JavaInstallationMetadata = jbr.metadata

        override fun getExecutablePath(): RegularFile = home.file("bin/java")
    }

    fun make(jbr: JavaLauncher, into: Directory) {
        val home = jbr.executablePath.asFile.toPath().parent.parent
        val dir = into.asFile.toPath()
        // Not File.deleteRecursively: it follows the links into the runtime and empties it. Files.walk doesn't.
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
        val bin = Files.createDirectories(dir.resolve("bin"))
        Files.list(home).use { entries -> entries.filter { it.fileName.toString() != "bin" }.forEach { Files.createSymbolicLink(dir.resolve(it.fileName.toString()), it) } }
        Files.list(home.resolve("bin")).use { entries -> entries.filter { it.fileName.toString() != "java" }.forEach { Files.createSymbolicLink(bin.resolve(it.fileName.toString()), it) } }
        Files.createSymbolicLink(bin.resolve("TableGrip Dev"), home.resolve("bin/java"))
        bin.resolve("java").toFile().apply {
            writeText("#!/bin/sh\nexec \"$(dirname \"$0\")/TableGrip Dev\" \"$@\"\n")
            setExecutable(true)
        }
    }
}

/** Runs a command from a task action; an object, so actions can call it without capturing the build script. */
object Shell {
    fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("${command.joinToString(" ")} failed:\n$output")
    }
}

/** Sparkle's release archive, checked against its pinned SHA-256: the framework, and sign_update for the release workflow. */
val downloadSparkle by tasks.registering {
    val version = sparkleVersion
    val sha256 = sparkleSha256
    val output = sparkleDir
    inputs.property("version", version)
    inputs.property("sha256", sha256)
    outputs.dir(output)
    val mac = isMacHost
    onlyIf { mac }
    doLast {
        val dir = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        val archive = File(temporaryDir, "Sparkle-$version.tar.xz")
        fun digest() = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        // A copy already downloaded is kept as long as it's still the pinned one.
        if (!archive.isFile || digest() != sha256) {
            URI("https://github.com/sparkle-project/Sparkle/releases/download/$version/Sparkle-$version.tar.xz").toURL().openStream().use { input ->
                archive.outputStream().use { input.copyTo(it) }
            }
            val actual = digest()
            if (actual != sha256) throw GradleException("Sparkle $version doesn't match its pinned SHA-256 (got $actual).")
        }
        Shell.run("tar", "-xJf", archive.absolutePath, "-C", dir.absolutePath)
    }
}

/** The bridge between the app and Sparkle, for both Mac architectures. */
val buildUpdaterBridge by tasks.registering {
    val source = layout.projectDirectory.file("src/macos/updater/TableGripUpdater.m")
    val sparkle = sparkleDir
    val output = updaterBridge
    dependsOn(downloadSparkle)
    inputs.file(source)
    inputs.dir(sparkleDir)
    outputs.file(output)
    val mac = isMacHost
    onlyIf { mac }
    doLast {
        val dylib = output.get().asFile.apply { parentFile.mkdirs() }
        Shell.run(
            "xcrun", "clang", "-dynamiclib", "-fobjc-arc", "-fmodules", "-O2",
            "-arch", "arm64", "-arch", "x86_64", "-mmacosx-version-min=12.0",
            "-F", sparkle.get().asFile.absolutePath, "-framework", "Sparkle", "-framework", "AppKit",
            "-install_name", "@rpath/libTableGripUpdater.dylib", "-Wl,-rpath,@loader_path",
            "-o", dylib.absolutePath, source.asFile.absolutePath,
        )
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows")
val winSparkleVersion = "0.9.4"
val winSparkleSha256 = "6037df37fc263bd1650a1c4949681a9d40ffe991d01f35892a406cb5d103c976"
/** Compose copies `windows-x64/` from here into the Windows app's resources folder. */
val appResources = layout.buildDirectory.dir("appResources")

/** WinSparkle's x64 DLL from its release archive, checked against its pinned SHA-256. */
val downloadWinSparkle by tasks.registering {
    val version = winSparkleVersion
    val sha256 = winSparkleSha256
    val output = appResources.map { it.dir("windows-x64") }
    inputs.property("version", version)
    inputs.property("sha256", sha256)
    outputs.dir(output)
    val windows = isWindowsHost
    onlyIf { windows }
    doLast {
        val archive = File(temporaryDir, "WinSparkle-$version.zip")
        fun digest() = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        if (!archive.isFile || digest() != sha256) {
            URI("https://github.com/vslavik/winsparkle/releases/download/v$version/WinSparkle-$version.zip").toURL().openStream().use { input ->
                archive.outputStream().use { input.copyTo(it) }
            }
            val actual = digest()
            if (actual != sha256) throw GradleException("WinSparkle $version doesn't match its pinned SHA-256 (got $actual).")
        }
        val dir = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("WinSparkle-$version/x64/Release/WinSparkle.dll") ?: throw GradleException("No x64 WinSparkle.dll in WinSparkle $version.")
            zip.getInputStream(entry).use { input -> dir.resolve("WinSparkle.dll").outputStream().use { input.copyTo(it) } }
        }
    }
}

// endregion

kotlin {
    jvmToolchain(jdkLevel)
    compilerOptions {
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn.add("org.jetbrains.jewel.foundation.ExperimentalJewelApi")
    }
}

/** TableGrip's adapters for non-JDBC clients: shipped as jars inside the app, loaded only with their client's download. */
val engineAdapters by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    engineAdapters(projects.engines.redis)
    engineAdapters(projects.engines.mongodb)
    implementation(projects.core)
    implementation(projects.mcp)

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
        mainClass = "dev.tablegrip.app.MainKt"
        // Installers bundle this runtime: the JBR, like `run` uses, never whatever JAVA_HOME happens to be.
        javaHome =
            javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(jdkLevel)
                vendor = JvmVendorSpec.JETBRAINS
            }.get().metadata.installationPath.asFile.absolutePath
        // Skiko and the SQLite driver load native libraries.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Trackpad pinch-to-zoom reads macOS magnify gestures (see PinchZoom).
        jvmArgs("--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            appResourcesRootDir = appResources
            packageName = "TableGrip"
            // Installers only take MAJOR.MINOR.PATCH; a pre-release's suffix is shown by the app itself.
            packageVersion = "$versionMajor.$versionMinor.$versionPatch"
            description = "A modern database client"
            vendor = "TableGrip"
            copyright = "Copyright 2026 The TableGrip Authors. Licensed under the Apache License, Version 2.0."
            licenseFile = rootProject.layout.projectDirectory.file("LICENSE")
            // java.net.http downloads drivers; the rest are what downloaded drivers commonly need (GSSAPI/Kerberos and
            // SASL logins, XML parsing), since they can't add modules to the bundled runtime themselves.
            modules(
                "java.sql", "java.naming", "java.management", "jdk.unsupported", "java.net.http",
                "java.security.jgss", "java.security.sasl", "java.xml",
            )

            // Lets the file manager open data files with TableGrip (Open With, or double-click once it's the default),
            // each in a window of its own. Plain .json and .txt are left out: too many of them aren't tables.
            fileAssociation("text/csv", "csv", "CSV Document")
            fileAssociation("text/tab-separated-values", "tsv", "TSV Document")
            fileAssociation("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", "Excel Workbook")
            fileAssociation("application/vnd.ms-excel.sheet.macroEnabled.12", "xlsm", "Excel Macro-Enabled Workbook")
            fileAssociation("application/x-ndjson", "ndjson", "NDJSON Document")
            fileAssociation("application/jsonl", "jsonl", "JSON Lines Document")

            // Signing and notarization are switched on from outside (CI), never stored here: -Pcompose.desktop.mac.sign=true
            // with compose.desktop.mac.signing.identity, and compose.desktop.mac.notarization.appleID / password / teamID.
            macOS {
                dockName = "TableGrip"
                bundleID = "dev.tablegrip.app"
                // macOS 12–15 show this; macOS 26 the Liquid Glass icon in Assets.car (added after packaging, below).
                iconFile = appIcons.file("TableGrip.icns")
                // CFBundleVersion, which Sparkle compares.
                packageBuildVersion = appBuild
                dmgPackageBuildVersion = appBuild
                entitlementsFile = macEntitlements
                runtimeEntitlementsFile = macEntitlements
                // The languages TableGrip is translated into (see core's i18n), so system dialogs, the About panel and
                // Sparkle's windows follow the system's language among them.
                // Sparkle: every update and the feed itself must carry a signature from this key; checks need no
                // first-run prompt (Settings → Updates turns them off); nothing about the Mac is sent.
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>CFBundleIconName</key><string>TableGrip</string>
                        <key>CFBundleDevelopmentRegion</key><string>en</string>
                        <key>CFBundleLocalizations</key><array><string>en</string><string>vi</string><string>zh-Hans</string><string>zh-Hant</string></array>
                        <key>SUPublicEDKey</key><string>$sparklePublicKey</string>
                        <key>SURequireSignedFeed</key><true/>
                        <key>SUVerifyUpdateBeforeExtraction</key><true/>
                        <key>SUEnableAutomaticChecks</key><true/>
                        <key>SUEnableSystemProfiling</key><false/>
                        """.trimIndent()
                }
            }
            windows {
                iconFile = appIcons.file("TableGrip.ico")
                // Fixed for the app's lifetime: it's how a newer MSI finds and replaces the installed one.
                upgradeUuid = "0E0D7626-709F-4F7A-9AAC-C702F6194B34"
                // Installs into the user's profile, so updates don't ask for admin rights.
                perUserInstall = true
                menuGroup = "TableGrip"
                // MSI ignores anything after the third part and won't replace a build with the same version, so the
                // build takes the third place: 1.4.0 build 412 and 1.4.1 build 415 install as 1.4.412 and 1.4.415.
                msiPackageVersion = "$versionMajor.$versionMinor.$appBuild"
            }
            linux {
                iconFile = appIcons.file("TableGrip.png")
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
    // A file collection, not a resolved list: as an input it carries the build dependencies on the project jars
    // (core.jar, ...), so they are built before this task reads them.
    val jars = files(configurations.runtimeClasspath)
    inputs.files(jars)
    doLast {
        val offenders =
            jars.files.filter { it.name.endsWith(".jar") }.filter { jar ->
                ZipFile(jar).use { zip -> zip.getEntry("META-INF/services/java.sql.Driver") != null }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException("These jars would ship a JDBC driver with TableGrip: ${offenders.joinToString { it.name }}. Drivers must be downloaded on demand.")
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
    val sparkle = sparkleVersion
    val winSparkle = winSparkleVersion
    inputs.property("sparkle", sparkle)
    inputs.property("winSparkle", winSparkle)
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
            if (license !in allowed) throw GradleException("$coordinates is under $license, which TableGrip may not ship.")
            license
        }
        val text = buildString {
            appendLine("TableGrip includes the following open source libraries, under the licenses named.")
            appendLine("License texts: https://spdx.org/licenses/<id>.html. JDBC drivers are not included: they are")
            appendLine("downloaded separately at the user's request, under their own licenses.")
            appendLine()
            appendLine("Native components, MIT (https://spdx.org/licenses/MIT.html):")
            appendLine("  Sparkle $sparkle (macOS updater), https://github.com/sparkle-project/Sparkle")
            appendLine("  WinSparkle $winSparkle (Windows updater), https://github.com/vslavik/winsparkle")
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

// region Command line from source
//
// `scripts/tablegrip` runs the app's main class from here, so `tablegrip mcp` works without packaging: the app's jar and
// runtime classpath in lib/, and bin/tablegrip, which starts them on the build's JDK. Installed apps get their
// `tablegrip` command from Settings › MCP instead.

val cliDir = layout.buildDirectory.dir("cli")

val installCli by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Puts the app's classpath and a tablegrip launcher in build/cli, for scripts/tablegrip."
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdkLevel) }.map { it.executablePath.asFile.absolutePath }
    inputs.property("java", java)
    val output = cliDir
    from(tasks.named("jar")) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
    into(output)
    // Compose publishes some artifacts under two groups with the same file name; one copy is enough.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doLast {
        val bin = output.get().dir("bin").asFile.apply { mkdirs() }
        bin.resolve("tablegrip").apply {
            writeText(
                "#!/bin/sh\n" +
                    "# Runs TableGrip from this build (see scripts/tablegrip). Profile: \$TABLEGRIP_HOME, else ~/.tablegrip-dev.\n" +
                    "lib=\"\$(cd \"\$(dirname \"\$0\")/../lib\" && pwd)\"\n" +
                    "exec \"${java.get()}\" --enable-native-access=ALL-UNNAMED -Dtablegrip.home=\"\${TABLEGRIP_HOME:-\$HOME/.tablegrip-dev}\" " +
                    "-cp \"\$lib/*\" dev.tablegrip.app.MainKt \"\$@\"\n",
            )
            setExecutable(true)
        }
    }
}

// endregion

tasks {
    check { dependsOn(verifyNoBundledDrivers, thirdPartyNotices) }
    processResources {
        from(thirdPartyNotices)
        from(versionResource)
        from(rootProject.layout.projectDirectory.file("LICENSE"), rootProject.layout.projectDirectory.file("NOTICE"))
        // Renamed to the adapter's name, which DriverLibrary looks up as engines/<name>.jar.
        from(engineAdapters) {
            into("engines")
            rename { it.substringBefore('-').removeSuffix(".jar") + ".jar" }
        }
    }
    matching { it.name.startsWith("package") }.configureEach { dependsOn(verifyNoBundledDrivers) }
    matching { it.name == "prepareAppResources" }.configureEach { dependsOn(downloadWinSparkle) }

    // Adds Sparkle, the bridge and the Liquid Glass icon to the .app jpackage made, then signs them and the app again,
    // inside out.
    matching { it.name == "createDistributable" }.configureEach {
        if (isMacHost) {
            dependsOn(downloadSparkle, buildUpdaterBridge)
            val sparkle = sparkleDir
            val bridge = updaterBridge
            val iconAssets = appIcons.file("Assets.car")
            inputs.file(iconAssets)
            val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
            val entitlements = macEntitlements.asFile
            val sign = providers.gradleProperty("compose.desktop.mac.sign").orNull == "true"
            val identity = providers.gradleProperty("compose.desktop.mac.signing.identity").orNull?.let { "Developer ID Application: $it" }
            val keychain = providers.gradleProperty("compose.desktop.mac.signing.keychain").orNull
            doLast {
                val app = appDir.get().asFile.listFiles { file -> file.name.endsWith(".app") }!!.single()
                val frameworks = app.resolve("Contents/Frameworks").apply { mkdirs() }
                val framework = frameworks.resolve("Sparkle.framework")
                framework.deleteRecursively()
                // ditto keeps the framework's symlinks, which codesign needs.
                Shell.run("ditto", sparkle.get().asFile.resolve("Sparkle.framework").absolutePath, framework.absolutePath)
                // The XPC services are for sandboxed apps; TableGrip isn't one.
                framework.resolve("Versions/B/XPCServices").deleteRecursively()
                framework.resolve("XPCServices").delete()
                bridge.get().asFile.copyTo(frameworks.resolve("libTableGripUpdater.dylib"), overwrite = true)
                // Found through CFBundleIconName; jpackage only knows .icns.
                iconAssets.asFile.copyTo(app.resolve("Contents/Resources/Assets.car"), overwrite = true)

                val signer = if (sign) requireNotNull(identity) { "compose.desktop.mac.signing.identity is needed to sign" } else "-"
                fun codesign(target: File, vararg extra: String) {
                    val command = mutableListOf("codesign", "--force", "--options", "runtime", "--sign", signer)
                    command += if (sign) "--timestamp" else "--timestamp=none"
                    if (sign && keychain != null) command += listOf("--keychain", keychain)
                    command += extra
                    command += target.absolutePath
                    Shell.run(*command.toTypedArray())
                }
                codesign(framework.resolve("Versions/B/Autoupdate"))
                codesign(framework.resolve("Versions/B/Updater.app"))
                codesign(framework)
                codesign(frameworks.resolve("libTableGripUpdater.dylib"))
                // Compose signs the .dylib and .jnilib files inside jars but not other native libraries, such as
                // jkeychain's osxkeychain.so, which notarization then rejects as unsigned. Each one still unsigned is
                // signed and written back into its jar, before the app's own signature seals the jars.
                app.resolve("Contents/app").listFiles { file -> file.name.endsWith(".jar") }!!.forEach { jar ->
                    val natives = ZipFile(jar).use { zip ->
                        zip.entries().asSequence().map { it.name }
                            .filter { name -> listOf(".so", ".dylib", ".jnilib").any(name::endsWith) }
                            .associateWith { name -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
                    }
                    // Mach-O only (thin or universal): jars carry Linux and Windows libraries too, which macOS never loads.
                    val machO = setOf(0xfeedfacf.toInt(), 0xcffaedfe.toInt(), 0xfeedface.toInt(), 0xcefaedfe.toInt(), 0xcafebabe.toInt())
                    val signed = natives.filterValues { it.size >= 4 && ByteBuffer.wrap(it).int in machO }.mapNotNull { (name, bytes) ->
                        val file = File(temporaryDir, "natives/${jar.name}/$name").apply { parentFile.mkdirs(); writeBytes(bytes) }
                        val unsigned = ProcessBuilder("codesign", "--verify", file.absolutePath).start().waitFor() != 0
                        if (!unsigned) return@mapNotNull null
                        codesign(file)
                        name to file.readBytes()
                    }.toMap()
                    if (signed.isEmpty()) return@forEach
                    val rewritten = File(temporaryDir, jar.name)
                    ZipFile(jar).use { zip ->
                        ZipOutputStream(rewritten.outputStream()).use { out ->
                            zip.entries().asSequence().forEach { entry ->
                                out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                                signed[entry.name]?.let(out::write) ?: zip.getInputStream(entry).use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                    }
                    rewritten.copyTo(jar, overwrite = true)
                    logger.lifecycle("Signed ${signed.keys.joinToString()} in ${jar.name}")
                }
                codesign(app, "--entitlements", entitlements.absolutePath)
                Shell.run("codesign", "--verify", "--deep", "--strict", app.absolutePath)
            }
        }
    }

    test {
        useJUnitPlatform()
        providers.gradleProperty("snapshot.dir").orNull?.let { systemProperty("snapshot.dir", it) }
        providers.gradleProperty("snapshot.db").orNull?.let { systemProperty("snapshot.db", it) }
        providers.gradleProperty("snapshot.lang").orNull?.let { systemProperty("snapshot.lang", it) }
        listOf("it.network", "it.postgres", "it.redis", "it.mongo", "it.etcd").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
    }

    withType<JavaExec> {
        // The Compose Gradle plugin registers its run tasks in afterEvaluate.
        afterEvaluate {
            // Jewel needs the UI fixes in the JetBrains Runtime.
            val jbr =
                project.javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(jdkLevel)
                    vendor = JvmVendorSpec.JETBRAINS
                }
            // macOS 26's Dock ignores -Xdock:name and labels an app without a bundle after its executable ("java"), so on
            // a Mac the JVM starts as "TableGrip Dev" (see DevRuntime).
            val devRuntime = layout.buildDirectory.dir("dev-runtime")
            val mac = isMacHost
            javaLauncher = if (mac) jbr.zip(devRuntime) { launcher, dir -> DevRuntime.Launcher(launcher, dir) } else jbr
            doFirst {
                if (mac) DevRuntime.make(jbr.get(), devRuntime.get())
                setExecutable(javaLauncher.get().executablePath.asFile.absolutePath)
            }
        }
        // Added on execution: the Compose run task replaces the JVM arguments it was configured with.
        // Started from source, macOS would otherwise name the app after the main class ("MainKt").
        // Without an app bundle macOS also shows Java's generic icon, in the Dock and in About TableGrip (see MacAboutPanel).
        val devIcon = layout.projectDirectory.file("src/main/resources/icon/TableGrip-macOS.png").asFile.absolutePath
        doFirst { jvmArgs("-Dcompose.interop.blending=true", "-Xdock:name=TableGrip Dev", "-Xdock:icon=$devIcon") }
        systemProperty("apple.awt.application.name", "TableGrip Dev")
        // Development runs keep their own profile/settings directory, so they never touch the installed app's;
        // `./gradlew :app:run -Ptablegrip.home=/tmp/dg` runs against a throwaway one instead.
        systemProperty(
            "tablegrip.home",
            providers.gradleProperty("tablegrip.home").getOrElse("${System.getProperty("user.home")}/.tablegrip-dev"),
        )
    }
}
