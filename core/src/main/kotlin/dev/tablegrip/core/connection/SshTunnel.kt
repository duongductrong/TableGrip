package dev.tablegrip.core.connection

import dev.tablegrip.core.TableGripPaths
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.PublicKey
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.slf4j.LoggerFactory

/**
 * Forwards a local port on 127.0.0.1 to [targetHost]:[targetPort] through an SSH server. Host keys are trusted on
 * first use and pinned in TableGrip's own known_hosts; a changed key fails the connection.
 */
class SshTunnel private constructor(
    private val client: SSHClient,
    private val server: ServerSocket,
    private val forwarder: LocalPortForwarder,
    val localPort: Int,
) : AutoCloseable {

    override fun close() {
        runCatching { forwarder.close() }
        runCatching { server.close() }
        runCatching { client.disconnect() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SshTunnel::class.java)

        fun open(
            ssh: SshConfig,
            secret: String?,
            targetHost: String,
            targetPort: Int,
            knownHosts: File = TableGripPaths.home.resolve("known_hosts").toFile(),
        ): SshTunnel {
            val client = SSHClient(DefaultConfig())
            try {
                knownHosts.parentFile?.mkdirs()
                if (!knownHosts.exists()) knownHosts.createNewFile()
                client.addHostKeyVerifier(TrustOnFirstUse(knownHosts))
                client.connectTimeout = 10_000
                client.connect(ssh.host, ssh.port)
                when (ssh.auth) {
                    SshAuth.Password -> client.authPassword(ssh.user, secret.orEmpty())
                    SshAuth.PrivateKey ->
                        if (ssh.privateKeyPath.isNullOrBlank()) client.authPublickey(ssh.user)
                        else {
                            val keys =
                                if (secret.isNullOrEmpty()) client.loadKeys(expandHome(ssh.privateKeyPath))
                                else client.loadKeys(expandHome(ssh.privateKeyPath), secret)
                            client.authPublickey(ssh.user, keys)
                        }
                }
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", 0))
                }
                val forwarder = client.newLocalPortForwarder(Parameters("127.0.0.1", server.localPort, targetHost, targetPort), server)
                Thread({ runCatching { forwarder.listen() }.onFailure { if (!server.isClosed) log.warn("SSH tunnel stopped", it) } }, "ssh-tunnel-${ssh.host}")
                    .apply { isDaemon = true }
                    .start()
                log.info("SSH tunnel 127.0.0.1:{} -> {}:{} via {}@{}", server.localPort, targetHost, targetPort, ssh.user, ssh.host)
                return SshTunnel(client, server, forwarder, server.localPort)
            } catch (e: Exception) {
                runCatching { client.disconnect() }
                throw e
            }
        }

        private fun expandHome(path: String) = if (path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path
    }

    private class TrustOnFirstUse(file: File) : OpenSSHKnownHosts(file) {
        override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
            entries().add(HostEntry(null, hostname, net.schmizz.sshj.common.KeyType.fromKey(key), key))
            write()
            return true
        }
    }
}
