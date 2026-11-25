package io.github.sawaichi9527.eyeshell.platform

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The trail of events that led to a problem, kept in a fixed-size ring so the diagnostics dump can
 * answer "what happened, in which order" instead of only "what does it look like now".
 *
 * A dump taken after a freeze shows a healthy terminal: the state that caused it is already gone.
 * These entries are the missing half — attach, reconnect, end of session, emulator stop, repaired
 * scroll margins, input that had to wait — each with a timestamp, so two incidents can be correlated
 * with the log without guessing.
 *
 * Only metadata is recorded: session names, counters, and outcomes. Never terminal output, never a
 * password, never a file path.
 */
object SessionEvents {
    /** Enough for a few hours of one session; the dump stays small enough to attach to a report. */
    const val CAPACITY = 256

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val lock = Any()
    private val entries = ArrayDeque<String>(CAPACITY)

    /**
     * Records one event. [fields] become `name=value` pairs; a `null` value is omitted, because an
     * absent fact reads better than `unknown`.
     */
    fun record(event: String, vararg fields: Pair<String, Any?>) {
        val line = buildString {
            append(TIMESTAMP.format(OffsetDateTime.now()))
            append(" ev=").append(sanitize(event))
            fields.forEach { (name, value) ->
                if (value != null) append(' ').append(sanitize(name)).append('=').append(sanitize(value.toString()))
            }
        }
        synchronized(lock) {
            if (entries.size == CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
    }

    /** Oldest first, so the dump reads as a timeline. */
    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** Entries are one per line, so a value must never contain whitespace. */
    private fun sanitize(text: String): String =
        text.map { if (it.isWhitespace()) '_' else it }.joinToString("")
}
