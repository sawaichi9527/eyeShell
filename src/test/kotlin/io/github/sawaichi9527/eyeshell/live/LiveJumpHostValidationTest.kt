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
import io.github.sawaichi9527.eyeshell.ssh.TransportProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in jump host validation against two real OpenSSH servers.
 *
 * Enable with `EYESHELL_TEST_OPENSSH_JUMP=1` after starting a bastion and a target that is
 * only reachable through the bastion. Environment:
 *
 * - jump host: `EYESHELL_TEST_JUMP_HOST` (127.0.0.1), `EYESHELL_TEST_JUMP_PORT` (2223),
 *   `EYESHELL_TEST_JUMP_USER` (current user), `EYESHELL_TEST_JUMP_KEY` (`~/.ssh/id_ed25519`)
 * - target: `EYESHELL_TEST_TARGET_HOST` (127.0.0.1), `EYESHELL_TEST_TARGET_PORT` (2222),
 *   `EYESHELL_TEST_TARGET_USER` (current user), `EYESHELL_TEST_TARGET_KEY` (same as jump)
 * - optional `EYESHELL_TEST_JUMP_LOG`: bastion `sshd -e` log; when set, the test asserts that the
 *   bastion actually opened a `direct-tcpip` forward to the target
 *
 * The keys must be readable without an interactive passphrase prompt.
 */
class LiveJumpHostValidationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var knownHosts: KnownHostsStore

    @BeforeEach
    fun requireOptIn() {
        assumeTrue(
            System.getenv("EYESHELL_TEST_OPENSSH_JUMP") == "1",
            "Set EYESHELL_TEST_OPENSSH_JUMP=1 to run the OpenSSH jump host validation",
        )
        knownHosts = KnownHostsStore(temporaryDirectory.resolve("known_hosts"))
    }

    @Test
    fun `exec monitoring and the terminal run through the OpenSSH jump host`() {
        connect().use { hostSession ->
            assertEquals(localHostname(), hostSession.execute("uname -n").output.trim())

            val sample = hostSession.execute(MonitorCommands.sample()).output
            assertEquals(
                MonitorCommands.SAMPLE_SECTIONS.size - 1,
                sample.count { it == MonitorCommands.SECTION_DELIMITER.single() },
                "The jump host must carry the printf-written monitor delimiters",
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
                assertTrue(snapshotLatch.await(10, TimeUnit.SECONDS), "Sampler produced no metrics via jump")
                val system = requireNotNull(requireNotNull(latest).system)
                assertEquals(localHostname(), system.hostname)
            } finally {
                sampler.close()
            }

            val terminal = hostSession.openTerminal()
            try {
                terminal.write("printf 'EYESHELL-JUMP-%s\\n' OK\n")
                val output = readUntil(terminal, "EYESHELL-JUMP-OK", 10)
                assertTrue(output.contains("EYESHELL-JUMP-OK"), "Jump host shell did not echo the marker: $output")
            } finally {
                terminal.close()
            }
        }

        assertKnownHostsPinnedToEndpoints()
        assertJumpHostForwardedTraffic()
    }

    @Test
    fun `sftp runs through the OpenSSH jump host`() {
        val remoteDirectory = Files.createTempDirectory("eyeshell-jump-sftp-")
        try {
            connect().use { hostSession ->
                hostSession.sftp().use { client ->
                    val upload = temporaryDirectory.resolve("jump-upload.txt")
                    Files.writeString(upload, "eyeShell jump host SFTP validation\n")
                    val remoteFile = "$remoteDirectory/jump-upload.txt"

                    client.upload(upload, remoteFile, overwrite = false)
                    val listed = client.list(remoteDirectory.toString()).firstOrNull { it.name == "jump-upload.txt" }
                    assertEquals(Files.size(upload), requireNotNull(listed).sizeBytes)

                    val download = temporaryDirectory.resolve("jump-download.txt")
                    client.download(remoteFile, download, overwrite = false)
                    assertEquals(Files.readString(upload), Files.readString(download))

                    client.rename(remoteFile, "$remoteDirectory/jump-renamed.txt")
                    assertNotNull(client.stat("$remoteDirectory/jump-renamed.txt"))
                    client.delete("$remoteDirectory/jump-renamed.txt")
                    assertNull(client.stat("$remoteDirectory/jump-renamed.txt"))
                }
            }
        } finally {
            remoteDirectory.toFile().deleteRecursively()
        }

        assertKnownHostsPinnedToEndpoints()
        assertJumpHostForwardedTraffic()
    }

    private fun connect(): HostSession {
        val jumpEndpoint = endpoint("EYESHELL_TEST_JUMP", defaultPort = 2223)
        val targetEndpoint = endpoint("EYESHELL_TEST_TARGET", defaultPort = 2222)
        val prompts = AtomicReference<MutableList<String>>(mutableListOf())

        return SshAuthentication.PublicKey(keyFile("EYESHELL_TEST_TARGET_KEY"), CharArray(0)).use { authentication ->
            SshAuthentication.PublicKey(keyFile("EYESHELL_TEST_JUMP_KEY"), CharArray(0)).use { jumpAuthentication ->
                MinaSshConnection.connect(
                    endpoint = targetEndpoint,
                    authentication = authentication,
                    knownHostsStore = knownHosts,
                    unknownHostVerifier = HostKeyVerifier { presented ->
                        prompts.get().add(presented.remoteAddress)
                        true
                    },
                    changedHostKeyHandler = ChangedHostKeyHandler { },
                    transport = TransportProvider.JumpHost(
                        endpoint = jumpEndpoint,
                        authentication = jumpAuthentication,
                        unknownHostVerifier = HostKeyVerifier { presented ->
                            prompts.get().add(presented.remoteAddress)
                            true
                        },
                        changedHostKeyHandler = ChangedHostKeyHandler { },
                    ),
                ).also {
                    assertEquals(
                        setOf(jumpEndpoint.addressString(), targetEndpoint.addressString()),
                        prompts.get().toSet(),
                        "Each hop must be verified at its own host:port",
                    )
                }
            }
        }
    }

    private fun endpoint(prefix: String, defaultPort: Int): SshEndpoint = SshEndpoint(
        host = env("${prefix}_HOST", "127.0.0.1"),
        port = env("${prefix}_PORT", defaultPort.toString()).toInt(),
        username = env("${prefix}_USER", System.getProperty("user.name")),
    )

    private fun SshEndpoint.addressString(): String = "$host:$port"

    private fun keyFile(variable: String): Path = Path.of(
        System.getenv(variable)?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".ssh", "id_ed25519").toString(),
    )

    private fun assertKnownHostsPinnedToEndpoints() {
        val recorded = knownHosts.file.toFile().readLines()
            .filter(String::isNotBlank)
            .map { it.substringBefore(' ') }
            .toSet()
        val expected = setOf(
            "[${env("EYESHELL_TEST_JUMP_HOST", "127.0.0.1")}]:${env("EYESHELL_TEST_JUMP_PORT", "2223")}",
            "[${env("EYESHELL_TEST_TARGET_HOST", "127.0.0.1")}]:${env("EYESHELL_TEST_TARGET_PORT", "2222")}",
        )
        assertEquals(expected, recorded, "Known Hosts must pin both hops and no loopback forwarding port")
    }

    private fun assertJumpHostForwardedTraffic() {
        val logPath = System.getenv("EYESHELL_TEST_JUMP_LOG")?.takeIf { it.isNotBlank() } ?: return
        val log = Files.readString(Path.of(logPath))
        val targetPort = env("EYESHELL_TEST_TARGET_PORT", "2222")
        assertTrue(
            log.contains("direct-tcpip") || log.contains("target 127.0.0.1 port $targetPort"),
            "The bastion log does not show a forwarded channel to the target",
        )
    }

    private fun readUntil(
        terminal: io.github.sawaichi9527.eyeshell.terminal.TerminalSession,
        marker: String,
        seconds: Long,
    ): String {
        val buffer = CharArray(4096)
        val output = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline && !output.contains(marker)) {
            if (terminal.ready()) {
                val read = terminal.read(buffer, 0, buffer.size)
                if (read > 0) output.append(buffer, 0, read)
            } else {
                Thread.sleep(20)
            }
        }
        return output.toString()
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private fun localHostname(): String = Files.readString(Path.of("/proc/sys/kernel/hostname")).trim()
}
