package io.github.sawaichi9527.eyeshell.terminal

import java.util.logging.Level
import java.util.logging.Logger

/** Outcome of one watchdog sample. */
internal enum class StallSample {
    /** Healthy: idle poll loop, a runnable thread, or fewer repeated samples than the threshold. */
    NONE,

    /** A non-idle blocked stack repeated for the first time; worth recording even if it recovers. */
    SUSPECTED,

    /** The threshold was reached: treat the terminal as failed. */
    STALLED,
}

/**
 * Decides whether the terminal emulator thread has stopped making progress.
 *
 * The normal quiet state is a short poll loop that sleeps between `ready()` checks, so a repeated
 * `Thread.sleep` under `SessionTtyConnector.readLoop` is **not** a stall. A thread that repeats any
 * other stack while blocked or waiting is: that is a reader stuck on a lock, a channel read, or a
 * synchronized-output block, and the tab will never update again.
 *
 * A runnable thread is never reported, because a busy emulator is still making progress (even a long
 * parse shows a moving stack only at coarse resolution, so treating it as a stall would disconnect
 * healthy sessions).
 *
 * The first repeated observation is reported as [StallSample.SUSPECTED] so a user who closes the tab
 * early still leaves a stack trace in the log; the confirmed [StallSample.STALLED] follows after
 * [requiredSamples] identical samples.
 */
internal class EmulatorStallDetector(
    internal val requiredSamples: Int = 3,
) {
    private var signature: String? = null
    private var repeats = 0
    private var reportState = ReportState.NONE

    private enum class ReportState { NONE, SUSPECTED, STALLED }

    fun sample(state: Thread.State, frames: List<String>): StallSample {
        if (state == Thread.State.RUNNABLE || state == Thread.State.NEW || isIdle(frames)) {
            reset()
            return StallSample.NONE
        }
        val current = frames.joinToString("\n")
        if (current == signature) {
            repeats += 1
        } else {
            signature = current
            repeats = 1
            // A different blocked stack is a new episode worth reporting, but a confirmed stall
            // stays confirmed for this session.
            if (reportState == ReportState.SUSPECTED) reportState = ReportState.NONE
        }
        if (repeats >= requiredSamples && reportState != ReportState.STALLED) {
            reportState = ReportState.STALLED
            return StallSample.STALLED
        }
        if (repeats == 1 && reportState == ReportState.NONE) {
            reportState = ReportState.SUSPECTED
            return StallSample.SUSPECTED
        }
        return StallSample.NONE
    }

    fun reset() {
        signature = null
        repeats = 0
        reportState = ReportState.NONE
    }

    internal companion object {
        private const val SLEEP_FRAME = "java.lang.Thread.sleep"
        private const val READ_LOOP_FRAME = "SessionTtyConnector.readLoop"

        /** The healthy idle state: polling in `readLoop`, asleep between ready() checks. */
        fun isIdle(frames: List<String>): Boolean {
            val top = frames.firstOrNull() ?: return false
            return top.startsWith(SLEEP_FRAME) && frames.any { it.contains(READ_LOOP_FRAME) }
        }
    }
}

/**
 * Samples the emulator thread from outside, so a thread that is blocked cannot be the one that
 * reports its own stall. [thread] returns the emulator thread once the connector has seen it.
 */
internal class EmulatorWatchdog(
    private val thread: () -> Thread?,
    private val intervalMillis: Long = SAMPLE_INTERVAL_MILLIS,
    private val detector: EmulatorStallDetector = EmulatorStallDetector(),
    private val onStall: (List<String>) -> Unit,
) {
    private val running = java.util.concurrent.atomic.AtomicBoolean()
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread.ofVirtual().name("eyeshell-emulator-watchdog").start {
            while (running.get()) {
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    return@start
                }
                val sampled = thread() ?: continue
                if (!sampled.isAlive) {
                    detector.reset()
                    continue
                }
                val frames = sampled.stackTrace.map { "${it.className}.${it.methodName}" }
                when (detector.sample(sampled.state, frames)) {
                    StallSample.SUSPECTED -> logger.log(
                        Level.WARNING,
                        buildString {
                            append("Terminal emulator thread ")
                            append(sampled.name)
                            append(" has not moved for ${intervalMillis / 1000}s (")
                            append(sampled.state)
                            append("); sampling again, stack:")
                            frames.forEach { frame -> append("\n    at ").append(frame) }
                        },
                    )

                    StallSample.STALLED -> {
                        onStall(frames)
                    }

                    StallSample.NONE -> Unit
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 5_000L
        val logger: Logger = Logger.getLogger(EmulatorWatchdog::class.java.name)
    }
}
