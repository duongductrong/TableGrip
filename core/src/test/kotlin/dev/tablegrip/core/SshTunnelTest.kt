package dev.tablegrip.core

import dev.tablegrip.core.connection.SshAuth
import dev.tablegrip.core.connection.SshConfig
import dev.tablegrip.core.connection.SshTunnel
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.jupiter.api.io.TempDir

class SshTunnelTest {
    @TempDir lateinit var dir: Path

    private val echo = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
    }
    private val servers = mutableListOf<SshServer>()

    @AfterTest
    fun stop() {
        servers.forEach { it.stop(true) }
        echo.close()
    }

    private fun sshServer(hostKey: Path): SshServer =
        SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey)
            setPasswordAuthenticator { user, password, _ -> user == "tunnel" && password == "secret" }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
            servers += this
        }

    private fun roundTrip(port: Int, text: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(text.toByteArray())
            socket.shutdownOutput()
            socket.getInputStream().readBytes().decodeToString()
        }

    @Test
    fun `forwards traffic through the ssh server and pins its host key`() {
        val server = sshServer(dir.resolve("host.ser"))
        val knownHosts = dir.resolve("known_hosts").toFile()
        val config = SshConfig("127.0.0.1", server.port, "tunnel", SshAuth.Password)

        SshTunnel.open(config, "secret", "127.0.0.1", echo.localPort, knownHosts).use { tunnel ->
            assertEquals("hello through ssh", roundTrip(tunnel.localPort, "hello through ssh"))
        }
        assertTrue(knownHosts.toPath().readText().contains("127.0.0.1"), "host key is remembered")
        // Reconnecting with the same host key works.
        SshTunnel.open(config, "secret", "127.0.0.1", echo.localPort, knownHosts).close()
    }

    @Test
    fun `rejects wrong passwords and changed host keys`() {
        val knownHosts = dir.resolve("known_hosts").toFile()
        val first = sshServer(dir.resolve("first.ser"))
        val config = SshConfig("127.0.0.1", first.port, "tunnel", SshAuth.Password)
        assertFails { SshTunnel.open(config, "wrong", "127.0.0.1", echo.localPort, knownHosts) }

        SshTunnel.open(config, "secret", "127.0.0.1", echo.localPort, knownHosts).close()
        val port = first.port
        first.stop(true)
        // Same host and port, different key: looks like a man in the middle.
        val impostor =
            SshServer.setUpDefaultServer().apply {
                this.port = port
                keyPairProvider = SimpleGeneratorHostKeyProvider(dir.resolve("second.ser"))
                setPasswordAuthenticator { _, _, _ -> true }
                start()
                servers += this
            }
        assertFails { SshTunnel.open(config.copy(port = impostor.port), "secret", "127.0.0.1", echo.localPort, knownHosts) }
    }
}
