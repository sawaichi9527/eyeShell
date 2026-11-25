package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes a dump by itself when something is wrong, because the interesting state is gone by the
 * time a person presses a button: the same lesson the emulator watchdogs already apply.
 *
 * Throttled so a watchdog loop cannot fill the disk: one dump per (kind, session) every five
 * minutes, and at most [MAX_PER_RUN] per process.
 */
object SessionDumps {
    const val THROTTLE_MILLIS = 5 * 60 * 1000L
    const val MAX_PER_RUN = 20

    private val lastWriteAt = ConcurrentHashMap<String, Long>()
    private val written = AtomicInteger()

    /**
     * Writes `thread-dump-<kind>-<session>-<timestamp>.txt` and records an event that names it.
     * Returns null when the throttle, the per-run cap, or the file write refused.
     */
    fun auto(
        kind: String,
        sessionName: String,
        sessions: List<String>,
        directory: Path = EyeShellPaths.logDirectory(),
    ): Path? {
        val key = "$kind|$sessionName"
        val now = System.currentTimeMillis()
        val previous = lastWriteAt[key]
        if (previous != null && now - previous < THROTTLE_MILLIS) return null
        if (written.get() >= MAX_PER_RUN) return null
        val file = Diagnostics.write(
            directory = directory,
            reason = "$kind-${Diagnostics.sanitize(sessionName)}",
            sessions = sessions,
        ) ?: return null
        lastWriteAt[key] = now
        written.incrementAndGet()
        SessionEvents.record(
            "auto-dump",
            "kind" to kind,
            "session" to sessionName,
            "file" to file.fileName.toString(),
        )
        return file
    }

    /** Visible for tests: the throttle and the cap are process-wide by design. */
    internal fun reset() {
        lastWriteAt.clear()
        written.set(0)
    }
}
