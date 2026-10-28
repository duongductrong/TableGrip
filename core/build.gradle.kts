plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinx.serialization)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin { jvmToolchain(jdkLevel) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.hikari)
    implementation(libs.java.keyring)
    implementation(libs.sshj)
    api(libs.slf4j.api)

    runtimeOnly(libs.jdbc.postgresql)
    runtimeOnly(libs.jdbc.mysql)
    runtimeOnly(libs.jdbc.mariadb)
    runtimeOnly(libs.jdbc.sqlite)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jdbc.sqlite)
    testImplementation(libs.apache.sshd.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Opt-in server tests: -Pit.postgres=localhost:55432 -Pit.mysql=localhost:53306
    listOf("it.postgres", "it.mysql").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
    testLogging { showStandardStreams = providers.gradleProperty("it.postgres").isPresent || providers.gradleProperty("it.mysql").isPresent }
}
