plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinx.serialization)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin { jvmToolchain(jdkLevel) }

// The MCP server behind `tablegrip mcp`: JSON-RPC over stdio, on top of :core. No UI and no dependency beyond core's,
// so the installer ships nothing new and the server starts without a window.
dependencies {
    api(projects.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jdbc.sqlite)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The SQLite driver loads a native library.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
