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
    testRuntimeOnly(libs.jdbc.firebird)
    testRuntimeOnly(libs.jdbc.trino)
    testRuntimeOnly(libs.jdbc.presto)
    testRuntimeOnly(libs.jdbc.oceanbase)
    testRuntimeOnly(libs.jdbc.oracle)
    testRuntimeOnly(libs.jdbc.hive)
    testRuntimeOnly(libs.jdbc.ucanaccess)
    // Bring-your-own drivers, outside the catalog's licenses (see artifactLicenses): tests aren't distributed.
    testRuntimeOnly(libs.jdbc.snowflake)
    testRuntimeOnly(libs.jdbc.databricks)
    testRuntimeOnly(libs.jdbc.db2)
    testRuntimeOnly(libs.jdbc.hana)
    testRuntimeOnly(libs.jdbc.vertica)
    testRuntimeOnly(libs.jdbc.exasol)
    testRuntimeOnly(libs.jdbc.informix)
    testRuntimeOnly(libs.jdbc.iris)
    testImplementation(libs.apache.sshd.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * Drivers whose one jar carries unrelocated copies of libraries the tests use too (Kotlin, Guava, gRPC): tests load them
 * through DriverLibrary, in a class loader of their own, as the app does. See TestDrivers.
 */
val isolatedTestDrivers: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    isolatedTestDrivers(variantOf(libs.jdbc.spanner) { classifier("single-jar-with-dependencies") })
    isolatedTestDrivers(variantOf(libs.jdbc.bigquery) { classifier("all") })
}

tasks.test {
    useJUnitPlatform()
    val isolated: FileCollection = isolatedTestDrivers.incoming.files
    inputs.files(isolated)
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dtablegrip.test.isolatedDrivers=" + isolated.files.joinToString(File.pathSeparator)) }
    // Opt-in server tests: -Pit.postgres=localhost:55432 -Pit.mysql=localhost:53306 (and it.cockroach, it.timescale, it.tidb,
    // it.mssql, it.clickhouse, it.oracle, it.firebird, it.trino, it.yugabyte, it.questdb, it.starrocks, it.oceanbase,
    // it.presto, it.db2, it.informix, it.iris, it.hive, it.spark, it.spanner: see
    // ServerEnginesIntegrationTest and JdbcEnginesIntegrationTest); -Pit.network=true downloads drivers.
    listOf(
        "it.postgres", "it.mysql", "it.network", "it.cockroach", "it.timescale", "it.tidb", "it.mssql", "it.clickhouse", "it.etcd", "it.etcdauth",
        "it.oracle", "it.firebird", "it.trino", "it.yugabyte", "it.questdb", "it.starrocks", "it.oceanbase", "it.presto",
        "it.db2", "it.informix", "it.iris", "it.hive", "it.spark", "it.spanner",
        // Engines browsed as records: see SearchEnginesTest, DocumentEnginesTest, TimeSeriesEnginesTest and GraphEnginesTest.
        "it.elasticsearch", "it.opensearch", "it.meilisearch", "it.couchdb", "it.dynamodb", "it.influxdb", "it.victoriametrics", "it.prometheus", "it.neo4j", "it.solr", "it.qdrant", "it.rabbitmq", "it.influxdb3", "it.consul", "it.rqlite", "it.libsql", "it.weaviate", "it.chroma", "it.milvus",
    ).forEach { name -> providers.gradleProperty(name).orNull?.let { systemProperty(name, it) } }
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
    /** A published jar with every dependency inside, used alone instead of the main jar and its dependencies. */
    val classifier: String? = null,
    /** Groups of optional dependencies left out of the download (`group` or `group:module`). */
    val excludes: List<String> = emptyList(),
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
        // Not JDBC either: the Cassandra adapter (engines/cassandra), for Cassandra and ScyllaDB. JNR only gives the
        // driver native clocks and the process id, which it does without; leaving it out keeps a tri-licensed jar and
        // native code out of the download.
        CatalogDriver(
            "cassandra", "Apache Cassandra Java Driver", libs.cassandra.driver, "dev.tablegrip.engines.cassandra.CassandraClient",
            "https://github.com/apache/cassandra-java-driver/blob/4.x/LICENSE.txt", "https://github.com/apache/cassandra-java-driver",
            excludes = listOf("com.github.jnr"),
        ),
        CatalogDriver(
            "kafka", "Apache Kafka client", libs.kafka.clients, "dev.tablegrip.engines.kafka.KafkaClient",
            "https://github.com/apache/kafka/blob/trunk/LICENSE", "https://github.com/apache/kafka",
        ),
        // ZooKeeper's client without Logback (the app logs through SLF4J already) and the server's metrics, command line
        // and compression libraries, which the client doesn't load.
        CatalogDriver(
            "zookeeper", "Apache ZooKeeper client", libs.zookeeper, "dev.tablegrip.engines.zookeeper.ZooKeeperClient",
            "https://github.com/apache/zookeeper/blob/master/LICENSE.txt", "https://github.com/apache/zookeeper",
            excludes = listOf(
                "ch.qos.logback", "io.dropwizard.metrics", "commons-io", "commons-cli", "org.xerial.snappy",
                // netty-tcnative is only for OpenSSL TLS; the client uses the JDK's.
                "io.netty:netty-tcnative-boringssl-static", "io.netty:netty-tcnative-classes",
            ),
        ),
        CatalogDriver(
            "duckdb", "DuckDB JDBC", libs.jdbc.duckdb, "org.duckdb.DuckDBDriver",
            "https://github.com/duckdb/duckdb-java/blob/main/LICENSE", "https://github.com/duckdb/duckdb-java",
        ),
        CatalogDriver(
            "firebird", "Jaybird (Firebird JDBC)", libs.jdbc.firebird, "org.firebirdsql.jdbc.FBDriver",
            "https://github.com/FirebirdSQL/jaybird/blob/master/LICENSE.md", "https://github.com/FirebirdSQL/jaybird",
        ),
        CatalogDriver(
            "trino", "Trino JDBC", libs.jdbc.trino, "io.trino.jdbc.TrinoDriver",
            "https://github.com/trinodb/trino/blob/master/LICENSE", "https://github.com/trinodb/trino",
        ),
        CatalogDriver(
            "oceanbase", "OceanBase Connector/J", libs.jdbc.oceanbase, "com.oceanbase.jdbc.Driver",
            "https://github.com/oceanbase/obconnector-j/blob/master/LICENSE", "https://github.com/oceanbase/obconnector-j",
        ),
        CatalogDriver(
            "presto", "Presto JDBC", libs.jdbc.presto, "com.facebook.presto.jdbc.PrestoDriver",
            "https://github.com/prestodb/presto/blob/master/LICENSE", "https://github.com/prestodb/presto",
        ),
        // Apache Kyuubi's shaded Hive JDBC driver: HiveServer2's protocol, for Hive, Spark's Thrift server, Impala and Kyuubi.
        CatalogDriver(
            "hive", "Kyuubi Hive JDBC", libs.jdbc.hive, "org.apache.kyuubi.jdbc.KyuubiHiveDriver",
            "https://github.com/apache/kyuubi/blob/master/LICENSE", "https://github.com/apache/kyuubi",
        ),
        CatalogDriver(
            "spanner", "Cloud Spanner JDBC", libs.jdbc.spanner, "com.google.cloud.spanner.jdbc.JdbcDriver",
            "https://github.com/googleapis/java-spanner-jdbc/blob/main/LICENSE", "https://github.com/googleapis/java-spanner-jdbc",
            classifier = "single-jar-with-dependencies",
        ),
        CatalogDriver(
            "ucanaccess", "UCanAccess", libs.jdbc.ucanaccess, "net.ucanaccess.jdbc.UcanaccessDriver",
            "https://github.com/spannm/ucanaccess/blob/master/LICENSE", "https://github.com/spannm/ucanaccess",
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
        // Jaybird is LGPL-2.1-or-later; its extension interfaces are BSD-3-Clause, which LGPL already covers here.
        "org.firebirdsql.jdbc:jaybird" to "LGPL-2.1-or-later",
        // One shaded jar; what it bundles is Apache-2.0 (see its NOTICE).
        "io.trino:trino-jdbc" to "Apache-2.0",
        // A fork of MariaDB Connector/J; its sources keep MariaDB's "2.1 or (at your option) any later version" grant.
        "com.oceanbase:oceanbase-client" to "LGPL-2.1-or-later",
        // One shaded jar with no dependencies of its own. Presto is Apache-2.0; it bundles Guava, Jackson, OkHttp, Apache
        // HttpClient, Joda-Time, Kotlin, gRPC and OpenCensus (Apache-2.0), google-auth-library (BSD-3-Clause) and
        // FastDoubleParser (MIT). Its LICENSE.jol.txt (GPL-2.0 with the Classpath exception) is a leftover: no JOL class is in the jar.
        "com.facebook.presto:presto-jdbc" to "Apache-2.0",
        // One shaded jar with no dependencies of its own; its LICENSE and NOTICE list Hive's RPC classes, Thrift, Guava,
        // Commons, Curator, ZooKeeper, HttpClient, Netty, Arrow and Jackson (Apache-2.0), SLF4J and jcl-over-slf4j and
        // checker-qual (MIT). Jackson's FastDoubleParser is MIT, with a few routines ported from fast_float under the
        // Boost license, as in Presto JDBC. No native libraries.
        "org.apache.kyuubi:kyuubi-hive-jdbc-shaded" to "Apache-2.0",
        // The single-jar-with-dependencies build, with nothing relocated. Every bundled POM was read: the Google Cloud
        // client libraries, gax, gRPC, Netty, protobuf, Guava, Gson, Jackson, Tink, OpenTelemetry, OpenCensus, conscrypt,
        // CEL and HttpClient are Apache-2.0; Bouncy Castle and animal-sniffer MIT; threeten, re2j and jsr305 BSD-3-Clause.
        // conscrypt's and netty-tcnative's native libraries link BoringSSL, whose bundled LICENSE is Apache-2.0 (the old
        // OpenSSL/SSLeay terms are gone from it); FastDoubleParser as in Kyuubi's jar.
        "com.google.cloud:google-cloud-spanner-jdbc" to "Apache-2.0",
        // UCanAccess, and Jackcess (its maintainer's fork) under it; HSQLDB is listed above.
        "io.github.spannm:ucanaccess" to "Apache-2.0",
        "io.github.spannm:jackcess" to "Apache-2.0",
        // Not here, and brought by the user instead (DriverId.vendorUrl):
        // - Snowflake JDBC 4.4 (Apache-2.0) bundles javax.servlet-api and javax.annotation-api, under CDDL-1.1 or
        //   GPL-2.0 with the Classpath exception (internal/javax/servlet/LocalStrings.properties, internal/javax/annotation).
        // - Databricks JDBC 3.4 and Google's BigQuery JDBC 1.9 (both Apache-2.0) bundle org.json, which is "Public Domain"
        //   since 20231013 (META-INF/maven/org.json/json/pom.xml in Databricks', relocated classes in BigQuery's).
        // - IBM Db2 (IPLA), SAP HANA (SAP Developer License), Vertica (Vertica Client License), Exasol (EXAClient
        //   License), IBM Informix (IBM Informix JDBC License) and InterSystems IRIS (its IERTU terms) are proprietary.
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
        // The Apache Cassandra Java driver and what it pulls in (JNR left out, see the catalog). Its guava-shaded jar
        // relocates Guava (Apache-2.0); native-protocol and typesafe config are Apache-2.0, as are Dropwizard Metrics and
        // Jackson. HdrHistogram is dedicated to the public domain under CC0, or BSD-2-Clause where that isn't possible.
        "org.apache.cassandra:java-driver-core" to "Apache-2.0",
        "org.apache.cassandra:java-driver-guava-shaded" to "Apache-2.0",
        "com.datastax.oss:native-protocol" to "Apache-2.0",
        "com.typesafe:config" to "Apache-2.0",
        "io.dropwizard.metrics:metrics-core" to "Apache-2.0",
        "org.hdrhistogram:HdrHistogram" to "BSD-2-Clause",
        "com.fasterxml.jackson.core:jackson-core" to "Apache-2.0",
        "com.fasterxml.jackson.core:jackson-databind" to "Apache-2.0",
        "com.fasterxml.jackson.core:jackson-annotations" to "Apache-2.0",
        "io.netty:netty-codec" to "Apache-2.0",
        // The Apache Kafka client, and its compression codecs: zstd-jni is BSD-2-Clause, lz4-java and snappy-java
        // Apache-2.0 (each with its native libraries, under the same licenses).
        "org.apache.kafka:kafka-clients" to "Apache-2.0",
        "org.lz4:lz4-java" to "Apache-2.0",
        "org.xerial.snappy:snappy-java" to "Apache-2.0",
        // The Apache ZooKeeper client, its jute records and Yetus' audience annotations: all Apache-2.0.
        "org.apache.zookeeper:zookeeper" to "Apache-2.0",
        "org.apache.zookeeper:zookeeper-jute" to "Apache-2.0",
        "org.apache.yetus:audience-annotations" to "Apache-2.0",
        "io.netty:netty-transport-native-epoll" to "Apache-2.0",
        "io.netty:netty-transport-classes-epoll" to "Apache-2.0",
    )

val driverConfigurations =
    driverCatalog.associate { driver ->
        val classifier = driver.classifier
        val configuration = configurations.create("driver-${driver.id}") {
            isCanBeConsumed = false
            // A jar with its dependencies inside is the whole driver.
            isTransitive = classifier == null
            driver.excludes.forEach { exclude(mapOf("group" to it.substringBefore(':')) + (it.substringAfter(':', "").takeIf { m -> m.isNotEmpty() }?.let { m -> mapOf("module" to m) } ?: emptyMap())) }
        }
        dependencies.add(configuration.name, if (classifier == null) driver.library else dependencies.variantOf(driver.library) { classifier(classifier) })
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
