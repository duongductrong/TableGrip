//! The connection form and the profile list (`ConnectionDialogTest`, `ConnectionsViewModelTest`).

use tg_app_logic::connections::{ConnectionForm, Connections, FormTab, connectable_types};
use tg_core::connection::{ConnectionConfig, Environment, SshAuth};
use tg_sql::model::DatabaseType;

fn new_form() -> ConnectionForm {
    ConnectionForm::new(None, "", "id-1", "me")
}

#[test]
fn a_new_form_is_postgres_on_localhost_and_validates_like_kotlin() {
    let mut f = new_form();
    assert_eq!(f.db_type, DatabaseType::POSTGRESQL);
    assert_eq!(f.host, "localhost");
    assert_eq!(f.tabs(), [FormTab::General, FormTab::Ssh, FormTab::Options]);
    assert_eq!(f.validate().as_deref(), Some("Database is required."));
    f.database = "app".into();
    assert_eq!(f.validate(), None);
    f.port = "70000".into();
    assert_eq!(
        f.validate().as_deref(),
        Some("Port must be between 1 and 65535.")
    );
    f.port = "5433".into();
    f.properties = "sslmode=require\nbad".into();
    assert_eq!(
        f.validate().as_deref(),
        Some("Driver property \"bad\" must look like key=value.")
    );
    f.properties = "# comment\nsslmode = require\n".into();
    f.use_ssh = true;
    assert_eq!(f.validate().as_deref(), Some("SSH host is required."));
    f.ssh_host = "bastion".into();
    f.ssh_key_path = "~/.ssh/id_ed25519".into();
    assert_eq!(f.validate(), None);

    let c = f.to_config();
    assert_eq!(
        c.name, "localhost/app",
        "a blank name falls back to host/database"
    );
    assert_eq!(c.port, Some(5433));
    assert_eq!(
        c.properties.get("sslmode").map(String::as_str),
        Some("require")
    );
    let ssh = c.ssh.unwrap();
    assert_eq!(
        (ssh.host.as_str(), ssh.port, ssh.user.as_str()),
        ("bastion", 22, "me")
    );
    assert_eq!(ssh.private_key_path.as_deref(), Some("~/.ssh/id_ed25519"));

    f.ssh_auth = SshAuth::Password;
    assert_eq!(
        f.to_config().ssh.unwrap().private_key_path,
        None,
        "a password tunnel has no key"
    );
}

#[test]
fn file_engines_have_no_ssh_and_name_themselves_after_the_file() {
    let mut f = new_form();
    f.change_type(&DatabaseType::SQLITE);
    f.use_ssh = true;
    assert_eq!(f.tabs(), [FormTab::General, FormTab::Options]);
    assert_eq!(f.validate().as_deref(), Some("Database file is required."));
    f.database = "/tmp/does-not-matter/app.db".into();
    assert_eq!(f.validate(), None);
    let c = f.to_config();
    assert_eq!(c.name, "app.db");
    assert!(c.host.is_empty() && c.port.is_none() && c.ssh.is_none());
    assert_eq!(
        f.ssh_secret_to_store(),
        "",
        "turning SSH off removes the stored secret"
    );
}

#[test]
fn a_pasted_url_fills_the_form_and_its_password_goes_to_the_keychain() {
    let mut f = new_form();
    f.connection_string = "postgres://alice:s3cret@db.example.com:6543/shop?sslmode=require".into();
    f.parse_connection_string().unwrap();
    assert_eq!(f.db_type, DatabaseType::POSTGRESQL);
    assert_eq!(
        (f.host.as_str(), f.port.as_str(), f.database.as_str()),
        ("db.example.com", "6543", "shop")
    );
    assert_eq!((f.user.as_str(), f.password.as_str()), ("alice", "s3cret"));
    assert!(f.properties.contains("sslmode=require"));

    f.connection_string = "mysql://root@localhost/app".into();
    f.parse_connection_string().unwrap();
    assert_eq!(f.db_type, DatabaseType::MYSQL);

    let mut f = new_form();
    f.use_url = true;
    f.url = "jdbc:postgresql://h/db?user=u&password=pw".into();
    f.move_url_password();
    assert_eq!(f.password, "pw");
    assert!(!f.url.contains("pw"));
    assert_eq!(f.validate(), None);
    f.url = "postgres://h/db".into();
    assert!(
        f.validate()
            .unwrap()
            .starts_with("A JDBC URL starts with jdbc:")
    );
}

#[test]
fn editing_keeps_what_the_form_doesnt_show() {
    let mut existing = ConnectionConfig::new("x", "prod", DatabaseType::MYSQL);
    existing.host = "db".into();
    existing.database = "app".into();
    existing.favorite = true;
    existing.environment = Environment::Production;
    existing.group = "Work".into();
    let f = ConnectionForm::new(Some(&existing), "", "unused", "me");
    let c = f.to_config();
    assert_eq!(c.id, "x");
    assert!(c.favorite);
    assert_eq!(c.environment, Environment::Production);
    assert_eq!(c.group, "Work");
}

#[test]
fn groups_and_favourites() {
    let mut list = Connections::new(vec![
        ConnectionConfig {
            group: "Work".into(),
            ..ConnectionConfig::new("a", "a", DatabaseType::SQLITE)
        },
        ConnectionConfig {
            favorite: true,
            ..ConnectionConfig::new("b", "b", DatabaseType::SQLITE)
        },
        ConnectionConfig {
            group: "home".into(),
            ..ConnectionConfig::new("c", "c", DatabaseType::SQLITE)
        },
    ]);
    assert_eq!(list.groups(), ["home", "Work"]);
    // Moving a favourite takes it out of Favorites.
    let changed = list.move_to_group(&["b".into()], " Work ");
    assert_eq!(changed.len(), 1);
    assert!(!list.by_id("b").unwrap().favorite);
    assert_eq!(list.by_id("b").unwrap().group, "Work");
    // Already there: nothing to save.
    assert!(list.move_to_group(&["a".into()], "Work").is_empty());
    assert_eq!(list.rename_group("Work", "Office").len(), 2);
    assert_eq!(list.ungroup("Office").len(), 2);
    assert_eq!(list.groups(), ["home"]);
    assert!(list.toggle_favorite("c")[0].favorite);
}

#[test]
fn engines_without_a_rust_driver_are_not_offered() {
    let names: Vec<&str> = connectable_types().iter().map(|t| t.id).collect();
    assert!(names.contains(&"PostgreSQL") && names.contains(&"CsvFile") && names.contains(&"Etcd"));
    for gone in ["H2", "HSQLDB", "Derby", "OtherJdbc"] {
        assert!(!names.contains(&gone), "{gone}");
    }
}

mod imports {
    use std::path::PathBuf;

    use tg_app_logic::imports::ImportState;
    use tg_core::connection::ConnectionConfig;
    use tg_data::imports::{ImportCandidate, ImportFormat, ImportScan, ImportSource};
    use tg_sql::model::DatabaseType;

    fn candidate(id: &str, host: &str, password: Option<&str>) -> ImportCandidate {
        let mut c = ConnectionConfig::new(id, id, DatabaseType::POSTGRESQL);
        c.host = host.into();
        c.database = "app".into();
        c.group = "Tool folder".into();
        let mut cand = ImportCandidate::new(c, "test");
        cand.password = password.map(str::to_owned);
        cand
    }

    fn source(name: &str) -> ImportSource {
        ImportSource {
            format: ImportFormat::DotEnv,
            path: PathBuf::from(name),
        }
    }

    #[test]
    fn saved_and_duplicate_connections_start_unticked_and_share_passwords() {
        let mut saved = ConnectionConfig::new("s", "mine", DatabaseType::POSTGRESQL);
        saved.host = "saved-host".into();
        saved.database = "app".into();
        let mut state = ImportState::new();
        state.append(
            vec![
                (
                    source("a"),
                    ImportScan {
                        candidates: vec![
                            candidate("1", "h1", None),
                            candidate("2", "saved-host", None),
                        ],
                        skipped: vec![],
                    },
                ),
                (
                    source("b"),
                    ImportScan {
                        candidates: vec![candidate("3", "h1", Some("pw"))],
                        skipped: vec![],
                    },
                ),
            ],
            &[saved],
        );
        assert!(!state.scanning);
        let rows: Vec<_> = state.rows().collect();
        assert!(rows[0].selected && !rows[1].selected && !rows[2].selected);
        assert_eq!(rows[1].already_saved.as_deref(), Some("mine"));
        assert!(rows[2].duplicate);
        assert_eq!(
            rows[0].password.as_deref(),
            Some("pw"),
            "the duplicate's password goes to the row imported"
        );

        state.keep_groups = false;
        let chosen = state.chosen();
        assert_eq!(chosen.len(), 1);
        assert_eq!(chosen[0].0.group, "");
        assert_eq!(ImportState::summary(&chosen), "Imported 1");
        state.toggle_group(0);
        assert_eq!(
            state.selected().len(),
            2,
            "a group's tick selects all its rows"
        );
    }
}
