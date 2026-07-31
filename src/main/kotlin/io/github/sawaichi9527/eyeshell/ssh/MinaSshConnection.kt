package io.github.sawaichi9527.eyeshell.ssh

import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.StreamingChannel
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyIdentityProvider

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
            password: CharArray,
            hostKeyVerifier: HostKeyVerifier,
        ): MinaSshConnection {
            require(password.isNotEmpty()) { "SSH password must not be empty" }

            val client = SshClient.setUpDefaultClient().apply {
                hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
                keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
                userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
                serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, remoteAddress, serverKey ->
                    hostKeyVerifier.verify(
                        PresentedHostKey(
                            remoteAddress = remoteAddress.toString(),
                            algorithm = KeyUtils.getKeyType(serverKey),
                            fingerprint = KeyUtils.getFingerPrint(serverKey),
                        ),
                    )
                }
            }
            client.start()

            var session: ClientSession? = null
            try {
                session = client.connect(endpoint.username, endpoint.host, endpoint.port)
                    .verify(CONNECT_TIMEOUT)
                    .session
                val passwordIdentity = String(password)
                session.addPasswordIdentity(passwordIdentity)
                try {
                    session.auth().verify(AUTH_TIMEOUT)
                } finally {
                    session.removePasswordIdentity(passwordIdentity)
                }
                return MinaSshConnection(client, session, endpoint)
            } catch (failure: Exception) {
                session?.close(true)
                client.close(true)
                throw failure
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
