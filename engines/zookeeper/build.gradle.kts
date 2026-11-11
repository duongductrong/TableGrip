plugins {
    alias(libs.plugins.kotlinJvm)
}

val jdkLevel = providers.gradleProperty("jdk.level").get().toInt()

kotlin { jvmToolchain(jdkLevel) }

// TableGrip's ZooKeeper adapter. Its jar ships inside the app but stays off the app's classpath: DriverLibrary loads it in one
// class loader with the client jars the user downloaded. So both core (from the app) and the client (from the download)
// are only compile-time dependencies here.
dependencies {
    compileOnly(projects.core)
    compileOnly(libs.zookeeper)

    testImplementation(projects.core)
    testImplementation(libs.zookeeper)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    // Opt-in server test: -Pit.zookeeper=localhost:port (see ZooKeeperClientTest).
    listOf("it.zookeeper").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
}
