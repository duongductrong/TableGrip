//! `ToolLibraryTest`, ported.

use sha2::Digest;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tg_data::tools::{
    Tool, ToolFamily, ToolLibrary, ToolPackage, ToolSource, package_for, parse_version, platform_id,
};

#[test]
fn parses_versions() {
    assert_eq!(
        parse_version("pg_dump (PostgreSQL) 18.6").as_deref(),
        Some("18.6")
    );
    assert_eq!(
        parse_version("pg_restore (PostgreSQL) 16.4 (Homebrew)").as_deref(),
        Some("16.4")
    );
    assert_eq!(
        parse_version("mysqldump  Ver 8.4.3 for macos14.4 on arm64 (Homebrew)").as_deref(),
        Some("8.4.3")
    );
    assert_eq!(
        parse_version(
            "mysqldump  Ver 10.19 Distrib 10.11.6-MariaDB, for debian-linux-gnu (x86_64)"
        )
        .as_deref(),
        Some("10.19")
    );
    assert_eq!(
        parse_version("mariadb-dump from 11.4.2-MariaDB, client 10.19 for osx10.19 (arm64)")
            .as_deref(),
        Some("11.4.2")
    );
}

/// A fake `pg_dump` printing `version`, in `dir`.
#[cfg(unix)]
fn fake_tool(dir: &Path, version: &str) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    std::fs::create_dir_all(dir).unwrap();
    let file = dir.join(Tool::PgDump.executable());
    std::fs::write(
        &file,
        format!("#!/bin/sh\necho 'pg_dump (PostgreSQL) {version}'\n"),
    )
    .unwrap();
    std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o755)).unwrap();
    file
}

#[cfg(unix)]
#[test]
fn picks_the_newest_that_can_read_the_server_unless_the_user_chose() {
    let home = tempfile::tempdir().unwrap();
    let h = home.path().to_owned();
    let old = fake_tool(&h.join("old"), "14.2");
    let new = fake_tool(&h.join("new"), "17.1");
    let custom: Arc<Mutex<std::collections::HashMap<String, String>>> = Arc::default();
    let c = custom.clone();
    let dirs = h.clone();
    let library = ToolLibrary::new(
        h.join("tools"),
        Box::new(move || c.lock().unwrap().clone()),
        Box::new(move || vec![dirs.join("old"), dirs.join("new")]),
    );
    let versions: Vec<_> = library
        .find(Tool::PgDump)
        .into_iter()
        .map(|t| t.version)
        .collect();
    assert_eq!(versions, [Some("14.2".into()), Some("17.1".into())]);
    assert_eq!(
        library.best(Tool::PgDump, Some(16)).unwrap().path,
        new.canonicalize().unwrap()
    );
    assert!(
        library.best(Tool::PgDump, Some(18)).is_none(),
        "no copy can dump a newer server"
    );
    custom
        .lock()
        .unwrap()
        .insert(Tool::PgDump.name().into(), old.display().to_string());
    assert_eq!(
        library.best(Tool::PgDump, Some(18)).unwrap().source,
        ToolSource::Custom
    );
}

/// Serves `bytes` once per connection on a local port.
fn serve(bytes: Vec<u8>) -> u16 {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let mut stream = stream;
            let mut request = [0u8; 4096];
            let _ = stream.read(&mut request);
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                bytes.len()
            );
            let _ = stream.write_all(head.as_bytes());
            let _ = stream.write_all(&bytes);
        }
    });
    port
}

#[tokio::test]
async fn installs_only_the_listed_files_and_checks_the_hash() {
    let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    for (name, text) in [
        (
            format!("pgsql/bin/{}", Tool::PgDump.executable()),
            "#!/bin/sh\necho 'pg_dump (PostgreSQL) 18.6'\n",
        ),
        ("pgsql/lib/libpq.5.dylib".to_owned(), "lib"),
        ("pgsql/bin/postgres".to_owned(), "server"),
    ] {
        zip.start_file(name, zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(text.as_bytes()).unwrap();
    }
    let bytes = zip.finish().unwrap().into_inner();
    let port = serve(bytes.clone());
    let sha: String = sha2::Sha256::digest(&bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    let pkg = ToolPackage {
        id: "pg-test".into(),
        family: ToolFamily::PostgreSQL,
        version: "18.6".into(),
        os: platform_id().into(),
        url: format!("http://127.0.0.1:{port}/pkg.zip"),
        sha256: sha,
        download_bytes: bytes.len() as u64,
        publisher: "test".into(),
        license: "test".into(),
        files: vec![
            format!("bin/{}", Tool::PgDump.executable()),
            "lib/libpq.5.dylib".into(),
        ],
        strip_prefix: "pgsql/".into(),
    };
    let root = tempfile::tempdir().unwrap();
    let library = ToolLibrary::new(
        root.path().to_owned(),
        Box::new(Default::default),
        Box::new(Vec::new),
    );
    let bad = ToolPackage {
        sha256: "0".repeat(64),
        ..pkg.clone()
    };
    assert!(library.install(&bad, &|_, _| {}).await.is_err());
    assert!(
        !root.path().join("pg-test").exists(),
        "a bad download installs nothing"
    );

    let progress = Arc::new(Mutex::new(0u64));
    let p = progress.clone();
    library
        .install(&pkg, &move |done, _| *p.lock().unwrap() = done)
        .await
        .unwrap();
    assert_eq!(*progress.lock().unwrap(), bytes.len() as u64);
    assert_eq!(
        std::fs::read_to_string(root.path().join("pg-test/lib/libpq.5.dylib")).unwrap(),
        "lib"
    );
    assert!(
        !root.path().join("pg-test/bin/postgres").exists(),
        "files not listed are left out"
    );
    assert!(
        std::fs::read_dir(root.path())
            .unwrap()
            .flatten()
            .all(|e| !e.file_name().to_string_lossy().ends_with(".part"))
    );
    library.uninstall(&pkg).unwrap();
    assert!(!root.path().join("pg-test").exists());
}

#[test]
fn catalog_covers_the_desktop_platforms() {
    let mac = package_for(ToolFamily::PostgreSQL, "macos")
        .unwrap()
        .tools();
    #[cfg(not(windows))]
    assert!(
        [Tool::PgDump, Tool::PgRestore, Tool::Psql]
            .iter()
            .all(|t| mac.contains(t))
    );
    let _ = mac;
    assert!(package_for(ToolFamily::PostgreSQL, "windows-x64").is_some());
    assert!(
        package_for(ToolFamily::MySQL, "macos").is_none(),
        "MySQL's clients are GPL: never downloaded"
    );
}
