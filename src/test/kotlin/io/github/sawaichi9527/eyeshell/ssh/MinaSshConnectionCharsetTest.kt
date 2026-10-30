package io.github.sawaichi9527.eyeshell.ssh

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Covers the terminal character-encoding boundary of a real (embedded) SSH session: the decoding of
 * remote bytes, the encoding of typed text, and the live switch between two charsets.
 */
class MinaSshConnectionCharsetTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var server: SshServer
    private lateinit var password: CharArray

    @BeforeEach
    fun startServer() {
        password = UUID.randomUUID().toString().toCharArray()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(temporaryDirectory.resolve("host-key.ser")).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, candidate, _ ->
                username == USERNAME && password.contentEquals(candidate.toCharArray())
            }
            shellFactory = ShellFactory { EchoShellCommand() }
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        server.stop(true)
        password.fill('\u0000')
    }

    @Test
    fun `typed text is encoded and remote bytes are decoded with the switched charset`() {
        val gbk = Charset.forName("GBK")
        withTerminal { terminal ->
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))
            assertEquals(StandardCharsets.UTF_8, terminal.charset)

            terminal.setCharset(gbk)
            assertEquals(gbk, terminal.charset)
            terminal.write("中文輸出\r\n")

            assertTrue(readUntil(terminal, "echo:中文輸出\r\n").contains("echo:中文輸出\r\n"))
        }
    }

    @Test
    fun `bytes that arrive before the switch are reinterpreted with the new charset`() {
        val big5 = Charset.forName("Big5")
        withTerminal { terminal ->
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))

            // Raw bytes: the session must not encode them, only the decoding side changes.
            terminal.write("繁體中文".toByteArray(big5))
            terminal.setCharset(big5)

            assertTrue(readUntil(terminal, "echo:繁體中文").contains("echo:繁體中文"))
        }
    }

    @Test
    fun `switching back to utf-8 resumes utf-8 decoding`() {
        val gbk = Charset.forName("GBK")
        withTerminal { terminal ->
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))

            terminal.setCharset(gbk)
            terminal.write("簡體\r\n")
            assertTrue(readUntil(terminal, "echo:簡體\r\n").contains("echo:簡體\r\n"))

            terminal.setCharset(StandardCharsets.UTF_8)
            terminal.write("繁體\r\n")
            assertTrue(readUntil(terminal, "echo:繁體\r\n").contains("echo:繁體\r\n"))
        }
    }

    @Test
    fun `undecodable bytes become the replacement character instead of failing the session`() {
        withTerminal { terminal ->
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))

            terminal.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

            assertTrue(readUntil(terminal, "\uFFFD").contains('\uFFFD'))
            terminal.write("\r\n")
            assertTrue(readUntil(terminal, "echo:").contains("echo:"))
        }
    }

    @Test
    fun `the session retains raw output for a later re-decode`() {
        val gbk = Charset.forName("GBK")
        withTerminal { terminal ->
            assertTrue(readUntil(terminal, "ready\r\n").contains("ready\r\n"))

            terminal.setCharset(gbk)
            terminal.write("中文輸出\r\n")
            assertTrue(readUntil(terminal, "echo:中文輸出\r\n").contains("echo:中文輸出"))

            val raw = terminal.rawOutput()
            assertTrue(raw != null && raw.isNotEmpty(), "The session must retain its raw bytes")
            assertTrue(String(raw!!, gbk).contains("中文輸出"))
        }
    }

    private fun withTerminal(block: (io.github.sawaichi9527.eyeshell.terminal.TerminalSession) -> Unit) {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
        val connection = SshAuthentication.Password(password).use { authentication ->
            MinaSshConnection.connect(
                endpoint = endpoint,
                authentication = authentication,
                knownHostsStore = KnownHostsStore(temporaryDirectory.resolve("config").resolve("known_hosts")),
                unknownHostVerifier = HostKeyVerifier { true },
            )
        }
        try {
            val terminal = connection.openTerminal()
            try {
                block(terminal)
            } finally {
                terminal.close()
            }
        } finally {
            connection.close()
        }
    }

    private fun readUntil(
        terminal: io.github.sawaichi9527.eyeshell.terminal.TerminalSession,
        expected: String,
    ): String {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        val result = StringBuilder()
        val buffer = CharArray(256)
        while (!result.contains(expected)) {
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

    /** Banner plus a raw echo, so the same bytes exercise both directions of the charset. */
    private class EchoShellCommand : Command {
        private lateinit var input: java.io.InputStream
        private lateinit var output: java.io.OutputStream
        private lateinit var exitCallback: ExitCallback
        private var worker: Thread? = null

        override fun setInputStream(input: java.io.InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: java.io.OutputStream) {
            this.output = output
        }

        override fun setErrorStream(error: java.io.OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            exitCallback = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            worker = Thread.ofVirtual().name("embedded-ssh-charset-shell").start {
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

    private companion object {
        private const val USERNAME = "test-user"
    }
}
