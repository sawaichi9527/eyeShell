package io.github.sawaichi9527.eyeshell.sftp

import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.KnownHostsStore
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SftpIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var server: SshServer
    private lateinit var password: CharArray
    private lateinit var remoteRoot: Path

    @BeforeEach
    fun startServer() {
        password = UUID.randomUUID().toString().toCharArray()
        remoteRoot = Files.createTempDirectory("sftp-root")
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
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(remoteRoot)
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        server.stop(true)
        password.fill('\u0000')
        remoteRoot.toFile().deleteRecursively()
    }

    @Test
    fun `lists uploads downloads renames and deletes over a real SFTP channel`() {
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
        val controller = SftpController(hostSession.sftp())
        val renameDone = CountDownLatch(1)
        val deleteDone = CountDownLatch(1)

        try {
            controller.makeDirectory("/data") { assertSettles(it) }
            controller.makeDirectory("/data/nested") { assertSettles(it) }
            controller.list("/") { result ->
                val files = result.getOrThrow()
                assertTrue(files.any { it.name == "data" && it.isDirectory })
            }

            val localUpload = temporaryDirectory.resolve("upload.txt")
            Files.writeString(localUpload, "upload content", StandardCharsets.UTF_8)
            val uploaded = CountDownLatch(1)
            controller.setListener { job ->
                if (job.status == TransferStatus.COMPLETED) uploaded.countDown()
            }
            controller.enqueue(SftpTransferRequest("/data/upload.txt", localUpload, TransferDirection.UPLOAD))
            assertTrue(uploaded.await(10, TimeUnit.SECONDS), "upload did not complete")
            assertEquals("upload content", Files.readString(remoteRoot.resolve("data/upload.txt")))

            val localDownload = temporaryDirectory.resolve("download.txt")
            val downloaded = CountDownLatch(1)
            controller.setListener { job ->
                if (job.status == TransferStatus.COMPLETED) downloaded.countDown()
            }
            controller.enqueue(SftpTransferRequest("/data/upload.txt", localDownload, TransferDirection.DOWNLOAD))
            assertTrue(downloaded.await(10, TimeUnit.SECONDS), "download did not complete")
            assertEquals("upload content", Files.readString(localDownload))

            controller.rename("/data/upload.txt", "/data/renamed.txt") { result ->
                assertSettles(result)
                renameDone.countDown()
            }
            assertTrue(renameDone.await(10, TimeUnit.SECONDS), "rename did not settle")
            assertTrue(Files.exists(remoteRoot.resolve("data/renamed.txt")))

            controller.list("/data") { result ->
                val files = result.getOrThrow()
                assertTrue(files.any { it.name == "renamed.txt" })
                assertNotNull(files.firstOrNull { it.name == "renamed.txt" })
            }

            controller.delete("/data/renamed.txt") { result ->
                assertSettles(result)
                deleteDone.countDown()
            }
            assertTrue(deleteDone.await(10, TimeUnit.SECONDS), "delete did not settle")
            assertTrue(!Files.exists(remoteRoot.resolve("data/renamed.txt")))
        } finally {
            controller.close()
            hostSession.close()
        }
    }

    private fun assertSettles(result: Result<Unit>) {
        result.getOrThrow()
    }

    companion object {
        private const val USERNAME = "test-user"
    }
}
