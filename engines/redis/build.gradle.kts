plugins {
    alias(libs.plugins.kotlinJvm)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin { jvmToolchain(jdkLevel) }

// Datagrok's Redis adapter. Its jar ships inside the app but stays off the app's classpath: DriverLibrary loads it in
// one class loader with the Lettuce jars the user downloaded. So both core (from the app) and Lettuce (from the
// download) are only compile-time dependencies here.
dependencies {
    compileOnly(projects.core)
    compileOnly(libs.lettuce)

    testImplementation(projects.core)
    testImplementation(libs.lettuce)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in server tests: -Pit.redis=localhost:56379 -Pit.valkey=localhost:56380 (see RedisClientTest).
    listOf("it.redis", "it.valkey", "it.rediscluster").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
}
