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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in interoperability check against a real OpenSSH server.
 *
 * Configure with `EYESHELL_TEST_OPENSSH=1` plus optional `EYESHELL_TEST_OPENSSH_HOST`,
 * `EYESHELL_TEST_OPENSSH_PORT`, `EYESHELL_TEST_OPENSSH_USER`, and
 * `EYESHELL_TEST_OPENSSH_KEY` (defaults: 127.0.0.1, 22, current user, `~/.ssh/id_ed25519`).
 * The key must be readable without an interactive passphrase prompt.
 */
class OpenSshInteroperabilityTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @BeforeEach
    fun requireOptIn() {
        assumeTrue(
            System.getenv("EYESHELL_TEST_OPENSSH") == "1",
            "Set EYESHELL_TEST_OPENSSH=1 to run OpenSSH interoperability validation",
        )
    }

    @Test
    fun `exec monitoring and the terminal interoperate with OpenSSH`() {
        connect().use { hostSession ->
            val hostname = hostSession.execute("uname -n").output.trim()
            assertEquals(localHostname(), hostname)

            val sample = hostSession.execute(MonitorCommands.sample()).output
            assertEquals(
                MonitorCommands.SAMPLE_SECTIONS.size - 1,
                sample.count { it == MonitorCommands.SECTION_DELIMITER.single() },
                "OpenSSH must deliver printf-written delimiters",
            )

            val sampler = MonitorSampler(
                hostSession,
                interval = Duration.ofMillis(200),
                backoff = Duration.ofMillis(200),
            )
            val snapshotLatch = CountDownLatch(1)
            var latest: MonitorSnapshot? = null
            try {
                sampler.start { snapshot ->
                    latest = snapshot
                    if (snapshot.hasData) snapshotLatch.countDown()
                }
                assertTrue(snapshotLatch.await(10, TimeUnit.SECONDS), "Sampler never produced OpenSSH metrics")
                val snapshot = requireNotNull(latest)
                val system = requireNotNull(snapshot.system)
                assertEquals(localHostname(), system.hostname)
                assertTrue(snapshot.filesystems.isNotEmpty())
                assertTrue(snapshot.processes.isNotEmpty())
            } finally {
                sampler.close()
            }

            val terminal = hostSession.openTerminal()
            try {
                terminal.write("printf 'EYESHELL-%s\\n' OK\n")
                val buffer = CharArray(4096)
                val output = StringBuilder()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline && !output.contains("EYESHELL-OK")) {
                    if (terminal.ready()) {
                        val read = terminal.read(buffer, 0, buffer.size)
                        if (read > 0) output.append(buffer, 0, read)
                    } else {
                        Thread.sleep(20)
                    }
                }
                assertTrue(output.contains("EYESHELL-OK"), "OpenSSH shell did not echo the marker: $output")
            } finally {
                terminal.close()
            }
        }
    }

    @Test
    fun `sftp interoperates with the OpenSSH sftp subsystem`() {
        val remoteDirectory = Files.createTempDirectory("eyeshell-openssh-")
        try {
            connect().use { hostSession ->
                hostSession.sftp().use { client ->
                    val upload = temporaryDirectory.resolve("openssh-upload.txt")
                    Files.writeString(upload, "eyeShell OpenSSH SFTP validation\n")
                    val remoteFile = "$remoteDirectory/openssh-upload.txt"

                    client.upload(upload, remoteFile, overwrite = false)
                    assertTrue(Files.exists(Path.of(remoteFile)), "OpenSSH SFTP did not write the real file")

                    val listed = client.list(remoteDirectory.toString()).firstOrNull { it.name == "openssh-upload.txt" }
                    val remoteEntry = requireNotNull(listed)
                    assertEquals(Files.size(upload), remoteEntry.sizeBytes)

                    val download = temporaryDirectory.resolve("openssh-download.txt")
                    client.download(remoteFile, download, overwrite = false)
                    assertEquals(Files.readString(upload), Files.readString(download))

                    client.rename(remoteFile, "$remoteDirectory/openssh-renamed.txt")
                    assertNotNull(client.stat("$remoteDirectory/openssh-renamed.txt"))
                    client.delete("$remoteDirectory/openssh-renamed.txt")
                    assertNull(client.stat("$remoteDirectory/openssh-renamed.txt"))
                    assertTrue(Files.notExists(Path.of("$remoteDirectory/openssh-renamed.txt")))
                }
            }
        } finally {
            remoteDirectory.toFile().deleteRecursively()
        }
    }

    private fun connect(): HostSession {
        val keyFile = Path.of(
            System.getenv("EYESHELL_TEST_OPENSSH_KEY")?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), ".ssh", "id_ed25519").toString(),
        )
        val endpoint = SshEndpoint(
            host = env("EYESHELL_TEST_OPENSSH_HOST", "127.0.0.1"),
            port = env("EYESHELL_TEST_OPENSSH_PORT", "22").toInt(),
            username = env("EYESHELL_TEST_OPENSSH_USER", System.getProperty("user.name")),
        )
        return SshAuthentication.PublicKey(keyFile, CharArray(0)).use { authentication ->
            MinaSshConnection.connect(
                endpoint = endpoint,
                authentication = authentication,
                knownHostsStore = KnownHostsStore(temporaryDirectory.resolve("known_hosts")),
                unknownHostVerifier = HostKeyVerifier { true },
                changedHostKeyHandler = ChangedHostKeyHandler { },
            )
        }
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private fun localHostname(): String = Files.readString(Path.of("/proc/sys/kernel/hostname")).trim()
}
