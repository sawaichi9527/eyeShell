package io.github.sawaichi9527.eyeshell.monitor

import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.KnownHostsStore
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MonitorIntegrationTest {
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
            commandFactory = ScriptedMonitorCommandFactory()
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        server.stop(true)
        password.fill('\u0000')
    }

    @Test
    fun `samples a Linux host end to end through a real transport`() {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
        val hostSession = SshAuthentication.Password(password).use { authentication ->
            MinaSshConnection.connect(
                endpoint = endpoint,
                authentication = authentication,
                knownHostsStore = KnownHostsStore(temporaryDirectory.resolve("known_hosts")),
                unknownHostVerifier = HostKeyVerifier { true },
                changedHostKeyHandler = ChangedHostKeyHandler { },
            )
        }
        val sampler = MonitorSampler(hostSession, interval = Duration.ofMillis(50), backoff = Duration.ofMillis(50))
        val latch = CountDownLatch(1)
        var snapshot: MonitorSnapshot? = null

        try {
            sampler.start {
                snapshot = it
                if (it.hasData) latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "sampler never published a data snapshot over the real transport")

            val received = requireNotNull(snapshot)
            assertNotNull(received.system)
            assertEquals("lab-server", received.system!!.hostname)
            assertNotNull(received.cpu)
            assertNotNull(received.memory)
            assertNotNull(received.swap)
            assertNotNull(received.load)
            assertNotNull(received.network)
            assertEquals(2, received.filesystems.size)
            assertTrue(received.processes.isNotEmpty())
        } finally {
            sampler.close()
            hostSession.close()
        }
    }

    @Test
    fun `renders the sampled metrics in the monitor panel on the EDT`() {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
        val hostSession = SshAuthentication.Password(password).use { authentication ->
            MinaSshConnection.connect(
                endpoint = endpoint,
                authentication = authentication,
                knownHostsStore = KnownHostsStore(temporaryDirectory.resolve("known_hosts")),
                unknownHostVerifier = HostKeyVerifier { true },
                changedHostKeyHandler = ChangedHostKeyHandler { },
            )
        }
        val sampler = MonitorSampler(hostSession, interval = Duration.ofMillis(50), backoff = Duration.ofMillis(50))
        val latch = CountDownLatch(1)
        val panel = io.github.sawaichi9527.eyeshell.ui.MonitorPanel()
        var snapshot: MonitorSnapshot? = null

        try {
            sampler.start {
                snapshot = it
                if (it.hasData) latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "sampler never published a data snapshot")

            javax.swing.SwingUtilities.invokeAndWait {
                panel.update(requireNotNull(snapshot))
            }
            val text = renderText(panel)
            assertTrue(text.contains("lab-server"))
            assertTrue(text.contains("CPU"))
            assertTrue(text.contains("Memory"))
            val processRows = renderTable(panel)
            assertTrue(processRows.contains("some-daemon"))
        } finally {
            sampler.close()
            hostSession.close()
        }
    }

    private fun renderText(container: java.awt.Container): String {
        val builder = StringBuilder()
        container.components.forEach { component ->
            if (component is javax.swing.JLabel) builder.append(component.text).append("\n")
            if (component is java.awt.Container) builder.append(renderText(component))
        }
        return builder.toString()
    }

    private fun renderTable(container: java.awt.Container): String {
        val builder = StringBuilder()
        container.components.forEach { component ->
            if (component is javax.swing.JTable) {
                for (row in 0 until component.rowCount) {
                    for (col in 0 until component.columnCount) {
                        builder.append(component.getValueAt(row, col)).append("\n")
                    }
                }
            }
            if (component is java.awt.Container) builder.append(renderTable(component))
        }
        return builder.toString()
    }

    private class ScriptedMonitorCommandFactory : CommandFactory {
        override fun createCommand(channel: ChannelSession, command: String): Command = ScriptedMonitorCommand(command)

        private class ScriptedMonitorCommand(
            private val command: String,
        ) : Command {
            private lateinit var output: OutputStream
            private lateinit var exitCallback: ExitCallback
            private var worker: Thread? = null

            override fun setInputStream(input: InputStream) = Unit

            override fun setOutputStream(output: OutputStream) {
                this.output = output
            }

            override fun setErrorStream(error: OutputStream) = Unit

            override fun setExitCallback(callback: ExitCallback) {
                exitCallback = callback
            }

            override fun start(channel: ChannelSession, env: Environment) {
                worker = Thread.ofVirtual().name("embedded-monitor-exec").start {
                    try {
                        val body = when {
                            command.contains("uname") -> SYSTEM_INFO
                            else -> SAMPLE
                        }
                        output.write(body.toByteArray(StandardCharsets.UTF_8))
                        output.flush()
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
    }

    companion object {
        private const val USERNAME = "test-user"
        private val SYSTEM_INFO = "Linux lab-server 6.8.0-45-generic #1 SMP x86_64 GNU/Linux\n1000 2000\n"
        private val SAMPLE = """
            cpu  100 0 100 800 50 0 0 0 0 0
            cpu0 50 0 50 400 25 0 0 0 0 0
            cpu1 50 0 50 400 25 0 0 0 0 0
            ${"\u000C"}MemTotal:       16000000 kB
            MemFree:         4000000 kB
            MemAvailable:    6000000 kB
            SwapTotal:       2000000 kB
            SwapFree:         500000 kB
            ${"\u000C"}0.52 0.33 0.20 2/345 12345
            ${"\u000C"}Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo:  1000       10    0    0    0     0          0         0   1000       10    0    0    0     0       0          0
              eth0:  100000    100    0    0    0     0          0         0   50000     50    0    0    0     0       0          0
            ${"\u000C"}Filesystem     1024-blocks      Used Available Capacity Mounted on
            /dev/sda1        100000000  40000000  60000000      40% /
            /dev/sdb1       1000000000  800000000 200000000      80% /data
            ${"\u000C"}1234 204800  12.5 some-daemon
            5678  51200   3.2 sshd
        """.trimIndent()
    }
}
