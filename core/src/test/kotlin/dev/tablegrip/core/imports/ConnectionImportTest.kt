package dev.tablegrip.core.imports

import dev.tablegrip.core.connection.ConnectionString
import dev.tablegrip.core.connection.DatabaseType
import dev.tablegrip.core.connection.Environment
import dev.tablegrip.core.connection.SshAuth
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ConnectionImportTest {
    @TempDir
    lateinit var home: Path

    private fun write(relative: String, text: String): Path = home.resolve(relative).also {
        it.parent.createDirectories()
        it.writeText(text.trimIndent())
    }

    @Test
    fun `pgpass entries become connections, honouring escapes and skipping wildcards`() {
        val file = write(".pgpass", """
            # comment
            db.example.com:5433:shop:app:s3cr\:et
            localhost:*:*:postgres:pw
            *:*:*:reporting:other
        """)
        val scan = PgPassImporter.readPassFile(file)
        assertEquals(2, scan.candidates.size)
        val shop = scan.candidates[0]
        assertEquals("app@db.example.com/shop", shop.config.name)
        assertEquals(5433, shop.config.port)
        assertEquals("s3cr:et", shop.password)
        assertNull(scan.candidates[1].config.port)
        assertEquals(1, scan.skipped.size)
    }

    @Test
    fun `pgpass fills in passwords for PostgreSQL connections imported without one`() {
        val pass = write(".pgpass", "db:5432:app:alice:from-pgpass")
        val config = ConnectionImport.fromParsed(ConnectionString.parse("postgresql://alice@db/app"), "app")
        val filled = ConnectionImport.fillPasswordsFromPgPass(listOf(ImportCandidate(config, origin = "test")), pass)
        assertEquals("from-pgpass", filled.single().password)
    }

    @Test
    fun `pg_service sections become named connections`() {
        val file = write(".pg_service.conf", """
            [billing]
            host=billing.internal
            port=6432
            dbname=billing
            user=svc
            sslmode=require
        """)
        val config = PgPassImporter.readServiceFile(file).candidates.single().config
        assertEquals("billing", config.name)
        assertEquals("billing.internal", config.host)
        assertEquals(6432, config.port)
        assertEquals(mapOf("sslmode" to "require"), config.properties)
    }

    @Test
    fun `env files yield URLs and split Laravel variables`() {
        val project = home.resolve("shop")
        write("shop/.env", """
            APP_URL=https://shop.test
            DATABASE_URL="postgres://app:p%40ss@localhost:5432/shop"
            export REDIS_URL=rediss://default:redispw@cache:6380
            DB_CONNECTION=mysql
            DB_HOST=127.0.0.1
            DB_PORT=3306
            DB_DATABASE=${'$'}{APP_NAME:-laravel}
            DB_USERNAME=root
            DB_PASSWORD=secret # local only
        """)
        write("shop/node_modules/pkg/.env", "DATABASE_URL=postgres://ignored/db")
        val scan = DotEnvImporter.scan(project)
        val byName = scan.candidates.associateBy { it.config.name }
        assertEquals(setOf("shop · DATABASE_URL", "shop · REDIS_URL", "shop · DB_HOST"), byName.keys)
        assertEquals("p@ss", byName.getValue("shop · DATABASE_URL").password)
        val redis = byName.getValue("shop · REDIS_URL")
        assertEquals(DatabaseType.Redis, redis.config.type)
        assertEquals(6380, redis.config.port)
        assertEquals("true", redis.config.properties["ssl"])
        val laravel = byName.getValue("shop · DB_HOST")
        assertEquals(DatabaseType.MySQL, laravel.config.type)
        assertEquals("laravel", laravel.config.database)
        assertEquals("secret", laravel.password)
        assertEquals("shop", laravel.config.group)
    }

    @Test
    fun `DBeaver data sources with encrypted credentials, folders, SSH and connection types`() {
        val file = write("Library/DBeaverData/workspace6/General/.dbeaver/data-sources.json", """
            {
              "folders": {"Clients": {}},
              "connections": {
                "postgres-jdbc-1": {
                  "provider": "postgresql", "driver": "postgres-jdbc", "name": "Orders prod", "folder": "Clients",
                  "save-password": true, "read-only": true,
                  "configuration": {
                    "host": "orders.db", "port": "5433", "database": "orders", "url": "jdbc:postgresql://orders.db:5433/orders",
                    "type": "prod",
                    "handlers": {"ssh_tunnel": {"enabled": true, "properties": {"host": "bastion", "port": 2222, "authType": "PUBLIC_KEY", "keyPath": "/keys/id"}}}
                  }
                },
                "sqlite-1": {"provider": "sqlite", "driver": "sqlite_jdbc", "name": "Local file", "configuration": {"database": "/tmp/app.db", "url": "jdbc:sqlite:/tmp/app.db"}},
                "maria-1": {"provider": "mysql", "driver": "mariaDB", "name": "Maria", "configuration": {"url": "jdbc:mariadb://m:3307/x", "configurationType": "URL"}},
                "oracle-1": {"provider": "oracle", "driver": "oracle_thin", "name": "Ora", "configuration": {"url": "jdbc:oracle:thin:@o:1521/XE"}},
                "cassandra-1": {"provider": "cassandra", "driver": "cassandra", "name": "Cass", "configuration": {"host": "c"}}
              }
            }
        """)
        file.resolveSibling("credentials-config.json").writeBytes(
            DBeaverImporter.encryptCredentials("""{"postgres-jdbc-1":{"#connection":{"user":"orders","password":"pw1"},"network/ssh_tunnel":{"user":"ops"}}}"""),
        )
        assertEquals(listOf(file), DBeaverImporter.locations(home, emptyMap()))
        val scan = DBeaverImporter.read(file)
        val byName = scan.candidates.associateBy { it.config.name }
        val orders = byName.getValue("Orders prod")
        assertEquals(DatabaseType.PostgreSQL, orders.config.type)
        assertEquals("orders.db", orders.config.host)
        assertEquals(5433, orders.config.port)
        assertEquals("orders", orders.config.user)
        assertEquals("pw1", orders.password)
        assertEquals("Clients", orders.config.group)
        assertEquals(Environment.Production, orders.config.environment)
        assertTrue(orders.config.readOnly)
        assertTrue(orders.config.confirmWrites)
        val ssh = assertNotNull(orders.config.ssh)
        assertEquals("bastion", ssh.host)
        assertEquals(2222, ssh.port)
        assertEquals("ops", ssh.user)
        assertEquals(SshAuth.PrivateKey, ssh.auth)
        assertEquals("/keys/id", ssh.privateKeyPath)
        assertEquals("/tmp/app.db", byName.getValue("Local file").config.database)
        assertEquals(DatabaseType.MariaDB, byName.getValue("Maria").config.type)
        assertEquals(3307, byName.getValue("Maria").config.port)
        assertEquals(DatabaseType.OtherJdbc, byName.getValue("Ora").config.type)
        assertEquals(listOf("DBeaver: “Cass” (cassandra) isn't supported."), scan.skipped)
    }

    @Test
    fun `IDE data sources found through recent projects, with local user names and SSH`() {
        write("Library/Application Support/JetBrains/IDE2024.1/options/recentProjects.xml", """
            <application><component name="RecentProjectsManager"><option name="additionalInfo"><map>
              <entry key="${'$'}USER_HOME${'$'}/work/api"><value/></entry>
            </map></option></component></application>
        """)
        write("Library/Application Support/JetBrains/IDE2024.1/options/sshConfigs.xml", """
            <application><component name="SshConfigs"><configs>
              <sshConfig authType="PASSWORD" host="jump.example.com" id="ssh-1" port="22" username="deploy" />
            </configs></component></application>
        """)
        val shared = write("work/api/.idea/dataSources.xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4"><component name="DataSourceManagerImpl" format="xml" multifile-model="true">
              <data-source source="LOCAL" name="api@localhost" uuid="u1">
                <driver-ref>mysql.8</driver-ref>
                <jdbc-url>jdbc:mysql://localhost:3306/api</jdbc-url>
              </data-source>
              <data-source source="LOCAL" name="local file" uuid="u2">
                <driver-ref>sqlite.xerial</driver-ref>
                <jdbc-url>jdbc:sqlite:${'$'}PROJECT_DIR${'$'}/data/app.db</jdbc-url>
              </data-source>
              <data-source source="LOCAL" name="cache" uuid="u3">
                <driver-ref>redis</driver-ref>
                <jdbc-url>jdbc:redis://localhost:6379/0</jdbc-url>
              </data-source>
            </component></project>
        """)
        write("work/api/.idea/dataSources.local.xml", """
            <project version="4"><component name="dataSourceStorageLocal">
              <data-source name="api@localhost" uuid="u1">
                <database-info product="MySQL" dbms="MARIADB" />
                <user-name>api_user</user-name>
                <ssh-properties><enabled>true</enabled><ssh-config-id>ssh-1</ssh-config-id></ssh-properties>
              </data-source>
            </component></project>
        """)
        assertEquals(listOf(shared), IdeDataSourcesImporter.locations(home))
        val scan = IdeDataSourcesImporter.read(shared, home)
        val byName = scan.candidates.associateBy { it.config.name }
        val api = byName.getValue("api@localhost")
        assertEquals(DatabaseType.MariaDB, api.config.type)
        assertEquals("api_user", api.config.user)
        assertEquals("api", api.config.group)
        assertEquals(SshAuth.Password, api.config.ssh?.auth)
        assertEquals("deploy", api.config.ssh?.user)
        assertEquals(home.resolve("work/api/data/app.db").toString(), byName.getValue("local file").config.database)
        assertEquals(DatabaseType.Redis, byName.getValue("cache").config.type)
    }

    @Test
    fun `Connections plist with groups, environments, files and SSH`() {
        val file = write("clients/Data/Connections.plist", """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><array>
              <dict>
                <key>ConnectionName</key><string>Payments</string>
                <key>Driver</key><string>PostgreSQL</string>
                <key>DatabaseHost</key><string>pay.db</string>
                <key>DatabasePort</key><string>5432</string>
                <key>DatabaseName</key><string>payments</string>
                <key>DatabaseUser</key><string>pay</string>
                <key>Enviroment</key><string>staging</string>
                <key>GroupID</key><string>G1</string>
                <key>isOverSSH</key><true/>
                <key>ServerAddress</key><string>bastion.pay</string>
                <key>ServerPort</key><string>22</string>
                <key>ServerUser</key><string>ec2-user</string>
                <key>isUsePrivateKey</key><true/>
                <key>tLSMode</key><integer>2</integer>
              </dict>
              <dict>
                <key>ConnectionName</key><string>Notes</string>
                <key>Driver</key><string>SQLite</string>
                <key>DatabasePath</key><string>/Users/me/notes.db</string>
              </dict>
              <dict>
                <key>ConnectionName</key><string>Warehouse</string>
                <key>Driver</key><string>Snowflake</string>
              </dict>
            </array></plist>
        """)
        write("clients/Data/ConnectionGroups.plist", """
            <plist version="1.0"><array><dict><key>ID</key><string>G1</string><key>Name</key><string>Fintech</string></dict></array></plist>
        """)
        val scan = PlistConnectionsImporter.read(file)
        val payments = scan.candidates.first { it.config.name == "Payments" }.config
        assertEquals("Fintech", payments.group)
        assertEquals(Environment.Staging, payments.environment)
        assertEquals(mapOf("sslmode" to "require"), payments.properties)
        assertEquals("bastion.pay", payments.ssh?.host)
        assertEquals("ec2-user", payments.ssh?.user)
        val notes = scan.candidates.first { it.config.name == "Notes" }.config
        assertEquals(DatabaseType.SQLite, notes.type)
        assertEquals("/Users/me/notes.db", notes.database)
        assertEquals(1, scan.skipped.size)
    }

    @Test
    fun `discover lists only sources present, and detect recognises picked files`() {
        write(".pgpass", "h:5432:d:u:p")
        val dbeaver = write("Library/DBeaverData/workspace6/General/.dbeaver/data-sources.json", """{"connections": {}}""")
        val found = ConnectionImport.discover(home, emptyMap())
        assertEquals(listOf(ImportFormat.DBeaver, ImportFormat.PgPass), found.map { it.format })
        assertEquals(ImportFormat.DBeaver, ConnectionImport.detect(dbeaver)?.format)
        val renamed = write("exports/connections.txt", """{"connections": {"a": {"provider": "postgresql"}}}""")
        assertEquals(ImportFormat.DBeaver, ConnectionImport.detect(renamed)?.format)
        assertEquals(ImportFormat.DotEnv, ConnectionImport.detect(Files.createDirectories(home.resolve("proj")))?.format)
    }

    @Test
    fun `keys treat the same database written differently as one`() {
        val a = ConnectionImport.fromParsed(ConnectionString.parse("postgresql://u@localhost/app"), "a")
        val b = ConnectionImport.fromParsed(ConnectionString.parse("postgresql://u@127.0.0.1:5432/app"), "b")
        val c = ConnectionImport.fromParsed(ConnectionString.parse("postgresql://u@localhost/other"), "c")
        assertEquals(ConnectionImport.keyOf(a), ConnectionImport.keyOf(b))
        assertTrue(ConnectionImport.keyOf(a) != ConnectionImport.keyOf(c))
    }

    @Test
    fun `ssh config hosts with includes and wildcard defaults`() {
        write(".ssh/config", """
            Include conf.d/*
            Host bastion prod-jump
              HostName 10.0.0.5
              Port 2222
            Host *
              User ops
              IdentityFile ~/.ssh/id_ed25519
        """)
        write(".ssh/conf.d/work", """
            Host db-tunnel
              HostName tunnel.example.com
              User me
        """)
        val hosts = SshHosts.read(home).associateBy { it.alias }
        assertEquals(setOf("db-tunnel", "bastion", "prod-jump"), hosts.keys)
        assertEquals("me", hosts.getValue("db-tunnel").user)
        val bastion = hosts.getValue("bastion")
        assertEquals("10.0.0.5", bastion.hostName)
        assertEquals(2222, bastion.port)
        assertEquals("ops", bastion.user)
        assertEquals(home.resolve(".ssh/id_ed25519").toString(), bastion.identityFile)
    }
}
