package io.github.sawaichi9527.eyeshell.terminal

/**
 * Bounded byte ring buffer of the raw output a session received.
 *
 * The terminal decodes bytes once, so text already written into the buffer cannot be recovered from
 * characters. Keeping the raw bytes lets a character-encoding change be applied to the retained
 * output (see [TerminalSession.rawOutput]) at the cost of a bounded amount of memory per session.
 *
 * When more than [capacity] bytes are appended, only the newest bytes are kept and [isTruncated]
 * reports that the oldest output is gone.
 */
class RawOutputLog(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0) { "Raw output capacity must be positive" }
    }

    private val buffer = ByteArray(capacity)
    private var start = 0
    private var size = 0

    @Synchronized
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Invalid append range" }
        if (length == 0) return
        if (length >= capacity) {
            // Keep only the newest bytes of an oversized chunk.
            val from = offset + length - capacity
            bytes.copyInto(buffer, 0, from, from + capacity)
            start = 0
            size = capacity
            return
        }
        val overflow = size + length - capacity
        if (overflow > 0) {
            start = (start + overflow) % capacity
            size -= overflow
        }
        val writeAt = (start + size) % capacity
        val firstChunk = minOf(length, capacity - writeAt)
        bytes.copyInto(buffer, writeAt, offset, offset + firstChunk)
        if (firstChunk < length) {
            bytes.copyInto(buffer, 0, offset + firstChunk, offset + length)
        }
        size += length
    }

    /** Number of retained bytes. */
    @Synchronized
    fun size(): Int = size

    /** True when older bytes were dropped because the capacity was exceeded. */
    @Synchronized
    fun isTruncated(): Boolean = size == capacity

    /**
     * Copy of the retained bytes in arrival order.
     *
     * [dropTail] removes the newest bytes, which the caller uses to exclude output that is still
     * sitting undecoded in the session: those bytes will be decoded live, so replaying them too
     * would duplicate them.
     */
    @Synchronized
    fun snapshot(dropTail: Int = 0): ByteArray {
        require(dropTail >= 0) { "dropTail must not be negative" }
        val retained = (size - dropTail).coerceAtLeast(0)
        if (retained == 0) return ByteArray(0)
        val result = ByteArray(retained)
        val firstChunk = minOf(retained, capacity - start)
        buffer.copyInto(result, 0, start, start + firstChunk)
        if (firstChunk < retained) {
            buffer.copyInto(result, firstChunk, 0, retained - firstChunk)
        }
        return result
    }

    @Synchronized
    fun clear() {
        start = 0
        size = 0
    }

    companion object {
        /** 8 MiB keeps roughly 100k typical log lines while staying small per open session. */
        const val DEFAULT_CAPACITY = 8 * 1024 * 1024
    }
}
