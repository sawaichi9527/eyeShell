package io.github.sawaichi9527.eyeshell.ssh

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MinaSshConnectionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var server: SshServer
    private lateinit var password: CharArray
    private lateinit var userKeyPair: KeyPair

    @BeforeEach
    fun startServer() {
        password = UUID.randomUUID().toString().toCharArray()
        userKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server = createServer(0, temporaryDirectory.resolve("host-key.ser"))
    }

    private fun createServer(serverPort: Int, hostKeyFile: Path): SshServer =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = serverPort
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, candidate, _ ->
                username == USERNAME && password.contentEquals(candidate.toCharArray())
            }
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { username, key, _ ->
                username == USERNAME && KeyUtils.compareKeys(userKeyPair.public, key)
            }
            shellFactory = ShellFactory { EchoShellCommand() }
            start()
        }

    @AfterEach
    fun stopServer() {
        server.stop(true)
        password.fill('\u0000')
    }

    @Test
    fun `opens authenticated shell after explicit host key acceptance`() {
        val presentedKey = AtomicReference<PresentedHostKey>()
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
        val connection = SshAuthentication.Password(password).use { authentication ->
            MinaSshConnection.connect(
                endpoint = endpoint,
                authentication = authentication,
                knownHostsStore = knownHostsStore(),
                unknownHostVerifier = HostKeyVerifier { hostKey ->
                    presentedKey.set(hostKey)
                    true
                },
            )
        }
        val terminal = connection.openTerminal(columns = 100, rows = 30)

        try {
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))
            terminal.write("hello 中文\r\n")
            assertTrue(readUntil(terminal, "echo:hello 中文\r\n").contains("echo:hello 中文\r\n"))
            terminal.resize(120, 40)
            assertEquals(endpoint.displayName, terminal.name)
            assertTrue(terminal.isOpen)
            assertTrue(presentedKey.get().fingerprint.startsWith("SHA256:"))
            assertTrue(presentedKey.get().algorithm.startsWith("ssh-rsa"))
        } finally {
            terminal.close()
        }

        assertFalse(terminal.isOpen)
    }

    @Test
    fun `rejects unverified host key before authentication`() {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)

        assertThrows(Exception::class.java) {
            SshAuthentication.Password(password).use { authentication ->
                MinaSshConnection.connect(
                    endpoint,
                    authentication,
                    knownHostsStore(),
                    HostKeyVerifier { false },
                )
            }
        }
    }

    @Test
    fun `rejects invalid password after host key acceptance`() {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)

        assertThrows(Exception::class.java) {
            SshAuthentication.Password(UUID.randomUUID().toString().toCharArray()).use { authentication ->
                MinaSshConnection.connect(
                    endpoint,
                    authentication,
                    knownHostsStore(),
                    HostKeyVerifier { true },
                )
            }
        }
    }

    @Test
    fun `persists accepted host key and rejects a changed key`() {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
        val knownHosts = knownHostsStore()
        val prompts = AtomicInteger()

        connectWithPassword(endpoint, knownHosts) {
            prompts.incrementAndGet()
            true
        }.close()
        assertTrue(knownHosts.file.toFile().readText().contains("ssh-rsa"))

        connectWithPassword(endpoint, knownHosts) {
            prompts.incrementAndGet()
            false
        }.close()
        assertEquals(1, prompts.get())

        val port = server.port
        server.stop(true)
        server = createServer(port, temporaryDirectory.resolve("changed-host-key.ser"))
        val changedKey = AtomicReference<ChangedHostKey>()
        assertThrows(Exception::class.java) {
            SshAuthentication.Password(password).use { authentication ->
                MinaSshConnection.connect(
                    endpoint = endpoint,
                    authentication = authentication,
                    knownHostsStore = knownHosts,
                    unknownHostVerifier = HostKeyVerifier { true },
                    changedHostKeyHandler = ChangedHostKeyHandler(changedKey::set),
                )
            }
        }
        assertTrue(changedKey.get().expectedFingerprint.startsWith("SHA256:"))
        assertTrue(changedKey.get().actualFingerprint.startsWith("SHA256:"))
        assertFalse(changedKey.get().expectedFingerprint == changedKey.get().actualFingerprint)
    }

    @Test
    fun `authenticates with encrypted OpenSSH private key`() {
        val passphrase = UUID.randomUUID().toString().toCharArray()
        val privateKeyFile = temporaryDirectory.resolve("client-key")
        val encryption = OpenSSHKeyEncryptionContext().apply {
            setPassword(String(passphrase))
            cipherName = "AES"
            cipherMode = "CTR"
            cipherType = "256"
        }
        java.nio.file.Files.newOutputStream(privateKeyFile).use { output ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(userKeyPair, "", encryption, output)
        }
        try {
            java.nio.file.Files.setPosixFilePermissions(
                privateKeyFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs are outside this Linux-focused test assertion.
        }
        val connection = try {
            val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
            SshAuthentication.PublicKey(privateKeyFile, passphrase).use { authentication ->
                MinaSshConnection.connect(
                    endpoint,
                    authentication,
                    knownHostsStore(),
                    HostKeyVerifier { true },
                )
            }
        } finally {
            encryption.setPassword("")
            passphrase.fill('\u0000')
        }

        val terminal = connection.openTerminal()
        try {
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))
        } finally {
            terminal.close()
        }
    }

    private fun connectWithPassword(
        endpoint: SshEndpoint,
        knownHosts: KnownHostsStore,
        verifier: HostKeyVerifier,
    ): MinaSshConnection = SshAuthentication.Password(password).use { authentication ->
        MinaSshConnection.connect(endpoint, authentication, knownHosts, verifier)
    }

    private fun knownHostsStore(): KnownHostsStore =
        KnownHostsStore(temporaryDirectory.resolve("config").resolve("known_hosts"))

    private fun readUntil(
        terminal: io.github.sawaichi9527.eyeshell.terminal.TerminalSession,
        expected: String,
    ): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        val result = StringBuilder()
        val buffer = CharArray(256)
        while (expected !in result) {
            check(System.nanoTime() < deadline) { "Did not receive '$expected'; received '$result'" }
            if (!terminal.ready()) {
                Thread.sleep(10)
                continue
            }
            val count = terminal.read(buffer, 0, buffer.size)
            check(count >= 0) { "SSH shell closed before '$expected'; received '$result'" }
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private class EchoShellCommand : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var exitCallback: ExitCallback
        private var worker: Thread? = null

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(error: OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            exitCallback = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            worker = Thread.ofVirtual().name("embedded-ssh-shell").start {
                try {
                    output.write("ready\r\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    val buffer = ByteArray(256)
                    while (!Thread.currentThread().isInterrupted) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write("echo:".toByteArray(StandardCharsets.UTF_8))
                        output.write(buffer, 0, count)
                        output.flush()
                    }
                    exitCallback.onExit(0)
                } catch (_: Exception) {
                    exitCallback.onExit(1)
                }
            }
        }

        override fun destroy(channel: ChannelSession) {
            worker?.interrupt()
        }
    }

    companion object {
        private const val USERNAME = "test-user"
    }
}
