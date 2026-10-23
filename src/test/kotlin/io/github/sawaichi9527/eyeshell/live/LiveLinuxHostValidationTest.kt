package io.github.sawaichi9527.eyeshell.live

import io.github.sawaichi9527.eyeshell.monitor.MonitorCommands
import io.github.sawaichi9527.eyeshell.monitor.MonitorSampler
import io.github.sawaichi9527.eyeshell.monitor.MonitorSnapshot
import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.KnownHostsStore
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in live validation against the Linux host that runs the test process.
 *
 * The SSH server executes real shell commands through `ProcessShellCommandFactory`, so the
 * monitor samples this host's real `/proc`, `df`, and `ps` output, and SFTP reads and writes
 * real files under a temporary directory. Enable with `EYESHELL_TEST_LIVE_LINUX_HOST=1`.
 */
class LiveLinuxHostValidationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var remoteRoot: Path
    private lateinit var server: SshServer
    private lateinit var password: CharArray

    @BeforeEach
    fun startServer() {
        assumeTrue(
            System.getenv("EYESHELL_TEST_LIVE_LINUX_HOST") == "1",
            "Set EYESHELL_TEST_LIVE_LINUX_HOST=1 to run live host validation",
        )
        remoteRoot = Files.createTempDirectory("eyeshell-live-root")
        password = UUID.randomUUID().toString().toCharArray()
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(temporaryDirectory.resolve("host-key.ser")).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            passwordAuthenticator = PasswordAuthenticator { username, candidate, _ ->
                username == USERNAME && password.contentEquals(candidate.toCharArray())
            }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(remoteRoot)
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
        if (::password.isInitialized) password.fill('\u0000')
        if (::remoteRoot.isInitialized) remoteRoot.toFile().deleteRecursively()
    }

    @Test
    fun `monitoring samples the real Linux host`() {
        connect().use { hostSession ->
            val raw = hostSession.execute(MonitorCommands.sample()).output
            assertEquals(
                MonitorCommands.SAMPLE_SECTIONS.size - 1,
                raw.count { it == MonitorCommands.SECTION_DELIMITER.single() },
                "The real remote shell must deliver printf-written section delimiters",
            )

            val sampler = MonitorSampler(
                hostSession,
                interval = Duration.ofMillis(200),
                backoff = Duration.ofMillis(200),
            )
            val latch = CountDownLatch(1)
            var latest: MonitorSnapshot? = null
            try {
                sampler.start { snapshot ->
                    latest = snapshot
                    if (snapshot.hasData) latch.countDown()
                }
                assertTrue(latch.await(10, TimeUnit.SECONDS), "Sampler never produced live metrics")

                val snapshot = requireNotNull(latest)
                assertFalse(snapshot.unsupported)
                val system = requireNotNull(snapshot.system)
                val cpu = requireNotNull(snapshot.cpu)
                val memory = requireNotNull(snapshot.memory)
                assertEquals(localHostname(), system.hostname)
                assertTrue(cpu.cores >= 1)
                assertTrue(cpu.percent in 0.0..100.0)
                assertTrue(memory.totalBytes > 0)
                assertNotNull(snapshot.load)
                assertNotNull(snapshot.network)
                assertTrue(snapshot.filesystems.isNotEmpty(), "df -P returned no filesystems")
                assertTrue(snapshot.processes.isNotEmpty(), "ps returned no processes")
                assertEquals("127.0.0.1", snapshot.endpoint)
            } finally {
                sampler.close()
            }
        }
    }

    @Test
    fun `sftp transfers real files on this host`() {
        connect().use { hostSession ->
            hostSession.sftp().use { client ->
                val upload = temporaryDirectory.resolve("live-upload.txt")
                Files.writeString(upload, "eyeShell live SFTP validation\n")
                client.upload(upload, "/live-upload.txt", overwrite = false)

                assertTrue(
                    Files.exists(remoteRoot.resolve("live-upload.txt")),
                    "Upload did not reach the real filesystem",
                )
                val listed = client.list("/")
                val remote = listed.firstOrNull { it.name == "live-upload.txt" }
                assertNotNull(remote, "Uploaded file missing from directory listing")
                assertEquals(Files.size(upload), remote!!.sizeBytes)

                val download = temporaryDirectory.resolve("live-download.txt")
                client.download("/live-upload.txt", download, overwrite = false)
                assertEquals(Files.readString(upload), Files.readString(download))

                client.rename("/live-upload.txt", "/live-renamed.txt")
                assertNotNull(client.stat("/live-renamed.txt"))
                client.delete("/live-renamed.txt")
                assertNull(client.stat("/live-renamed.txt"))
                assertFalse(Files.exists(remoteRoot.resolve("live-renamed.txt")))
            }
        }
    }

    private fun connect(): HostSession {
        val endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME)
        return SshAuthentication.Password(password).use { authentication ->
            MinaSshConnection.connect(
                endpoint = endpoint,
                authentication = authentication,
                knownHostsStore = KnownHostsStore(temporaryDirectory.resolve("known_hosts")),
                unknownHostVerifier = HostKeyVerifier { true },
                changedHostKeyHandler = ChangedHostKeyHandler { },
            )
        }
    }

    private fun localHostname(): String = Files.readString(Path.of("/proc/sys/kernel/hostname")).trim()

    private companion object {
        const val USERNAME = "live-user"
    }
}
