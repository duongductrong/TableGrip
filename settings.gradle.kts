@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "tablegrip"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle auto-provision the JetBrains Runtime toolchain that Jewel requires.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // IntelliJ Platform icons used by Jewel's AllIconsKeys.
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

include(":core", ":mcp", ":app", ":engines:redis", ":engines:mongodb", ":engines:cassandra", ":engines:kafka", ":engines:zookeeper")
