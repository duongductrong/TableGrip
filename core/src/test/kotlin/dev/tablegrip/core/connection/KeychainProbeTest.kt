package dev.tablegrip.core.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A manual check that `tg-core` reads what this app writes to the keychain. `-Pkeychain.probe=set` stores a dummy secret
 * under the real service name and a dummy account, `-Pkeychain.probe=delete` removes it again. In between, run
 * `cargo test -p tg-core --test keychain -- --ignored` (see crates/tg-core/tests/keychain.rs). Without the property
 * the test does nothing, so it never touches the keychain in a normal run.
 */
class KeychainProbeTest {
    private val store = KeyringSecretStore()

    @Test
    fun probe() {
        when (System.getProperty("keychain.probe")) {
            "set" -> {
                store.set(Account, Secret)
                assertEquals(Secret, store.get(Account))
            }
            "delete" -> {
                store.delete(Account)
                assertNull(store.get(Account))
            }
        }
    }

    private companion object {
        const val Account = "tablegrip-s2-probe"
        const val Secret = "s2-probe-secret ✓"
    }
}
