//! Reads an item the Kotlin app's keychain code wrote. Ignored by default because it touches the real keychain:
//!
//! ```text
//! ./gradlew :core:test --tests '*KeychainProbeTest*' -Pkeychain.probe=set
//! cargo test -p tg-core --test keychain -- --ignored
//! ./gradlew :core:test --tests '*KeychainProbeTest*' -Pkeychain.probe=delete
//! ```

use tg_core::connection::{KeyringSecretStore, SecretStore};

#[test]
#[ignore = "reads the real keychain: needs the item KeychainProbeTest writes"]
fn reads_the_item_java_keyring_wrote() {
    let store = KeyringSecretStore::default();
    assert_eq!(
        store.get("tablegrip-s2-probe").as_deref(),
        Some("s2-probe-secret ✓")
    );
}

#[test]
#[ignore = "writes the real keychain: leaves nothing behind, but may prompt for access"]
fn writes_an_item_java_keyring_can_read_and_removes_it() {
    let store = KeyringSecretStore::default();
    let account = "tablegrip-s2-rust-probe";
    store.set(account, "rust-probe ✓").unwrap();
    assert_eq!(store.get(account).as_deref(), Some("rust-probe ✓"));
    store.delete(account);
    assert_eq!(store.get(account), None);
}
