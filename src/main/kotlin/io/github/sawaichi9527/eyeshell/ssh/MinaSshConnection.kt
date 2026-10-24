package io.github.sawaichi9527.eyeshell.ssh

import io.github.sawaichi9527.eyeshell.sftp.SftpClient
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPair
import java.time.Duration
import java.util.Arrays
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.future.AuthFuture
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.PortForwardingTracker
import org.apache.sshd.common.channel.StreamingChannel
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.io.resource.PathResource
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.util.security.SecurityUtils

class MinaSshConnection private constructor(
    private val client: SshClient,
    private val clientSession: ClientSession,
    override val endpoint: SshEndpoint,
    private val upstream: Upstream? = null,
) : HostSession {
    private val closed = AtomicBoolean()

    override fun openTerminal(columns: Int, rows: Int): TerminalSession {
        require(columns > 0 && rows > 0)
        check(!closed.get()) { "SSH connection is closed" }

        val channel = clientSession.createShellChannel()
        try {
            channel.streaming = StreamingChannel.Streaming.Sync
            channel.ptyType = "xterm-256color"
            channel.ptyColumns = columns
            channel.ptyLines = rows
            channel.setRedirectErrorStream(true)
            channel.open().verify(CHANNEL_TIMEOUT)
            return MinaSshTerminalSession(endpoint.displayName, channel)
        } catch (failure: Exception) {
            channel.close(true)
            throw failure
        }
    }

    override fun execute(command: String): ExecResult {
        check(!closed.get()) { "SSH connection is closed" }
        val channel = clientSession.createExecChannel(command)
        val output = StringBuilder()
        try {
            channel.setStreaming(StreamingChannel.Streaming.Sync)
            channel.setRedirectErrorStream(true)
            channel.open().verify(CHANNEL_TIMEOUT)
            channel.invertedOut.reader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                }
            }
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), EXEC_TIMEOUT.toMillis())
            return ExecResult(channel.exitStatus ?: 0, output.toString())
        } finally {
            channel.close(true)
        }
    }

    override fun isOpen(): Boolean = !closed.get() && clientSession.isOpen

    override fun sftp(): SftpClient = MinaSftpClient(clientSession)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            clientSession.close(true)
        } catch (error: Throwable) {
            failure = error
        }
        try {
            client.close(true)
        } catch (error: Throwable) {
            failure = accumulateFailure(failure, error)
        }
        try {
            upstream?.close()
        } catch (error: Throwable) {
            failure = accumulateFailure(failure, error)
        }
        failure?.let { throw it }
    }

    private class Upstream(
        private val jumpConnection: OpenedConnection,
        private val tracker: PortForwardingTracker,
    ) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            try {
                tracker.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                jumpConnection.close()
            } catch (error: Throwable) {
                failure = accumulateFailure(failure, error)
            }
            failure?.let { throw it }
        }
    }

    companion object {
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val AUTH_TIMEOUT = Duration.ofSeconds(15)
        private val CHANNEL_TIMEOUT = Duration.ofSeconds(10)
        private val EXEC_TIMEOUT = Duration.ofSeconds(10)

        fun connect(
            endpoint: SshEndpoint,
            authentication: SshAuthentication,
            knownHostsStore: KnownHostsStore,
            unknownHostVerifier: HostKeyVerifier,
            changedHostKeyHandler: ChangedHostKeyHandler = ChangedHostKeyHandler {},
            transport: TransportProvider = TransportProvider.Direct,
        ): MinaSshConnection {
            val jump = transport as? TransportProvider.JumpHost
            if (jump != null) {
                require(!(jump.endpoint.host == endpoint.host && jump.endpoint.port == endpoint.port)) {
                    "A host cannot use itself as its jump host"
                }
            }
            val jumpConnection = jump?.let {
                connectDirect(
                    endpoint = it.endpoint,
                    authentication = it.authentication,
                    knownHostsStore = knownHostsStore,
                    unknownHostVerifier = it.unknownHostVerifier,
                    changedHostKeyHandler = it.changedHostKeyHandler,
                )
            }
            var tracker: PortForwardingTracker? = null
            try {
                val route = if (jumpConnection != null) {
                    val forwarding = jumpConnection.session.createLocalPortForwardingTracker(
                        SshdSocketAddress(SshdSocketAddress.LOCALHOST_NAME, 0),
                        SshdSocketAddress(endpoint.host, endpoint.port),
                    )
                    tracker = forwarding
                    check(forwarding.boundAddress.port > 0) { "Jump host forwarding was not bound" }
                    forwarding.boundAddress
                } else {
                    null
                }
                val opened = connectDirect(
                    endpoint = endpoint,
                    authentication = authentication,
                    knownHostsStore = knownHostsStore,
                    unknownHostVerifier = unknownHostVerifier,
                    changedHostKeyHandler = changedHostKeyHandler,
                    route = route,
                )
                return MinaSshConnection(
                    opened.client,
                    opened.session,
                    endpoint,
                    upstream = jumpConnection?.let { Upstream(it, checkNotNull(tracker)) },
                )
            } catch (failure: Throwable) {
                try {
                    tracker?.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                try {
                    jumpConnection?.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        private fun connectDirect(
            endpoint: SshEndpoint,
            authentication: SshAuthentication,
            knownHostsStore: KnownHostsStore,
            unknownHostVerifier: HostKeyVerifier,
            changedHostKeyHandler: ChangedHostKeyHandler,
            route: SshdSocketAddress? = null,
        ): OpenedConnection {
            val keyboardInteraction = (authentication as? SshAuthentication.KeyboardInteractive)
                ?.let(::KeyboardUserInteraction)
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
                    is SshAuthentication.KeyboardInteractive -> {
                        userInteraction = keyboardInteraction
                        listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
                    }
                    is SshAuthentication.Agent -> {
                        agentFactory = OpenSshAgentFactory(authentication.endpoint)
                        listOf(UserAuthPublicKeyFactory.INSTANCE)
                    }
                }
                serverKeyVerifier = knownHostsStore.createServerKeyVerifier(
                    unknownHostVerifier,
                    changedHostKeyHandler,
                    verificationAddress = SshdSocketAddress(endpoint.host, endpoint.port),
                )
            }
            client.start()

            var session: ClientSession? = null
            try {
                val connectedSession = client.connect(
                    endpoint.username,
                    route?.hostName ?: endpoint.host,
                    route?.port ?: endpoint.port,
                )
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
                    val authFuture = connectedSession.auth()
                    if (authentication is SshAuthentication.KeyboardInteractive) {
                        val interaction = checkNotNull(keyboardInteraction)
                        try {
                            verifyKeyboardInteractive(authFuture, interaction)
                        } finally {
                            interaction.cancel()
                        }
                    } else {
                        authFuture.verify(AUTH_TIMEOUT)
                    }
                } finally {
                    passwordIdentity?.let(connectedSession::removePasswordIdentity)
                    publicKeys.forEach(connectedSession::removePublicKeyIdentity)
                }
                return OpenedConnection(client, connectedSession)
            } catch (failure: Throwable) {
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

        private fun verifyKeyboardInteractive(authFuture: AuthFuture, interaction: KeyboardUserInteraction) {
            var networkDeadline = System.nanoTime() + AUTH_TIMEOUT.toNanos()
            while (!authFuture.await(100, TimeUnit.MILLISECONDS)) {
                if (interaction.awaitingResponse.get()) {
                    networkDeadline = System.nanoTime() + AUTH_TIMEOUT.toNanos()
                } else if (System.nanoTime() >= networkDeadline) {
                    throw java.net.SocketTimeoutException("SSH authentication timed out")
                }
            }
            authFuture.verify()
        }
    }
}

private class KeyboardUserInteraction(
    private val authentication: SshAuthentication.KeyboardInteractive,
) : UserInteraction {
    val awaitingResponse = AtomicBoolean()

    override fun interactive(
        session: ClientSession,
        name: String,
        instruction: String,
        lang: String,
        prompt: Array<String>,
        echo: BooleanArray,
    ): Array<String>? {
        val challenge = KeyboardInteractiveChallenge(
            name = name,
            instruction = instruction,
            language = lang,
            prompts = prompt.mapIndexed { index, text -> KeyboardInteractivePrompt(text, echo[index]) },
        )
        awaitingResponse.set(true)
        val responses = try {
            authentication.responder.respond(challenge)
        } finally {
            awaitingResponse.set(false)
        } ?: return null
        return try {
            require(responses.size == prompt.size) {
                "Keyboard-interactive response count must match the prompt count"
            }
            Array(responses.size) { index -> String(responses[index]) }
        } finally {
            responses.forEach { Arrays.fill(it, '\u0000') }
        }
    }

    override fun getUpdatedPassword(session: ClientSession, prompt: String, lang: String): String? = null

    fun cancel() = authentication.cancel()
}

private class OpenedConnection(
    val client: SshClient,
    val session: ClientSession,
) {
    fun close() {
        var failure: Throwable? = null
        try {
            session.close(true)
        } catch (error: Throwable) {
            failure = error
        }
        try {
            client.close(true)
        } catch (error: Throwable) {
            failure = accumulateFailure(failure, error)
        }
        failure?.let { throw it }
    }
}

private fun accumulateFailure(current: Throwable?, next: Throwable): Throwable {
    if (current == null) return next
    if (current !== next) current.addSuppressed(next)
    return current
}

private class MinaSshTerminalSession(
    override val name: String,
    private val channel: ChannelShell,
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
    }
}
