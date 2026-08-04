package io.github.sawaichi9527.eyeshell.sftp

data class RemoteFile(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val permissions: String,
    val owner: String?,
    val group: String?,
)

data class SftpTransferRequest(
    val remotePath: String,
    val localFile: java.nio.file.Path,
    val direction: TransferDirection,
    val overwrite: Boolean = false,
)

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class TransferStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TransferJob(
    val id: Long,
    val request: SftpTransferRequest,
    val status: TransferStatus,
    val progressPercent: Int,
    val failure: Throwable? = null,
)
