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
    compilerOptions { optIn.add("androidx.compose.foundation.ExperimentalFoundationApi") }
}

dependencies {
    implementation(projects.core)

    implementation(libs.jewel.intUi.standalone)
    implementation(libs.jewel.intUi.decoratedWindow)
    implementation(libs.intellijPlatform.icons)
    implementation(libs.jbr.api)

    implementation(compose.desktop.currentOs) { exclude(group = "org.jetbrains.compose.material") }
    implementation(libs.kotlinx.coroutines.swing)
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
    }
}
