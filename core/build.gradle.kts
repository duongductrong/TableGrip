import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

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

    // No JDBC driver ships with TableGrip: users download them on demand (see DriverLibrary). Tests aren't distributed,
    // so they keep the drivers on their classpath.
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jdbc.sqlite)
    testRuntimeOnly(libs.jdbc.postgresql)
    testRuntimeOnly(libs.jdbc.mysql)
    testRuntimeOnly(libs.jdbc.mariadb)
    testRuntimeOnly(libs.jdbc.h2)
    testRuntimeOnly(libs.jdbc.hsqldb)
    testRuntimeOnly(libs.jdbc.derby)
    testRuntimeOnly(libs.jdbc.mssql)
    testRuntimeOnly(libs.jdbc.clickhouse)
    testRuntimeOnly(libs.jdbc.duckdb)
    testImplementation(libs.apache.sshd.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Opt-in server tests: -Pit.postgres=localhost:55432 -Pit.mysql=localhost:53306 (and it.cockroach, it.timescale, it.tidb,
    // it.mssql, it.clickhouse: see ServerEnginesIntegrationTest); -Pit.network=true downloads drivers.
    listOf("it.postgres", "it.mysql", "it.network", "it.cockroach", "it.timescale", "it.tidb", "it.mssql", "it.clickhouse", "it.etcd", "it.etcdauth").forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
    testLogging { showStandardStreams = providers.gradleProperty("it.postgres").isPresent || providers.gradleProperty("it.mysql").isPresent }
}

// region Driver catalog
//
// The drivers TableGrip offers to download, and the license every jar they pull in is under. A person reads each license
// before adding it here: POMs often omit or misstate theirs, and a version bump can change it. `lockDrivers` writes the
// lockfile the app downloads from; `verifyDriverLock` (part of `check`) fails when the lockfile is stale or a jar's
// license isn't allowed. Drivers under GPL or a vendor's own license never go here: users bring those jars themselves.

/** Licenses a catalog driver may be under, by SPDX id. Weak copyleft is allowed only because the user downloads it. */
val permissiveLicenses = setOf("Apache-2.0", "MIT", "MIT-0", "BSD-2-Clause", "BSD-3-Clause", "MPL-2.0", "EPL-1.0", "EPL-2.0")
val weakCopyleftLicenses = setOf("LGPL-2.1-or-later")

data class CatalogDriver(
    val id: String,
    val name: String,
    val library: Provider<MinimalExternalModuleDependency>,
    val className: String,
    val licenseUrl: String,
    val sourceUrl: String,
)

val driverCatalog =
    listOf(
        CatalogDriver(
            "postgresql", "PostgreSQL JDBC Driver", libs.jdbc.postgresql, "org.postgresql.Driver",
            "https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE", "https://github.com/pgjdbc/pgjdbc",
        ),
        CatalogDriver(
            "mariadb", "MariaDB Connector/J", libs.jdbc.mariadb, "org.mariadb.jdbc.Driver",
            "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html", "https://github.com/mariadb-corporation/mariadb-connector-j",
        ),
        CatalogDriver(
            "sqlite", "SQLite JDBC (Xerial)", libs.jdbc.sqlite, "org.sqlite.JDBC",
            "https://github.com/xerial/sqlite-jdbc/blob/master/LICENSE", "https://github.com/xerial/sqlite-jdbc",
        ),
        CatalogDriver(
            "h2", "H2 Database Engine", libs.jdbc.h2, "org.h2.Driver",
            "https://h2database.com/html/license.html", "https://github.com/h2database/h2database",
        ),
        CatalogDriver(
            "hsqldb", "HyperSQL (HSQLDB)", libs.jdbc.hsqldb, "org.hsqldb.jdbc.JDBCDriver",
            "https://hsqldb.org/web/hsqlLicense.html", "https://sourceforge.net/projects/hsqldb/",
        ),
        CatalogDriver(
            "derby", "Apache Derby (embedded)", libs.jdbc.derby, "org.apache.derby.iapi.jdbc.AutoloadedDriver",
            "https://www.apache.org/licenses/LICENSE-2.0", "https://github.com/apache/derby",
        ),
        CatalogDriver(
            "sqlserver", "Microsoft JDBC Driver for SQL Server", libs.jdbc.mssql, "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "https://github.com/microsoft/mssql-jdbc/blob/main/LICENSE", "https://github.com/microsoft/mssql-jdbc",
        ),
        CatalogDriver(
            "clickhouse", "ClickHouse JDBC", libs.jdbc.clickhouse, "com.clickhouse.jdbc.Driver",
            "https://github.com/ClickHouse/clickhouse-java/blob/main/LICENSE", "https://github.com/ClickHouse/clickhouse-java",
        ),
        // Not JDBC: TableGrip's Redis adapter (engines/redis) runs on this client; the class is the adapter's.
        CatalogDriver(
            "lettuce", "Lettuce (Redis client)", libs.lettuce, "dev.tablegrip.engines.redis.RedisClient",
            "https://github.com/redis/lettuce/blob/main/LICENSE", "https://github.com/redis/lettuce",
        ),
        CatalogDriver(
            "mongodb", "MongoDB Java Driver", libs.mongodb.driver.sync, "dev.tablegrip.engines.mongodb.MongoClient",
            "https://github.com/mongodb/mongo-java-driver/blob/main/LICENSE.txt", "https://github.com/mongodb/mongo-java-driver",
        ),
        CatalogDriver(
            "duckdb", "DuckDB JDBC", libs.jdbc.duckdb, "org.duckdb.DuckDBDriver",
            "https://github.com/duckdb/duckdb-java/blob/main/LICENSE", "https://github.com/duckdb/duckdb-java",
        ),
    )

/** Reviewed license of every jar a catalog driver resolves to, by `group:name`. */
val artifactLicenses =
    mapOf(
        "org.postgresql:postgresql" to "BSD-2-Clause",
        "org.checkerframework:checker-qual" to "MIT",
        "org.mariadb.jdbc:mariadb-java-client" to "LGPL-2.1-or-later",
        "org.xerial:sqlite-jdbc" to "Apache-2.0",
        // Dual-licensed MPL-2.0 / EPL-1.0.
        "com.h2database:h2" to "MPL-2.0",
        // The HSQLDB license is the three-clause BSD text with the HSQL Development Group as holder.
        "org.hsqldb:hsqldb" to "BSD-3-Clause",
        "org.apache.derby:derby" to "Apache-2.0",
        "org.apache.derby:derbyshared" to "Apache-2.0",
        "com.microsoft.sqlserver:mssql-jdbc" to "MIT",
        "org.duckdb:duckdb_jdbc" to "MIT",
        // ClickHouse JDBC and what it pulls in. Blank POMs were read from their parents (guava-parent, the ASF's
        // commons-parent and httpcomponents-parent, antlr4-master).
        "com.clickhouse:clickhouse-jdbc" to "Apache-2.0",
        "com.clickhouse:jdbc-v2" to "Apache-2.0",
        "com.clickhouse:client-v2" to "Apache-2.0",
        "com.clickhouse:clickhouse-client" to "Apache-2.0",
        "com.clickhouse:clickhouse-http-client" to "Apache-2.0",
        "com.clickhouse:clickhouse-data" to "Apache-2.0",
        "at.yawk.lz4:lz4-java" to "Apache-2.0",
        "com.github.luben:zstd-jni" to "BSD-2-Clause",
        "com.google.errorprone:error_prone_annotations" to "Apache-2.0",
        "com.google.guava:guava" to "Apache-2.0",
        "com.google.guava:failureaccess" to "Apache-2.0",
        "com.google.guava:listenablefuture" to "Apache-2.0",
        "com.google.j2objc:j2objc-annotations" to "Apache-2.0",
        "commons-codec:commons-codec" to "Apache-2.0",
        "commons-io:commons-io" to "Apache-2.0",
        "org.antlr:antlr4-runtime" to "BSD-3-Clause",
        "org.apache.commons:commons-compress" to "Apache-2.0",
        "org.apache.commons:commons-lang3" to "Apache-2.0",
        "org.apache.httpcomponents.client5:httpclient5" to "Apache-2.0",
        "org.apache.httpcomponents.core5:httpcore5" to "Apache-2.0",
        "org.apache.httpcomponents.core5:httpcore5-h2" to "Apache-2.0",
        "org.jspecify:jspecify" to "Apache-2.0",
        "org.ow2.asm:asm" to "BSD-3-Clause",
        "org.roaringbitmap:RoaringBitmap" to "Apache-2.0",
        "org.slf4j:slf4j-api" to "MIT",
        // Lettuce and what it pulls in; Netty's modules take the license of netty-parent.
        "io.lettuce:lettuce-core" to "MIT",
        "io.netty:netty-buffer" to "Apache-2.0",
        "io.netty:netty-codec-base" to "Apache-2.0",
        "io.netty:netty-codec-dns" to "Apache-2.0",
        "io.netty:netty-common" to "Apache-2.0",
        "io.netty:netty-handler" to "Apache-2.0",
        "io.netty:netty-resolver" to "Apache-2.0",
        "io.netty:netty-resolver-dns" to "Apache-2.0",
        "io.netty:netty-transport" to "Apache-2.0",
        "io.netty:netty-transport-native-unix-common" to "Apache-2.0",
        "io.projectreactor:reactor-core" to "Apache-2.0",
        "org.reactivestreams:reactive-streams" to "MIT-0",
        "redis.clients.authentication:redis-authx-core" to "MIT",
        "org.mongodb:mongodb-driver-sync" to "Apache-2.0",
        "org.mongodb:mongodb-driver-core" to "Apache-2.0",
        "org.mongodb:bson" to "Apache-2.0",
        "org.mongodb:bson-record-codec" to "Apache-2.0",
    )

val driverConfigurations =
    driverCatalog.associate { driver ->
        val configuration = configurations.create("driver-${driver.id}") {
            isCanBeConsumed = false
            isTransitive = true
        }
        dependencies.add(configuration.name, driver.library)
        driver.id to configuration
    }

/** The lockfile's content: plain values and providers only, so the tasks stay configuration-cache compatible. */
val driverLockContent: Provider<String> =
    driverCatalog
        .map { driver ->
            driverConfigurations.getValue(driver.id).incoming.artifacts.resolvedArtifacts.map { artifacts ->
                val jars =
                    artifacts.map { artifact ->
                        val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                        val module = "${id.group}:${id.module}"
                        val license = artifactLicenses[module] ?: throw GradleException(
                            "No reviewed license for $module ${id.version} (needed by ${driver.id}). Read it and add it to artifactLicenses."
                        )
                        if (license !in permissiveLicenses && license !in weakCopyleftLicenses) {
                            throw GradleException("$module is under $license, which a catalog driver may not use.")
                        }
                        val file = artifact.file
                        val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                        val path = "${id.group.replace('.', '/')}/${id.module}/${id.version}/${file.name}"
                        """{"coordinates": "$module:${id.version}", "path": "$path", "sha256": "$sha256", "size": ${file.length()}, "license": "$license"}"""
                    }
                val main = driver.library.get()
                val license = artifactLicenses.getValue("${main.module.group}:${main.module.name}")
                val tier = if (artifacts.all { artifactLicenses[(it.id.componentIdentifier as ModuleComponentIdentifier).let { c -> "${c.group}:${c.module}" }] in permissiveLicenses }) "Permissive" else "WeakCopyleft"
                buildString {
                    append("    {\n")
                    append("      \"id\": \"${driver.id}\",\n")
                    append("      \"name\": \"${driver.name}\",\n")
                    append("      \"version\": \"${main.version}\",\n")
                    append("      \"className\": \"${driver.className}\",\n")
                    append("      \"license\": \"$license\",\n")
                    append("      \"tier\": \"$tier\",\n")
                    append("      \"licenseUrl\": \"${driver.licenseUrl}\",\n")
                    append("      \"sourceUrl\": \"${driver.sourceUrl}\",\n")
                    append("      \"artifacts\": [\n")
                    append(jars.joinToString(",\n") { "        $it" })
                    append("\n      ]\n")
                    append("    }")
                }
            }
        }
        .reduce { acc, next -> acc.zip(next) { a, b -> "$a,\n$b" } }
        .map { "{\n  \"drivers\": [\n$it\n  ]\n}\n" }

val driverLockFile = layout.projectDirectory.file("src/main/resources/dev/tablegrip/core/drivers/drivers.lock.json")

tasks.register("lockDrivers") {
    group = "tablegrip"
    description = "Resolves the driver catalog and writes drivers.lock.json (coordinates, sha256 and license of every jar)."
    val content = driverLockContent
    val output = driverLockFile.asFile
    // The resolved content is the input: a catalog change must rewrite the file even when the file itself is unchanged.
    inputs.property("lock", content)
    outputs.file(output)
    doLast { output.writeText(content.get()) }
}

val verifyDriverLock by tasks.registering {
    group = "verification"
    description = "Fails when drivers.lock.json doesn't match the catalog, or a driver jar's license isn't allowed."
    val content = driverLockContent
    val lock = driverLockFile.asFile
    inputs.file(lock)
    doLast {
        if (!lock.exists() || lock.readText() != content.get()) {
            throw GradleException("drivers.lock.json is out of date with the driver catalog. Run ./gradlew :core:lockDrivers and review the diff.")
        }
    }
}

tasks.check { dependsOn(verifyDriverLock) }

// endregion
