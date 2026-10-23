package io.github.sawaichi9527.eyeshell.sftp

import java.nio.file.Path

interface SftpClient : AutoCloseable {
    fun list(path: String): List<RemoteFile>

    fun stat(path: String): RemoteFile?

    fun makeDirectory(path: String)

    fun rename(from: String, to: String)

    fun delete(path: String)

    fun download(
        remotePath: String,
        localFile: Path,
        overwrite: Boolean,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    )

    fun upload(
        localFile: Path,
        remotePath: String,
        overwrite: Boolean,
        onProgress: (transferredBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    )

    override fun close()
}
