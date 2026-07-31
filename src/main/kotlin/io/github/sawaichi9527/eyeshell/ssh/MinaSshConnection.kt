package io.github.sawaichi9527.eyeshell.ssh

import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.time.Duration
import java.util.Arrays
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.StreamingChannel
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.io.resource.PathResource
import org.apache.sshd.common.util.security.SecurityUtils

class MinaSshConnection private constructor(
    private val client: SshClient,
    private val clientSession: ClientSession,
    val endpoint: SshEndpoint,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val terminalOpened = AtomicBoolean()

    fun openTerminal(columns: Int = 80, rows: Int = 24): TerminalSession {
        require(columns > 0 && rows > 0)
        check(!closed.get()) { "SSH connection is closed" }
        check(terminalOpened.compareAndSet(false, true)) { "A terminal channel is already open" }

        val channel = clientSession.createShellChannel()
        try {
            channel.streaming = StreamingChannel.Streaming.Sync
            channel.ptyType = "xterm-256color"
            channel.ptyColumns = columns
            channel.ptyLines = rows
            channel.setRedirectErrorStream(true)
            channel.open().verify(CHANNEL_TIMEOUT)
            return MinaSshTerminalSession(endpoint.displayName, channel, this)
        } catch (failure: Exception) {
            channel.close(true)
            close()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clientSession.close(true)
        client.close(true)
    }

    companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val AUTH_TIMEOUT = Duration.ofSeconds(15)
        private val CHANNEL_TIMEOUT = Duration.ofSeconds(10)

        fun connect(
            endpoint: SshEndpoint,
            authentication: SshAuthentication,
            knownHostsStore: KnownHostsStore,
            unknownHostVerifier: HostKeyVerifier,
            changedHostKeyHandler: ChangedHostKeyHandler = ChangedHostKeyHandler {},
        ): MinaSshConnection {
            val publicKeys = if (authentication is SshAuthentication.PublicKey) {
                loadPublicKeyIdentities(authentication)
            } else {
                emptyList()
            }

            val client = SshClient.setUpDefaultClient().apply {
                hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
                keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
                userAuthFactories = when (authentication) {
                    is SshAuthentication.Password -> listOf(UserAuthPasswordFactory.INSTANCE)
                    is SshAuthentication.PublicKey -> listOf(UserAuthPublicKeyFactory.INSTANCE)
                }
                serverKeyVerifier = knownHostsStore.createServerKeyVerifier(
                    unknownHostVerifier,
                    changedHostKeyHandler,
                )
            }
            client.start()

            var session: ClientSession? = null
            try {
                val connectedSession = client.connect(endpoint.username, endpoint.host, endpoint.port)
                    .verify(CONNECT_TIMEOUT)
                    .session
                session = connectedSession
                var passwordIdentity: String? = null
                try {
                    passwordIdentity = if (authentication is SshAuthentication.Password) {
                        val password = authentication.copyValue()
                        try {
                            String(password).also(connectedSession::addPasswordIdentity)
                        } finally {
                            Arrays.fill(password, '\u0000')
                        }
                    } else {
                        null
                    }
                    publicKeys.forEach(connectedSession::addPublicKeyIdentity)
                    connectedSession.auth().verify(AUTH_TIMEOUT)
                } finally {
                    passwordIdentity?.let(connectedSession::removePasswordIdentity)
                    publicKeys.forEach(connectedSession::removePublicKeyIdentity)
                }
                return MinaSshConnection(client, connectedSession, endpoint)
            } catch (failure: Exception) {
                session?.close(true)
                client.close(true)
                throw failure
            }
        }

        private fun loadPublicKeyIdentities(authentication: SshAuthentication.PublicKey): List<KeyPair> {
            require(Files.isRegularFile(authentication.privateKeyFile)) {
                "Private key file does not exist: ${authentication.privateKeyFile}"
            }
            val permissionViolation = KeyUtils.validateStrictKeyFilePermissions(authentication.privateKeyFile)
            require(permissionViolation == null) {
                "Private key file permissions are not secure: ${permissionViolation.key}"
            }
            val passphrase = authentication.copyPassphrase()
            try {
                val passwordProvider = if (passphrase.isEmpty()) {
                    FilePasswordProvider.EMPTY
                } else {
                    FilePasswordProvider { _, _, _ -> String(passphrase) }
                }
                val resource = PathResource(authentication.privateKeyFile)
                val identities = resource.openInputStream().use { input ->
                    SecurityUtils.loadKeyPairIdentities(null, resource, input, passwordProvider).toList()
                }
                require(identities.isNotEmpty()) { "Private key file contains no identities" }
                return identities
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }
    }
}

private class MinaSshTerminalSession(
    override val name: String,
    private val channel: ChannelShell,
    private val connection: MinaSshConnection,
) : TerminalSession {
    private val closed = AtomicBoolean()
    private val reader = InputStreamReader(channel.invertedOut, StandardCharsets.UTF_8)
    private val output = channel.invertedIn

    override val isOpen: Boolean
        get() = !closed.get() && channel.isOpen

    override fun read(buffer: CharArray, offset: Int, length: Int): Int = reader.read(buffer, offset, length)

    @Synchronized
    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun write(text: String) {
        write(text.toByteArray(StandardCharsets.UTF_8))
    }

    override fun resize(columns: Int, rows: Int) {
        require(columns > 0 && rows > 0)
        channel.sendWindowChange(columns, rows)
    }

    override fun ready(): Boolean = reader.ready()

    override fun awaitExit(): Int {
        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L)
        return channel.exitStatus ?: 0
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.close(false)
        connection.close()
    }
}
