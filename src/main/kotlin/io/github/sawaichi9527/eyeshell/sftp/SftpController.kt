package io.github.sawaichi9527.eyeshell.sftp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SftpController(
    private val sftpClient: SftpClient,
    private val maxRetries: Int = 2,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val nextJobId = AtomicLong(1)
    private val jobs = linkedMapOf<Long, TransferJob>()
    private var listeners: List<(TransferJob) -> Unit> = emptyList()

    fun list(path: String, onResult: (Result<List<RemoteFile>>) -> Unit) {
        requireOpen()
        executor.submit {
            val result = runCatching { sftpClient.list(path) }
            onResult(result)
        }
    }

    fun stat(path: String, onResult: (Result<RemoteFile?>) -> Unit) {
        requireOpen()
        executor.submit {
            val result = runCatching { sftpClient.stat(path) }
            onResult(result)
        }
    }

    fun makeDirectory(path: String, onResult: (Result<Unit>) -> Unit) {
        requireOpen()
        executor.submit {
            val result = runCatching { sftpClient.makeDirectory(path) }
            onResult(result)
        }
    }

    fun rename(from: String, to: String, onResult: (Result<Unit>) -> Unit) {
        requireOpen()
        executor.submit {
            val result = runCatching { sftpClient.rename(from, to) }
            onResult(result)
        }
    }

    fun delete(path: String, onResult: (Result<Unit>) -> Unit) {
        requireOpen()
        executor.submit {
            val result = runCatching { sftpClient.delete(path) }
            onResult(result)
        }
    }

    fun enqueue(request: SftpTransferRequest): Long {
        requireOpen()
        if (Files.exists(request.localFile) && !request.overwrite && request.direction == TransferDirection.DOWNLOAD) {
            throw IllegalArgumentException("Local file already exists: ${request.localFile}")
        }
        val id = nextJobId.getAndIncrement()
        val job = TransferJob(id, request, TransferStatus.QUEUED, 0)
        synchronized(jobs) { jobs[id] = job }
        publish(job)
        executor.submit { runTransfer(job) }
        return id
    }

    fun jobsSnapshot(): List<TransferJob> = synchronized(jobs) { jobs.values.toList() }

    fun setListener(listener: (TransferJob) -> Unit) {
        listeners = listOf(listener)
    }

    private fun runTransfer(job: TransferJob) {
        update(job.copy(status = TransferStatus.RUNNING))
        var lastFailure: Throwable? = null
        for (attempt in 0..maxRetries) {
            if (closed.get()) {
                update(job.copy(status = TransferStatus.CANCELLED))
                return
            }
            val result = runCatching {
                when (job.request.direction) {
                    TransferDirection.DOWNLOAD -> sftpClient.download(
                        job.request.remotePath,
                        job.request.localFile,
                        overwrite = job.request.overwrite || attempt > 0,
                    )
                    TransferDirection.UPLOAD -> sftpClient.upload(
                        job.request.localFile,
                        job.request.remotePath,
                        overwrite = job.request.overwrite || attempt > 0,
                    )
                }
            }
            if (result.isSuccess) {
                update(job.copy(status = TransferStatus.COMPLETED, progressPercent = 100))
                return
            }
            lastFailure = result.exceptionOrNull()
        }
        update(job.copy(status = TransferStatus.FAILED, failure = lastFailure))
    }

    private fun update(job: TransferJob) {
        synchronized(jobs) { jobs[job.id] = job }
        publish(job)
    }

    private fun publish(job: TransferJob) {
        listeners.forEach { it(job) }
    }

    private fun requireOpen() {
        check(!closed.get()) { "SFTP controller is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        sftpClient.close()
    }
}
