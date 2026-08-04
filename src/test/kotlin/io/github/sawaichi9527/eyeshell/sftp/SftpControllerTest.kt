package io.github.sawaichi9527.eyeshell.sftp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SftpControllerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `lists a remote directory on a background thread`() {
        val client = FakeSftpClient(
            entries = listOf(
                RemoteFile("dir", isDirectory = true, sizeBytes = 0, permissions = "drwxr-xr-x", owner = "user", group = "user"),
                RemoteFile("file.txt", isDirectory = false, sizeBytes = 42, permissions = "-rw-r--r--", owner = "user", group = "user"),
            ),
        )
        val controller = SftpController(client)
        val latch = CountDownLatch(1)
        var listed: List<RemoteFile>? = null

        try {
            controller.list("/home/user") { result ->
                listed = result.getOrThrow()
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val result = requireNotNull(listed)
            assertEquals(2, result.size)
            assertTrue(result.any { it.name == "dir" && it.isDirectory })
            assertTrue(result.any { it.name == "file.txt" && it.sizeBytes == 42L })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `downloads a remote file into place via a part file`() {
        val client = FakeSftpClient(remoteFiles = mapOf("/remote/file.txt" to "remote content"))
        val controller = SftpController(client)
        val local = temporaryDirectory.resolve("file.txt")
        val latch = CountDownLatch(1)
        var status: TransferStatus? = null
        controller.setListener { job ->
            status = job.status
            if (job.status == TransferStatus.COMPLETED) latch.countDown()
        }

        try {
            controller.enqueue(SftpTransferRequest("/remote/file.txt", local, TransferDirection.DOWNLOAD))
            assertTrue(latch.await(5, TimeUnit.SECONDS), "download did not complete")
            assertEquals(TransferStatus.COMPLETED, status)
            assertEquals("remote content", Files.readString(local))
            assertFalse(Files.exists(local.resolveSibling(".file.txt.part")))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `rejects a download that would overwrite an existing local file`() {
        val client = FakeSftpClient(remoteFiles = mapOf("/remote/file.txt" to "content"))
        val controller = SftpController(client)
        val local = temporaryDirectory.resolve("file.txt")
        Files.writeString(local, "existing")

        try {
            assertThrows(IllegalArgumentException::class.java) {
                controller.enqueue(SftpTransferRequest("/remote/file.txt", local, TransferDirection.DOWNLOAD))
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `uploads a local file and retries on failure`() {
        val local = temporaryDirectory.resolve("local.txt")
        Files.writeString(local, "upload me")
        val client = FakeSftpClient(failuresRemaining = 1)
        val controller = SftpController(client, maxRetries = 2)
        val latch = CountDownLatch(1)
        var status: TransferStatus? = null
        controller.setListener { job ->
            status = job.status
            if (job.status == TransferStatus.COMPLETED || job.status == TransferStatus.FAILED) latch.countDown()
        }

        try {
            controller.enqueue(SftpTransferRequest("/remote/local.txt", local, TransferDirection.UPLOAD))
            assertTrue(latch.await(5, TimeUnit.SECONDS), "upload did not settle")
            assertEquals(TransferStatus.COMPLETED, status)
            assertEquals("upload me", client.uploaded["/remote/local.txt"])
        } finally {
            controller.close()
        }
    }

    @Test
    fun `marks a transfer failed after retries are exhausted`() {
        val local = temporaryDirectory.resolve("local.txt")
        Files.writeString(local, "upload me")
        val client = FakeSftpClient(failuresRemaining = Int.MAX_VALUE)
        val controller = SftpController(client, maxRetries = 1)
        val latch = CountDownLatch(1)
        var status: TransferStatus? = null
        controller.setListener { job ->
            status = job.status
            if (job.status == TransferStatus.FAILED) latch.countDown()
        }

        try {
            controller.enqueue(SftpTransferRequest("/remote/local.txt", local, TransferDirection.UPLOAD))
            assertTrue(latch.await(5, TimeUnit.SECONDS), "transfer did not fail")
            assertEquals(TransferStatus.FAILED, status)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `executes mkdir rename and delete`() {
        val client = FakeSftpClient()
        val controller = SftpController(client)
        val mkdirLatch = CountDownLatch(1)
        val renameLatch = CountDownLatch(1)
        val deleteLatch = CountDownLatch(1)

        try {
            controller.makeDirectory("/tmp/newdir") { mkdirLatch.countDown() }
            controller.rename("/tmp/a", "/tmp/b") { renameLatch.countDown() }
            controller.delete("/tmp/a") { deleteLatch.countDown() }
            assertTrue(mkdirLatch.await(5, TimeUnit.SECONDS))
            assertTrue(renameLatch.await(5, TimeUnit.SECONDS))
            assertTrue(deleteLatch.await(5, TimeUnit.SECONDS))
            assertTrue(client.mkdirs.contains("/tmp/newdir"))
            assertEquals(listOf("/tmp/a" to "/tmp/b"), client.renames)
            assertEquals(listOf("/tmp/a"), client.deleted)
        } finally {
            controller.close()
        }
    }

    private class FakeSftpClient(
        val entries: List<RemoteFile> = emptyList(),
        val remoteFiles: Map<String, String> = emptyMap(),
        var failuresRemaining: Int = 0,
    ) : SftpClient {
        val mkdirs = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        val uploaded = mutableMapOf<String, String>()
        var closed = false

        override fun list(path: String): List<RemoteFile> = entries

        override fun stat(path: String): RemoteFile? = null

        override fun makeDirectory(path: String) {
            mkdirs += path
        }

        override fun rename(from: String, to: String) {
            renames += from to to
        }

        override fun delete(path: String) {
            deleted += path
        }

        override fun download(remotePath: String, localFile: Path, overwrite: Boolean) {
            val content = remoteFiles[remotePath] ?: throw java.io.IOException("No such file")
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw java.io.IOException("transient download failure")
            }
            Files.writeString(localFile, content)
        }

        override fun upload(localFile: Path, remotePath: String, overwrite: Boolean) {
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw java.io.IOException("transient upload failure")
            }
            uploaded[remotePath] = Files.readString(localFile)
        }

        override fun close() {
            closed = true
        }
    }
}
