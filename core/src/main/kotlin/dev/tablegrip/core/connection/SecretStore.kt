package dev.tablegrip.core.connection

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException

/** Stores connection passwords outside of the profile file. */
interface SecretStore {
    fun get(connectionId: String): String?

    fun set(connectionId: String, secret: String)

    fun delete(connectionId: String)
}

/**
 * Backed by the OS credential store: macOS Keychain, Windows Credential Manager, or the freedesktop Secret Service
 * (GNOME Keyring / KWallet) on Linux.
 */
class KeyringSecretStore(private val service: String = "TableGrip") : SecretStore {
    private val keyring: Keyring by lazy { Keyring.create() }

    override fun get(connectionId: String): String? =
        try {
            keyring.getPassword(service, connectionId)
        } catch (_: PasswordAccessException) {
            null
        }

    override fun set(connectionId: String, secret: String) = keyring.setPassword(service, connectionId, secret)

    override fun delete(connectionId: String) {
        try {
            keyring.deletePassword(service, connectionId)
        } catch (_: PasswordAccessException) {
            // Nothing stored for this connection.
        }
    }
}

class InMemorySecretStore : SecretStore {
    private val secrets = mutableMapOf<String, String>()

    override fun get(connectionId: String): String? = secrets[connectionId]

    override fun set(connectionId: String, secret: String) {
        secrets[connectionId] = secret
    }

    override fun delete(connectionId: String) {
        secrets.remove(connectionId)
    }
}
