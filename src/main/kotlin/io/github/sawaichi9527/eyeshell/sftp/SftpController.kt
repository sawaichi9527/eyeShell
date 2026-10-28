package io.github.sawaichi9527.eyeshell.sftp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val PROGRESS_INTERVAL_NANOS = 50_000_000L

class SftpController(
    private val sftpClient: SftpClient,
    private val maxRetries: Int = 2,
    private val ownerNames: OwnerNameResolver? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val nextJobId = AtomicLong(1)
    private val jobs = linkedMapOf<Long, TransferJob>()
    private var listeners: List<(TransferJob) -> Unit> = emptyList()

    fun list(path: String, onResult: (Result<List<RemoteFile>>) -> Unit) {
        requireOpen()
        executor.submit {
            val result = runCatching {
                val files = sftpClient.list(path)
                ownerNames?.decorate(files) ?: files
            }
            onResult(result)
        }
    }

    /** Asks the server for the user's home directory so the browser does not have to start at "/". */
    fun homeDirectory(onResult: (String?) -> Unit) {
        requireOpen()
        executor.submit {
            onResult(runCatching { sftpClient.homeDirectory() }.getOrNull())
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

    /**
     * Removes completed, failed, and cancelled jobs from the history. Queued and running
     * transfers are kept so an in-flight transfer never loses its progress row.
     */
    fun clearFinishedJobs(): Int = synchronized(jobs) {
        val finished = jobs.values.filter(::isFinished)
        jobs.values.removeAll(::isFinished)
        finished.size
    }

    private fun isFinished(job: TransferJob): Boolean = when (job.status) {
        TransferStatus.COMPLETED, TransferStatus.FAILED, TransferStatus.CANCELLED -> true
        TransferStatus.QUEUED, TransferStatus.RUNNING -> false
    }

    fun setListener(listener: (TransferJob) -> Unit) {
        listeners = listOf(listener)
    }

    private fun runTransfer(initialJob: TransferJob) {
        var job = initialJob.copy(status = TransferStatus.RUNNING, progressPercent = 0)
        update(job)
        var lastFailure: Throwable? = null
        for (attempt in 0..maxRetries) {
            if (closed.get()) {
                update(job.copy(status = TransferStatus.CANCELLED))
                return
            }
            job = job.copy(progressPercent = 0)
            val onProgress = progressReporter { percent ->
                job = job.copy(progressPercent = percent)
                update(job)
            }
            val result = runCatching {
                when (job.request.direction) {
                    TransferDirection.DOWNLOAD -> sftpClient.download(
                        job.request.remotePath,
                        job.request.localFile,
                        overwrite = job.request.overwrite || attempt > 0,
                        onProgress = onProgress,
                    )
                    TransferDirection.UPLOAD -> sftpClient.upload(
                        job.request.localFile,
                        job.request.remotePath,
                        overwrite = job.request.overwrite || attempt > 0,
                        onProgress = onProgress,
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

    private fun progressReporter(onPercent: (Int) -> Unit): (Long, Long) -> Unit {
        val lastPercent = AtomicLong(-1)
        val lastPublishedAt = AtomicLong(0)
        return { transferred, total ->
            val percent = if (total > 0) {
                ((transferred.coerceAtMost(total) * 100) / total).toInt().coerceIn(0, 100)
            } else {
                0
            }
            val now = System.nanoTime()
            if (percent > lastPercent.get() &&
                (percent == 100 || now - lastPublishedAt.get() >= PROGRESS_INTERVAL_NANOS)
            ) {
                lastPercent.set(percent.toLong())
                lastPublishedAt.set(now)
                onPercent(percent)
            }
        }
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
