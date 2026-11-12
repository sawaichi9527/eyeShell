package io.github.sawaichi9527.eyeshell.terminal

/**
 * Watches a live terminal session for the "connected but silent" case: the SSH channel is open and
 * the emulator keeps polling, yet not a single character arrives. That is what a user sees when a
 * terminal tab stops updating for no visible reason, so it is reported once per episode with the
 * time spent waiting instead of being invisible until the next unrelated event.
 *
 * [clock] is injectable so the behaviour is covered without waiting a real minute.
 */
internal class TerminalReadStall(
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastProgressMillis = clock()
    private var reported = false

    /** Records that characters arrived, which also re-arms the report. */
    fun progress() {
        lastProgressMillis = clock()
        reported = false
    }

    /** True exactly once per silent episode, and only after [thresholdMillis] without progress. */
    fun shouldReport(): Boolean {
        if (reported || silenceMillis() < thresholdMillis) return false
        reported = true
        return true
    }

    fun silenceMillis(): Long = clock() - lastProgressMillis

    internal companion object {
        const val DEFAULT_THRESHOLD_MILLIS = 60_000L
    }
}
