plugins {
    alias(libs.plugins.kotlinJvm)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin { jvmToolchain(jdkLevel) }

// Datagrok's MongoDB adapter. Its jar ships inside the app but stays off the app's classpath: DriverLibrary loads it in
// one class loader with the MongoDB driver jars the user downloaded. So both core (from the app) and the driver (from the
// download) are only compile-time dependencies here.
dependencies {
    compileOnly(projects.core)
    compileOnly(libs.mongodb.driver.sync)

    testImplementation(projects.core)
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in server tests: -Pit.mongo=localhost:57017 (see MongoClientTest).
    listOf("it.mongo", "it.ferretdb").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
}
