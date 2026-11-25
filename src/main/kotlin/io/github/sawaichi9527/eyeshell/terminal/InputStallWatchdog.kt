package io.github.sawaichi9527.eyeshell.terminal

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the input path from outside, the same way [EmulatorWatchdog] watches the emulator.
 *
 * The writer thread cannot report its own stall while it is blocked inside
 * `ChannelOutputStream.write` waiting for remote window space. A tab that ignores typing therefore
 * looks healthy in every snapshot taken afterwards. Sampling [InputStats.pendingBytes] from a
 * separate thread detects "bytes are queued and not draining" and lets the owner capture a thread
 * dump while the writer is still stuck.
 */
internal class InputStallWatchdog(
    private val stats: () -> InputStats?,
    private val interval: Duration = Duration.ofSeconds(1),
    /** Consecutive samples with queued input before the stall is reported. */
    private val confirmations: Int = 2,
    private val onStall: (InputStats) -> Unit,
) {
    private val stopped = AtomicBoolean(true)
    private var thread: Thread? = null

    fun start() {
        if (!stopped.compareAndSet(true, false)) return
        thread = Thread.ofPlatform()
            .daemon(true)
            .name("input-stall-watchdog")
            .start(::loop)
    }

    fun stop() {
        stopped.set(true)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var blockedSamples = 0
        var reported = false
        while (!stopped.get()) {
            try {
                Thread.sleep(interval.toMillis())
            } catch (_: InterruptedException) {
                return
            }
            val current = stats() ?: return
            if (current.pendingBytes > 0) {
                blockedSamples += 1
                if (!reported && blockedSamples >= confirmations) {
                    reported = true
                    onStall(current)
                }
            } else {
                // The queue drained: the next episode may be reported again.
                blockedSamples = 0
                reported = false
            }
        }
    }
}
