@file:Suppress("UnstableApiUsage")

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

dependencies {
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
}

compose.desktop {
    application {
        mainClass = "dev.datagrok.app.MainKt"
        // Skiko and the SQLite driver load native libraries.
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Datagrok"
            packageVersion = "1.0.0"
            description = "A modern database client"
            vendor = "Datagrok"
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported")

            macOS {
                dockName = "Datagrok"
                bundleID = "dev.datagrok.app"
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        providers.gradleProperty("snapshot.dir").orNull?.let { systemProperty("snapshot.dir", it) }
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
