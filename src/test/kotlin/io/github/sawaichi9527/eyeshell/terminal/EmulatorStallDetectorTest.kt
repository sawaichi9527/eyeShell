package io.github.sawaichi9527.eyeshell.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmulatorStallDetectorTest {
    private val idle = listOf(
        "java.lang.Thread.sleep",
        "io.github.sawaichi9527.eyeshell.terminal.jediterm.SessionTtyConnector.readLoop",
        "io.github.sawaichi9527.eyeshell.terminal.jediterm.SessionTtyConnector.read",
    )

    private val blocked = listOf(
        "java.lang.Object.wait",
        "org.apache.sshd.client.channel.ChannelInputStream.read",
        "io.github.sawaichi9527.eyeshell.terminal.jediterm.SessionTtyConnector.readLoop",
    )

    @Test
    fun `a sleeping poll loop is never a stall`() {
        val detector = EmulatorStallDetector(requiredSamples = 3)

        repeat(10) {
            assertEquals(
                StallSample.NONE,
                detector.sample(Thread.State.TIMED_WAITING, idle, progress = 0),
                "an idle terminal is healthy",
            )
        }
    }

    /**
     * A thread blocked in a native channel read reports RUNNABLE on Windows, so the state is not
     * used: the detector only trusts the absence of consumed characters on an unchanged stack.
     */
    @Test
    fun `no progress on the same stack is suspected and then confirmed once`() {
        val detector = EmulatorStallDetector(requiredSamples = 3)

        assertEquals(StallSample.NONE, detector.sample(Thread.State.RUNNABLE, blocked, progress = 100), "baseline")
        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.RUNNABLE, blocked, progress = 100))
        assertEquals(StallSample.STALLED, detector.sample(Thread.State.RUNNABLE, blocked, progress = 100))
        assertEquals(StallSample.NONE, detector.sample(Thread.State.RUNNABLE, blocked, progress = 100), "one report")
    }

    @Test
    fun `a runnable thread that keeps consuming is healthy`() {
        val detector = EmulatorStallDetector(requiredSamples = 2)

        var progress = 0L
        repeat(6) {
            assertEquals(
                StallSample.NONE,
                detector.sample(Thread.State.RUNNABLE, blocked, progress = progress),
                "progress means the emulator is working",
            )
            progress += 1_000
        }
    }

    @Test
    fun `progress on an otherwise identical stack re-arms the detector`() {
        val detector = EmulatorStallDetector(requiredSamples = 3)

        assertEquals(StallSample.NONE, detector.sample(Thread.State.WAITING, blocked, progress = 10), "baseline")
        assertEquals(StallSample.NONE, detector.sample(Thread.State.WAITING, blocked, progress = 20), "progress is health")
        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.WAITING, blocked, progress = 20))
        assertEquals(StallSample.STALLED, detector.sample(Thread.State.WAITING, blocked, progress = 20))
    }

    @Test
    fun `a changed stack restarts the repeated-sample count`() {
        val detector = EmulatorStallDetector(requiredSamples = 3)

        assertEquals(StallSample.NONE, detector.sample(Thread.State.BLOCKED, blocked, progress = 5), "baseline")
        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.BLOCKED, blocked, progress = 5))
        val other = blocked + "com.jediterm.terminal.model.TerminalTextBuffer.lock"
        assertEquals(StallSample.NONE, detector.sample(Thread.State.BLOCKED, other, progress = 5), "new stack, new count")
        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.BLOCKED, other, progress = 5))
        assertEquals(StallSample.STALLED, detector.sample(Thread.State.BLOCKED, other, progress = 5))    }
}
