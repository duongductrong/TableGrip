//! `ConnectionImportTest`, ported.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use tg_core::connection::string as connection_string;
use tg_core::connection::{Environment, SshAuth};
use tg_data::imports::{self, ImportCandidate, ImportFormat, dbeaver, dotenv, ide, pgpass, plist};
use tg_sql::model::DatabaseType;

/// Kotlin's `trimIndent`: drops the first and last blank lines and the common indentation.
fn trim_indent(text: &str) -> String {
    let mut lines: Vec<&str> = text.split('\n').collect();
    if lines.first().is_some_and(|l| l.trim().is_empty()) {
        lines.remove(0);
    }
    if lines.last().is_some_and(|l| l.trim().is_empty()) {
        lines.pop();
    }
    let indent = lines
        .iter()
        .filter(|l| !l.trim().is_empty())
        .map(|l| l.len() - l.trim_start().len())
        .min()
        .unwrap_or(0);
    lines
        .iter()
        .map(|l| {
            if l.len() >= indent {
                &l[indent..]
            } else {
                l.trim()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn write(home: &Path, relative: &str, text: &str) -> PathBuf {
    let path = home.join(relative);
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(&path, trim_indent(text)).unwrap();
    path
}

fn by_name(candidates: &[ImportCandidate]) -> HashMap<String, ImportCandidate> {
    candidates
        .iter()
        .map(|c| (c.config.name.clone(), c.clone()))
        .collect()
}

#[test]
fn pgpass_entries_become_connections_honouring_escapes_and_skipping_wildcards() {
    let home = tempfile::tempdir().unwrap();
    let file = write(
        home.path(),
        ".pgpass",
        r"
        # comment
        db.example.com:5433:shop:app:s3cr\:et
        localhost:*:*:postgres:pw
        *:*:*:reporting:other
    ",
    );
    let scan = pgpass::read_pass_file(&file).unwrap();
    assert_eq!(scan.candidates.len(), 2);
    let shop = &scan.candidates[0];
    assert_eq!(shop.config.name, "app@db.example.com/shop");
    assert_eq!(shop.config.port, Some(5433));
    assert_eq!(shop.password.as_deref(), Some("s3cr:et"));
    assert_eq!(scan.candidates[1].config.port, None);
    assert_eq!(scan.skipped.len(), 1);
}

#[test]
fn pgpass_fills_in_passwords_for_postgresql_connections_imported_without_one() {
    let home = tempfile::tempdir().unwrap();
    let pass = write(home.path(), ".pgpass", "db:5432:app:alice:from-pgpass");
    let config = imports::profile_from(
        &connection_string::parse("postgresql://alice@db/app").unwrap(),
        "app",
    );
    let filled =
        imports::fill_passwords_from_pg_pass(vec![ImportCandidate::new(config, "test")], &pass);
    assert_eq!(filled[0].password.as_deref(), Some("from-pgpass"));
}

#[test]
fn pg_service_sections_become_named_connections() {
    let home = tempfile::tempdir().unwrap();
    let file = write(
        home.path(),
        ".pg_service.conf",
        "
        [billing]
        host=billing.internal
        port=6432
        dbname=billing
        user=svc
        sslmode=require
    ",
    );
    let config = pgpass::read_service_file(&file)
        .unwrap()
        .candidates
        .remove(0)
        .config;
    assert_eq!(config.name, "billing");
    assert_eq!(config.host, "billing.internal");
    assert_eq!(config.port, Some(6432));
    assert_eq!(
        config.properties.iter().collect::<Vec<_>>(),
        [(&"sslmode".to_owned(), &"require".to_owned())]
    );
}

#[test]
fn env_files_yield_urls_and_split_laravel_variables() {
    let home = tempfile::tempdir().unwrap();
    let project = home.path().join("shop");
    write(
        home.path(),
        "shop/.env",
        r#"
        APP_URL=https://shop.test
        DATABASE_URL="postgres://app:p%40ss@localhost:5432/shop"
        export REDIS_URL=rediss://default:redispw@cache:6380
        DB_CONNECTION=mysql
        DB_HOST=127.0.0.1
        DB_PORT=3306
        DB_DATABASE=${APP_NAME:-laravel}
        DB_USERNAME=root
        DB_PASSWORD=secret # local only
    "#,
    );
    write(
        home.path(),
        "shop/node_modules/pkg/.env",
        "DATABASE_URL=postgres://ignored/db",
    );
    let scan = dotenv::scan(&project, 4);
    let names = by_name(&scan.candidates);
    let mut keys: Vec<_> = names.keys().cloned().collect();
    keys.sort();
    assert_eq!(
        keys,
        ["shop · DATABASE_URL", "shop · DB_HOST", "shop · REDIS_URL"]
    );
    assert_eq!(
        names["shop · DATABASE_URL"].password.as_deref(),
        Some("p@ss")
    );
    let redis = &names["shop · REDIS_URL"].config;
    assert_eq!(redis.db_type.id, DatabaseType::REDIS.id);
    assert_eq!(redis.port, Some(6380));
    assert_eq!(
        redis.properties.get("ssl").map(String::as_str),
        Some("true")
    );
    let laravel = &names["shop · DB_HOST"];
    assert_eq!(laravel.config.db_type.id, DatabaseType::MYSQL.id);
    assert_eq!(laravel.config.database, "laravel");
    assert_eq!(laravel.password.as_deref(), Some("secret"));
    assert_eq!(laravel.config.group, "shop");
}

#[test]
fn dbeaver_data_sources_with_encrypted_credentials_folders_ssh_and_connection_types() {
    let home = tempfile::tempdir().unwrap();
    let file = write(
        home.path(),
        "Library/DBeaverData/workspace6/General/.dbeaver/data-sources.json",
        r#"
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
    "#,
    );
    let credentials = r##"{"postgres-jdbc-1":{"#connection":{"user":"orders","password":"pw1"},"network/ssh_tunnel":{"user":"ops"}}}"##;
    std::fs::write(
        file.with_file_name("credentials-config.json"),
        dbeaver::encrypt_credentials(credentials, [0; 16]),
    )
    .unwrap();
    assert_eq!(
        dbeaver::locations(home.path(), &HashMap::new()),
        std::slice::from_ref(&file)
    );
    let scan = dbeaver::read(&file).unwrap();
    let names = by_name(&scan.candidates);
    let orders = &names["Orders prod"];
    assert_eq!(orders.config.db_type.id, DatabaseType::POSTGRESQL.id);
    assert_eq!(orders.config.host, "orders.db");
    assert_eq!(orders.config.port, Some(5433));
    assert_eq!(orders.config.user, "orders");
    assert_eq!(orders.password.as_deref(), Some("pw1"));
    assert_eq!(orders.config.group, "Clients");
    assert_eq!(orders.config.environment, Environment::Production);
    assert!(orders.config.read_only);
    assert!(orders.config.confirm_writes);
    let ssh = orders.config.ssh.as_ref().unwrap();
    assert_eq!(ssh.host, "bastion");
    assert_eq!(ssh.port, 2222);
    assert_eq!(ssh.user, "ops");
    assert_eq!(ssh.auth, SshAuth::PrivateKey);
    assert_eq!(ssh.private_key_path.as_deref(), Some("/keys/id"));
    assert_eq!(names["Local file"].config.database, "/tmp/app.db");
    assert_eq!(names["Maria"].config.db_type.id, DatabaseType::MARIADB.id);
    assert_eq!(names["Maria"].config.port, Some(3307));
    assert_eq!(names["Ora"].config.db_type.id, DatabaseType::OTHER_JDBC.id);
    assert_eq!(
        scan.skipped,
        ["DBeaver: “Cass” (cassandra) isn't supported."]
    );
}

#[test]
fn ide_data_sources_found_through_recent_projects_with_local_user_names_and_ssh() {
    let home = tempfile::tempdir().unwrap();
    let h = home.path();
    write(
        h,
        "Library/Application Support/JetBrains/IDE2024.1/options/recentProjects.xml",
        r#"
        <application><component name="RecentProjectsManager"><option name="additionalInfo"><map>
          <entry key="$USER_HOME$/work/api"><value/></entry>
        </map></option></component></application>
    "#,
    );
    write(
        h,
        "Library/Application Support/JetBrains/IDE2024.1/options/sshConfigs.xml",
        r#"
        <application><component name="SshConfigs"><configs>
          <sshConfig authType="PASSWORD" host="jump.example.com" id="ssh-1" port="22" username="deploy" />
        </configs></component></application>
    "#,
    );
    let shared = write(
        h,
        "work/api/.idea/dataSources.xml",
        r#"
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4"><component name="DataSourceManagerImpl" format="xml" multifile-model="true">
          <data-source source="LOCAL" name="api@localhost" uuid="u1">
            <driver-ref>mysql.8</driver-ref>
            <jdbc-url>jdbc:mysql://localhost:3306/api</jdbc-url>
          </data-source>
          <data-source source="LOCAL" name="local file" uuid="u2">
            <driver-ref>sqlite.xerial</driver-ref>
            <jdbc-url>jdbc:sqlite:$PROJECT_DIR$/data/app.db</jdbc-url>
          </data-source>
          <data-source source="LOCAL" name="cache" uuid="u3">
            <driver-ref>redis</driver-ref>
            <jdbc-url>jdbc:redis://localhost:6379/0</jdbc-url>
          </data-source>
        </component></project>
    "#,
    );
    write(
        h,
        "work/api/.idea/dataSources.local.xml",
        r#"
        <project version="4"><component name="dataSourceStorageLocal">
          <data-source name="api@localhost" uuid="u1">
            <database-info product="MySQL" dbms="MARIADB" />
            <user-name>api_user</user-name>
            <ssh-properties><enabled>true</enabled><ssh-config-id>ssh-1</ssh-config-id></ssh-properties>
          </data-source>
        </component></project>
    "#,
    );
    assert_eq!(ide::locations(h), std::slice::from_ref(&shared));
    let scan = ide::read(&shared, h).unwrap();
    let names = by_name(&scan.candidates);
    let api = &names["api@localhost"].config;
    assert_eq!(api.db_type.id, DatabaseType::MARIADB.id);
    assert_eq!(api.user, "api_user");
    assert_eq!(api.group, "api");
    assert_eq!(api.ssh.as_ref().map(|s| s.auth), Some(SshAuth::Password));
    assert_eq!(api.ssh.as_ref().map(|s| s.user.as_str()), Some("deploy"));
    assert_eq!(
        names["local file"].config.database,
        h.join("work/api/data/app.db").display().to_string()
    );
    assert_eq!(names["cache"].config.db_type.id, DatabaseType::REDIS.id);
}

#[test]
fn connections_plist_with_groups_environments_files_and_ssh() {
    let home = tempfile::tempdir().unwrap();
    let file = write(
        home.path(),
        "clients/Data/Connections.plist",
        r#"
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
    "#,
    );
    write(
        home.path(),
        "clients/Data/ConnectionGroups.plist",
        r#"
        <plist version="1.0"><array><dict><key>ID</key><string>G1</string><key>Name</key><string>Fintech</string></dict></array></plist>
    "#,
    );
    let scan = plist::read(&file).unwrap();
    let names = by_name(&scan.candidates);
    let payments = &names["Payments"].config;
    assert_eq!(payments.group, "Fintech");
    assert_eq!(payments.environment, Environment::Staging);
    assert_eq!(
        payments.properties.get("sslmode").map(String::as_str),
        Some("require")
    );
    assert_eq!(payments.properties.len(), 1);
    assert_eq!(
        payments.ssh.as_ref().map(|s| s.host.as_str()),
        Some("bastion.pay")
    );
    assert_eq!(
        payments.ssh.as_ref().map(|s| s.user.as_str()),
        Some("ec2-user")
    );
    let notes = &names["Notes"].config;
    assert_eq!(notes.db_type.id, DatabaseType::SQLITE.id);
    assert_eq!(notes.database, "/Users/me/notes.db");
    assert_eq!(scan.skipped.len(), 1);
}

#[test]
fn discover_lists_only_sources_present_and_detect_recognises_picked_files() {
    let home = tempfile::tempdir().unwrap();
    let h = home.path();
    write(h, ".pgpass", "h:5432:d:u:p");
    let dbeaver = write(
        h,
        "Library/DBeaverData/workspace6/General/.dbeaver/data-sources.json",
        r#"{"connections": {}}"#,
    );
    let found: Vec<_> = imports::discover(h, &HashMap::new())
        .into_iter()
        .map(|s| s.format)
        .collect();
    assert_eq!(found, [ImportFormat::DBeaver, ImportFormat::PgPass]);
    assert_eq!(
        imports::detect(&dbeaver).map(|s| s.format),
        Some(ImportFormat::DBeaver)
    );
    let renamed = write(
        h,
        "exports/connections.txt",
        r#"{"connections": {"a": {"provider": "postgresql"}}}"#,
    );
    assert_eq!(
        imports::detect(&renamed).map(|s| s.format),
        Some(ImportFormat::DBeaver)
    );
    std::fs::create_dir_all(h.join("proj")).unwrap();
    assert_eq!(
        imports::detect(&h.join("proj")).map(|s| s.format),
        Some(ImportFormat::DotEnv)
    );
}

#[test]
fn keys_treat_the_same_database_written_differently_as_one() {
    let config = |url: &str| imports::profile_from(&connection_string::parse(url).unwrap(), "x");
    let a = config("postgresql://u@localhost/app");
    let b = config("postgresql://u@127.0.0.1:5432/app");
    let c = config("postgresql://u@localhost/other");
    assert_eq!(imports::key_of(&a), imports::key_of(&b));
    assert_ne!(imports::key_of(&a), imports::key_of(&c));
}

#[test]
fn ssh_config_hosts_with_includes_and_wildcard_defaults() {
    let home = tempfile::tempdir().unwrap();
    let h = home.path();
    write(
        h,
        ".ssh/config",
        "
        Include conf.d/*
        Host bastion prod-jump
          HostName 10.0.0.5
          Port 2222
        Host *
          User ops
          IdentityFile ~/.ssh/id_ed25519
    ",
    );
    write(
        h,
        ".ssh/conf.d/work",
        "
        Host db-tunnel
          HostName tunnel.example.com
          User me
    ",
    );
    let hosts: HashMap<_, _> = imports::ssh_hosts(h)
        .into_iter()
        .map(|x| (x.alias.clone(), x))
        .collect();
    let mut aliases: Vec<_> = hosts.keys().cloned().collect();
    aliases.sort();
    assert_eq!(aliases, ["bastion", "db-tunnel", "prod-jump"]);
    assert_eq!(hosts["db-tunnel"].user.as_deref(), Some("me"));
    let bastion = &hosts["bastion"];
    assert_eq!(bastion.host_name, "10.0.0.5");
    assert_eq!(bastion.port, Some(2222));
    assert_eq!(bastion.user.as_deref(), Some("ops"));
    assert_eq!(
        bastion.identity_file.as_deref(),
        Some(h.join(".ssh/id_ed25519").display().to_string().as_str())
    );
}
