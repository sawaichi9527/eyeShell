package io.github.sawaichi9527.eyeshell.ssh

import io.github.sawaichi9527.eyeshell.sftp.RemoteFile
import io.github.sawaichi9527.eyeshell.sftp.SftpClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.apache.sshd.sftp.client.SftpClientFactory

internal class MinaSftpClient(
    clientSession: org.apache.sshd.client.session.ClientSession,
) : SftpClient {
    private val client: org.apache.sshd.sftp.client.SftpClient = SftpClientFactory.instance().createSftpClient(clientSession)

    override fun list(path: String): List<RemoteFile> {
        client.openDir(path).use { handle ->
            return client.readDir(handle).mapNotNull { entry ->
                val filename = entry.filename ?: return@mapNotNull null
                if (filename == "." || filename == "..") return@mapNotNull null
                val attributes = entry.attributes
                RemoteFile(
                    name = filename,
                    isDirectory = attributes.isDirectory,
                    sizeBytes = attributes.size,
                    permissions = permissionsString(attributes),
                    owner = ownerLabel(attributes),
                    group = groupLabel(attributes),
                    modifiedTime = attributes.modifyTime?.toInstant(),
                )
            }
        }
    }

    override fun homeDirectory(): String? = try {
        client.canonicalPath(".")
    } catch (_: IOException) {
        null
    }

    override fun stat(path: String): RemoteFile? = try {
        val attributes = client.stat(path)
        RemoteFile(
            name = path.substringAfterLast('/').ifEmpty { path },
            isDirectory = attributes.isDirectory,
            sizeBytes = attributes.size,
            permissions = permissionsString(attributes),
            owner = ownerLabel(attributes),
            group = groupLabel(attributes),
            modifiedTime = attributes.modifyTime?.toInstant(),
        )
    } catch (failure: IOException) {
        if (isNotFound(failure)) null else throw failure
    }

    override fun makeDirectory(path: String) = client.mkdir(path)

    override fun rename(from: String, to: String) = client.rename(from, to)

    override fun delete(path: String) = client.remove(path)

    override fun download(
        remotePath: String,
        localFile: Path,
        overwrite: Boolean,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit,
    ) {
        if (Files.exists(localFile) && !overwrite) {
            throw IOException("Local file already exists: $localFile")
        }
        val total = stat(remotePath)?.sizeBytes ?: 0L
        val temporary = localFile.resolveSibling(".${localFile.fileName}.part")
        Files.deleteIfExists(temporary)
        try {
            client.read(remotePath).use { input ->
                Files.newOutputStream(temporary).use { output -> copyWithProgress(input, output, total, onProgress) }
            }
            moveIntoPlace(temporary, localFile)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun upload(
        localFile: Path,
        remotePath: String,
        overwrite: Boolean,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val targetExists = stat(remotePath) != null
        if (targetExists && !overwrite) {
            throw IOException("Remote file already exists: $remotePath")
        }
        val total = Files.size(localFile)
        val temporary = remotePath.substringBeforeLast('/', "").let { directory ->
            val name = remotePath.substringAfterLast('/')
            val part = ".$name.part"
            if (directory.isEmpty()) part else "$directory/$part"
        }
        try {
            client.write(temporary).use { output ->
                Files.newInputStream(localFile).use { input -> copyWithProgress(input, output, total, onProgress) }
            }
            client.rename(temporary, remotePath)
        } finally {
            try {
                client.remove(temporary)
            } catch (_: IOException) {
            }
        }
    }

    override fun close() = client.close()

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalBytes: Long,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var transferred = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            transferred += read
            onProgress(transferred, totalBytes)
        }
        onProgress(transferred, totalBytes)
    }

    private fun moveIntoPlace(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (failure: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    }

    private fun permissionsString(attributes: org.apache.sshd.sftp.client.SftpClient.Attributes): String {
        val permissions = attributes.permissions
        val type = when {
            attributes.isDirectory -> "d"
            attributes.isSymbolicLink -> "l"
            else -> "-"
        }
        val owner = if ((permissions and 256) != 0) "r" else "-"
        val ownerWrite = if ((permissions and 128) != 0) "w" else "-"
        val ownerExec = if ((permissions and 64) != 0) "x" else "-"
        val group = if ((permissions and 32) != 0) "r" else "-"
        val groupWrite = if ((permissions and 16) != 0) "w" else "-"
        val groupExec = if ((permissions and 8) != 0) "x" else "-"
        val other = if ((permissions and 4) != 0) "r" else "-"
        val otherWrite = if ((permissions and 2) != 0) "w" else "-"
        val otherExec = if ((permissions and 1) != 0) "x" else "-"
        return "$type$owner$ownerWrite$ownerExec$group$groupWrite$groupExec$other$otherWrite$otherExec"
    }

    private fun isNotFound(failure: IOException): Boolean =
        failure.message?.contains("No such file", ignoreCase = true) == true
            || failure.message?.contains("not found", ignoreCase = true) == true
}

/**
 * SFTP v3 directory listings carry the owner and group as numeric uid/gid ([SftpClient.Attribute.UidGid]),
 * not as names; only v4+ servers send names ([SftpClient.Attribute.OwnerGroup]). Falling back to the
 * numeric id keeps the column honest — it shows what the server actually sent instead of "—".
 */
internal fun ownerLabel(attributes: org.apache.sshd.sftp.client.SftpClient.Attributes): String? =
    attributes.owner?.takeIf { it.isNotBlank() } ?: numericId(attributes, attributes.userId, org.apache.sshd.sftp.client.SftpClient.Attribute.UidGid)

internal fun groupLabel(attributes: org.apache.sshd.sftp.client.SftpClient.Attributes): String? =
    attributes.group?.takeIf { it.isNotBlank() } ?: numericId(attributes, attributes.groupId, org.apache.sshd.sftp.client.SftpClient.Attribute.UidGid)

private fun numericId(
    attributes: org.apache.sshd.sftp.client.SftpClient.Attributes,
    id: Int,
    flag: org.apache.sshd.sftp.client.SftpClient.Attribute,
): String? = if (attributes.flags.contains(flag) && id >= 0) id.toString() else null
