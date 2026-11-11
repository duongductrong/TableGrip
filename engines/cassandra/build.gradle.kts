plugins {
    alias(libs.plugins.kotlinJvm)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin { jvmToolchain(jdkLevel) }

// TableGrip's Cassandra adapter, for Cassandra and ScyllaDB. Its jar ships inside the app but stays off the app's
// classpath: DriverLibrary loads it in one class loader with the driver jars the user downloaded. So both core (from the
// app) and the driver (from the download) are only compile-time dependencies here.
dependencies {
    compileOnly(projects.core)
    compileOnly(libs.cassandra.driver)

    testImplementation(projects.core)
    testImplementation(libs.cassandra.driver)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in server tests: -Pit.cassandra=localhost:59042 -Pit.scylladb=localhost:59043 (see CassandraClientTest).
    listOf("it.cassandra", "it.scylladb").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
}
