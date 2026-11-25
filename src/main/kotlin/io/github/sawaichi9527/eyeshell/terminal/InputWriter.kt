package io.github.sawaichi9527.eyeshell.terminal

import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * Hands typed bytes to a transport without blocking the caller.
 *
 * The Swing EDT calls [TerminalSession.write] for every keystroke, and an SSH channel write blocks
 * while the peer's window is exhausted (Apache MINA SSHD waits up to
 * `channel-output-wait-for-space-timeout`, 30 s by default). A remote that stops reading stdin
 * therefore used to freeze the whole window, not just that tab: the symptom is "typing has no
 * effect for a while, then everything catches up".
 *
 * The bytes are queued here and written by one dedicated thread per session, the way desktop
 * terminal emulators keep PTY/SSH I/O off their UI thread. The queue is bounded: keystrokes are
 * human-paced, so the limit is only reachable by a paste into a stalled session, and the oldest
 * bytes are dropped (and logged) rather than blocking the UI.
 *
 * @param sink performs the blocking transport write; it runs on the writer thread only.
 * @param maxPendingBytes upper bound of queued input; oldest chunks are dropped beyond it.
 * @param blockedAfterMillis a single write slower than this is reported through [onBlocked].
 */
internal class InputWriter(
    private val name: String,
    private val sink: (ByteArray) -> Unit,
    private val maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
    private val blockedAfterMillis: Long = DEFAULT_BLOCKED_AFTER_MILLIS,
    private val onDropped: (droppedBytes: Int, pendingBytes: Int) -> Unit = { _, _ -> },
    private val onBlocked: (blockedMillis: Long) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val hasWork = lock.newCondition()
    private val queue = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var writer: Thread? = null
    private val droppedBytes = AtomicLong()
    private val lastBlockedMillis = AtomicLong()

    /**
     * Queues [bytes] for delivery. Never blocks: a caller on the Swing EDT only takes the lock for
     * the enqueue, and the transport write happens on the writer thread.
     */
    fun submit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val chunk = bytes.copyOf()
        var dropped = 0
        var pending = 0
        lock.withLock {
            if (closed) return
            startWriterLocked()
            if (chunk.size > maxPendingBytes) {
                // A single chunk larger than the whole queue: dropping it keeps the session usable
                // instead of flushing everything the user typed after it.
                dropped = chunk.size
                droppedBytes.addAndGet(chunk.size.toLong())
            } else {
                while (pendingBytes + chunk.size > maxPendingBytes && queue.isNotEmpty()) {
                    val oldest = queue.removeFirst()
                    pendingBytes -= oldest.size
                    droppedBytes.addAndGet(oldest.size.toLong())
                    dropped += oldest.size
                }
                queue.addLast(chunk)
                pendingBytes += chunk.size
                hasWork.signalAll()
            }
            pending = pendingBytes
        }
        if (dropped > 0) {
            LOGGER.warning(
                "Dropped $dropped bytes of terminal input on $name: $pending bytes were already " +
                    "waiting for a transport that has not consumed them (limit $maxPendingBytes)",
            )
            onDropped(dropped, pending)
        }
    }

    /** Stops delivery and discards queued bytes. Never joins: this is called from the Swing EDT. */
    fun close() {
        lock.withLock {
            closed = true
            queue.clear()
            pendingBytes = 0
            hasWork.signalAll()
        }
    }

    /** Snapshot for the diagnostics dump. */
    fun stats(): InputStats = lock.withLock {
        InputStats(
            pendingBytes = pendingBytes,
            queuedChunks = queue.size,
            droppedBytes = droppedBytes.get(),
            lastBlockedMillis = lastBlockedMillis.get(),
        )
    }

    /** Waits until everything queued has been handed to [sink]; visible for tests. */
    internal fun awaitDrained(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (pendingBytes > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                hasWork.awaitNanos(remaining)
            }
            return true
        }
    }

    private fun startWriterLocked() {
        if (writer != null) return
        writer = Thread.ofPlatform()
            .daemon(true)
            .name("terminal-input-$name")
            .start(::runWriter)
    }

    private fun runWriter() {
        while (true) {
            val chunk = lock.withLock {
                while (queue.isEmpty() && !closed) {
                    try {
                        hasWork.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        closed = true
                    }
                }
                if (queue.isEmpty()) return
                val next = queue.removeFirst()
                pendingBytes -= next.size
                // Wake awaitDrained and anyone waiting for queue space.
                hasWork.signalAll()
                next
            }
            val startedAt = System.nanoTime()
            try {
                sink(chunk)
            } catch (failure: Throwable) {
                // The channel is closed or broken: stop delivering instead of spinning on a dead
                // transport. The session's own close path reports the end of the session.
                LOGGER.log(Level.FINE, "Terminal input stopped on $name", failure)
                lock.withLock {
                    closed = true
                    queue.clear()
                    pendingBytes = 0
                    hasWork.signalAll()
                }
                return
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsedMillis >= blockedAfterMillis) {
                lastBlockedMillis.set(elapsedMillis)
                onBlocked(elapsedMillis)
            } else if (lastBlockedMillis.get() != 0L) {
                lastBlockedMillis.set(0L)
            }
        }
    }

    private companion object {
        /** Keystrokes are human-paced; this only fills up for a paste into a stalled session. */
        const val DEFAULT_MAX_PENDING_BYTES = 64 * 1024

        /** One keystroke write should be immediate; a second means the transport is not consuming. */
        const val DEFAULT_BLOCKED_AFTER_MILLIS = 1_000L

        val LOGGER: Logger = Logger.getLogger("io.github.sawaichi9527.eyeshell.terminal.input")
    }
}
