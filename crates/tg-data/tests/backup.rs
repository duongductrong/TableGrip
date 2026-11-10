//! `core/src/test/.../backup/BackupTest.kt`. The MySQL round trip runs when `TG_IT_MYSQL=host:port` is set (user
//! `root`, password `pw`) and `mysqldump` and `mysql` are installed; the PostgreSQL one needs `TG_IT_POSTGRES` and
//! `pg_dump`, `pg_restore` and `psql`.

use indexmap::IndexMap;
use std::path::{Path, PathBuf};
use tg_core::connection::ConnectionConfig;
use tg_data::backup::*;
use tg_data::tools::ToolFamily;
use tg_sql::model::DatabaseType;

fn server() -> ServerEndpoint {
    ServerEndpoint {
        host: "db.internal".into(),
        port: 5432,
        user: "app".into(),
        password: Some("s3cret 'quoted'".into()),
        properties: IndexMap::from([("sslmode".to_string(), "require".to_string())]),
    }
}

#[test]
fn support() {
    assert_eq!(
        BackupSupport::WithTools(ToolFamily::PostgreSQL),
        BackupSupport::of(&DatabaseType::SUPABASE)
    );
    assert_eq!(
        BackupSupport::WithTools(ToolFamily::MySQL),
        BackupSupport::of(&DatabaseType::MARIADB)
    );
    assert_eq!(
        BackupSupport::Native(BackupFormat::SqliteCopy),
        BackupSupport::of(&DatabaseType::SQLITE)
    );
    assert!(matches!(
        BackupSupport::restore_of(&DatabaseType::SQLITE),
        BackupSupport::NotYet(_)
    ));
    assert!(matches!(
        BackupSupport::of(&DatabaseType::COCKROACHDB),
        BackupSupport::NotYet(_)
    ));
    assert!(matches!(
        BackupSupport::of(&DatabaseType::SQLSERVER),
        BackupSupport::NotYet(_)
    ));
    assert_eq!(
        BackupSupport::NotApplicable,
        BackupSupport::of(&DatabaseType::REDIS)
    );
    assert_eq!(
        BackupSupport::NotApplicable,
        BackupSupport::of(&DatabaseType::CSV_FILE)
    );
}

#[test]
fn secrets_never_reach_the_command_line() {
    let mut options = BackupOptions::new(BackupFormat::PgCustom, "/tmp/a.dump", "shop");
    options.no_owner = true;
    options.schemas = vec!["public".into()];
    let dump = commands::pg_dump(Path::new("/bin/pg_dump"), &server(), &options);
    assert!(dump.arguments.iter().all(|a| !a.contains("s3cret")));
    assert_eq!("s3cret 'quoted'", dump.environment["PGPASSWORD"]);
    assert_eq!("require", dump.environment["PGSSLMODE"]);
    assert_eq!(
        "PGCONNECT_TIMEOUT=10 PGSSLMODE=require /bin/pg_dump --host=db.internal --port=5432 --username=app \
         --dbname=shop --no-password --format=custom --no-owner --schema=public --verbose --file=/tmp/a.dump",
        dump.display()
    );

    let secrets = PathBuf::from("/tmp/opts.cnf");
    let mysql = commands::mysql_dump(
        Path::new("/bin/mysqldump"),
        &ServerEndpoint {
            port: 3306,
            ..server()
        },
        &BackupOptions::new(BackupFormat::MySqlSql, "/tmp/a.sql", "shop"),
        &secrets,
    );
    assert!(mysql.arguments.iter().all(|a| !a.contains("s3cret")));
    assert_eq!(
        "--defaults-extra-file=/tmp/opts.cnf", mysql.arguments[1],
        "option files must come first"
    );
    assert_eq!(
        "[client]\nuser=\"app\"\npassword=\"s3cret 'quoted'\"\n",
        mysql.secret_files[&secrets]
    );
    assert!(!mysql.display().contains("s3cret"));
}

#[test]
fn display_quotes_what_a_shell_would_split() {
    let command = commands::mysql_restore(
        Path::new("/bin/mysql"),
        &server(),
        &RestoreOptions::new("/tmp/my dump.sql", BackupFormat::MySqlSql, "it's"),
        Path::new("/tmp/o.cnf"),
    );
    assert_eq!(
        "/bin/mysql --defaults-extra-file=/tmp/o.cnf --host=db.internal --port=5432 --protocol=TCP --verbose \
         'it'\\''s' < '/tmp/my dump.sql'",
        command.display()
    );
}

#[test]
fn parses_restore_listings() {
    let listing = ";
; Archive created at 2026-10-31 10:00:00 +07
;
5; 2615 2200 SCHEMA - public pg_database_owner
215; 1259 16386 TABLE public orders postgres
3410; 0 16386 TABLE DATA public orders postgres
3260; 2606 16392 CONSTRAINT public orders orders_pkey postgres
3262; 2606 16400 FK CONSTRAINT public orders orders_customer_fkey postgres";
    let entries = RestoreEntry::parse(listing);
    assert_eq!(
        vec![
            "SCHEMA public",
            "TABLE public.orders",
            "TABLE DATA public.orders",
            "CONSTRAINT public.orders orders_pkey",
            "FK CONSTRAINT public.orders orders_customer_fkey"
        ],
        entries.iter().map(RestoreEntry::label).collect::<Vec<_>>()
    );
    assert_eq!(
        "215; 1259 16386 TABLE public orders postgres",
        entries[1].line
    );
}

#[test]
fn formats_and_tools() {
    assert_eq!(
        &[
            BackupFormat::PgCustom,
            BackupFormat::PgPlain,
            BackupFormat::PgDirectory
        ],
        BackupFormat::for_family(ToolFamily::PostgreSQL)
    );
    let dir = tempfile::tempdir().unwrap();
    assert_eq!(BackupFormat::PgDirectory, BackupFormat::of_file(dir.path()));
    assert_eq!(
        BackupFormat::PgPlain,
        BackupFormat::of_file(Path::new("a.SQL"))
    );
    assert_eq!(
        BackupFormat::PgCustom,
        BackupFormat::of_file(Path::new("a.dump"))
    );
    use tg_data::tools::Tool;
    assert_eq!(
        vec![Tool::MariaDbDump, Tool::MySqlDump],
        tools_for(&DatabaseType::MARIADB, false, BackupFormat::MySqlSql)
    );
    assert_eq!(
        vec![Tool::MySql, Tool::MariaDb],
        tools_for(&DatabaseType::MYSQL, true, BackupFormat::MySqlSql)
    );
    assert_eq!(
        vec![Tool::Psql],
        tools_for(&DatabaseType::POSTGRESQL, true, BackupFormat::PgPlain)
    );
    assert_eq!(
        vec![Tool::PgRestore],
        tools_for(&DatabaseType::NEON, true, BackupFormat::PgCustom)
    );
}

#[test]
fn endpoint_from_a_profile() {
    let mut config = ConnectionConfig::new("c", "c", DatabaseType::POSTGRESQL);
    config.host = "[::1]".into();
    config.user = "app".into();
    let endpoint = ServerEndpoint::of(&config, Some("pw")).unwrap();
    assert_eq!(
        ("::1", 5432, Some("pw")),
        (
            endpoint.host.as_str(),
            endpoint.port,
            endpoint.password.as_deref()
        )
    );
    config.url = Some("postgresql://db.example:6432/shop".into());
    assert_eq!(
        ("db.example".to_string(), 6432),
        ServerEndpoint::of(&config, None)
            .map(|e| (e.host, e.port))
            .unwrap()
    );
    config.url = Some("postgresql://h1:5432,h2:5433/shop".into());
    assert!(ServerEndpoint::of(&config, None).is_err());
    assert!(
        ServerEndpoint::of(&ConnectionConfig::new("s", "s", DatabaseType::SQLITE), None).is_err()
    );
}

fn shell(script: &str) -> ToolCommand {
    ToolCommand {
        arguments: vec!["/bin/sh".into(), "-c".into(), script.into()],
        ..Default::default()
    }
}

#[tokio::test]
async fn runs_tools_with_owner_only_secrets_and_a_log() {
    let dir = tempfile::tempdir().unwrap();
    let secret = dir.path().join("opts.cnf");
    let mut command = shell(
        "stat -f %Lp opts.cnf 2>/dev/null || stat -c %a opts.cnf; cat opts.cnf; echo oops >&2; printf $X",
    );
    command.arguments[2] = format!("cd {} && {}", dir.path().display(), command.arguments[2]);
    command
        .secret_files
        .insert(secret.clone(), "password=x\n".into());
    command.environment.insert("X".into(), "done".into());
    let log = dir.path().join("logs/run.txt");
    let mut lines = Vec::new();
    let run = ToolRunner::run(&command, Some(&log), |l| lines.push(l.to_owned()))
        .await
        .unwrap();
    assert_eq!(0, run.exit_code);
    assert_eq!(vec!["600", "password=x", "oops", "done"], lines);
    assert_eq!("600\npassword=x\noops\ndone\n", run.log);
    assert!(!secret.exists(), "the secret file is deleted");
    let written = std::fs::read_to_string(&log).unwrap();
    assert!(written.starts_with("$ X=done /bin/sh -c "), "{written}");
    assert!(written.ends_with("\n\n600\npassword=x\noops\ndone\n"));
}

#[tokio::test]
async fn a_failed_run_ends_with_the_tools_last_words() {
    let script = "for i in 1 2 3 4 5 6 7 8; do echo line $i; done; exit 3";
    let err = ToolRunner::run_checked(&shell(script), "pg_dump", None, |_| {})
        .await
        .unwrap_err();
    match err {
        ToolError::Failed { message, log } => {
            assert_eq!(
                "pg_dump failed (exit code 3).\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8",
                message
            );
            assert!(log.starts_with("line 1\n"));
        }
        other => panic!("{other}"),
    }
}

#[tokio::test]
async fn input_is_fed_to_the_tool_and_a_dropped_run_still_cleans_up() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("in.sql");
    std::fs::write(&input, "select 1;\n").unwrap();
    let mut command = shell("cat");
    command.input = Some(input);
    assert_eq!(
        "select 1;\n",
        ToolRunner::run(&command, None, |_| {}).await.unwrap().log
    );

    let secret = dir.path().join("s.cnf");
    let mut slow = shell("echo started; sleep 30");
    slow.secret_files.insert(secret.clone(), "x".into());
    let run = ToolRunner::run(&slow, None, |_| {});
    assert!(
        tokio::time::timeout(std::time::Duration::from_millis(500), run)
            .await
            .is_err()
    );
    assert!(!secret.exists());
}

#[tokio::test(flavor = "multi_thread")]
async fn mysql_round_trip() {
    let Some((host, port)) = std::env::var("TG_IT_MYSQL").ok().and_then(|v| {
        let (h, p) = v.split_once(':')?;
        Some((h.to_owned(), p.parse::<i32>().ok()?))
    }) else {
        return;
    };
    let (Some(dump), Some(client)) = (which("mysqldump"), which("mysql")) else {
        return;
    };
    let mut config = ConnectionConfig::new("my", "my", DatabaseType::MYSQL);
    config.host = host;
    config.port = Some(port);
    config.user = "root".into();
    let endpoint = ServerEndpoint::of(&config, Some("pw")).unwrap();
    let dir = tempfile::tempdir().unwrap();
    let sql = |statements: &str| {
        let file = dir.path().join("setup.sql");
        std::fs::write(&file, statements).unwrap();
        commands::mysql_restore(
            &client,
            &endpoint,
            &RestoreOptions::new(&file, BackupFormat::MySqlSql, "mysql"),
            &dir.path().join("setup.cnf"),
        )
    };
    ToolRunner::run_checked(
        &sql(
            "DROP DATABASE IF EXISTS backup_it_src; DROP DATABASE IF EXISTS backup_it_dst;
              CREATE DATABASE backup_it_src; CREATE DATABASE backup_it_dst;
              CREATE TABLE backup_it_src.t (id int PRIMARY KEY, name text);
              INSERT INTO backup_it_src.t VALUES (1, 'a'), (2, 'b');",
        ),
        "mysql",
        None,
        |_| {},
    )
    .await
    .unwrap();

    let file = dir.path().join("src.sql");
    let dump_cnf = dir.path().join("dump.cnf");
    ToolRunner::run_checked(
        &commands::mysql_dump(
            &dump,
            &endpoint,
            &BackupOptions::new(BackupFormat::MySqlSql, &file, "backup_it_src"),
            &dump_cnf,
        ),
        "mysqldump",
        None,
        |_| {},
    )
    .await
    .unwrap();
    assert!(
        !dump_cnf.exists(),
        "the option file with the password is deleted"
    );
    assert!(
        std::fs::read_to_string(&file)
            .unwrap()
            .contains("INSERT INTO `t`")
    );
    ToolRunner::run_checked(
        &commands::mysql_restore(
            &client,
            &endpoint,
            &RestoreOptions::new(&file, BackupFormat::MySqlSql, "backup_it_dst"),
            &dir.path().join("restore.cnf"),
        ),
        "mysql",
        None,
        |_| {},
    )
    .await
    .unwrap();
    let count = ToolRunner::run_checked(
        &sql("SELECT COUNT(*) AS n FROM backup_it_dst.t;"),
        "mysql",
        None,
        |_| {},
    )
    .await
    .unwrap();
    assert!(count.log.lines().any(|l| l.trim() == "2"), "{}", count.log);

    let bad = ToolRunner::run(
        &commands::mysql_dump(
            &dump,
            &ServerEndpoint {
                password: Some("wrong".into()),
                ..endpoint.clone()
            },
            &BackupOptions::new(
                BackupFormat::MySqlSql,
                dir.path().join("bad.sql"),
                "backup_it_src",
            ),
            &dir.path().join("bad.cnf"),
        ),
        None,
        |_| {},
    )
    .await
    .unwrap();
    assert!(
        bad.exit_code != 0 && bad.log.contains("Access denied"),
        "{}",
        bad.log
    );
    ToolRunner::run_checked(
        &sql("DROP DATABASE backup_it_src; DROP DATABASE backup_it_dst;"),
        "mysql",
        None,
        |_| {},
    )
    .await
    .unwrap();
}

fn which(name: &str) -> Option<PathBuf> {
    std::env::var_os("PATH")?
        .to_str()?
        .split(':')
        .map(|d| Path::new(d).join(name))
        .find(|p| p.is_file())
}
