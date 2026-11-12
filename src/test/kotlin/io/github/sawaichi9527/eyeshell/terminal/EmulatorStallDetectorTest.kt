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
        val detector = EmulatorStallDetector(requiredSamples = 2)

        repeat(10) {
            assertEquals(StallSample.NONE, detector.sample(Thread.State.TIMED_WAITING, idle), "an idle terminal is healthy")
        }
    }

    @Test
    fun `a repeatedly blocked stack is suspected first and then confirmed once`() {
        val detector = EmulatorStallDetector(requiredSamples = 3)

        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.WAITING, blocked))
        assertEquals(StallSample.NONE, detector.sample(Thread.State.WAITING, blocked))
        assertEquals(StallSample.STALLED, detector.sample(Thread.State.WAITING, blocked), "the third identical sample confirms")
        assertEquals(StallSample.NONE, detector.sample(Thread.State.WAITING, blocked), "one report per episode")
    }

    @Test
    fun `a busy or changing emulator is not reported`() {
        val detector = EmulatorStallDetector(requiredSamples = 2)

        repeat(5) { assertEquals(StallSample.NONE, detector.sample(Thread.State.RUNNABLE, blocked)) }

        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.BLOCKED, blocked))
        val other = blocked + "com.jediterm.terminal.model.TerminalTextBuffer.lock"
        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.BLOCKED, other), "a new stack restarts the episode")
    }

    @Test
    fun `progress between samples re-arms the detector`() {
        val detector = EmulatorStallDetector(requiredSamples = 2)

        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.WAITING, blocked))
        assertEquals(StallSample.NONE, detector.sample(Thread.State.TIMED_WAITING, idle))
        assertEquals(StallSample.SUSPECTED, detector.sample(Thread.State.WAITING, blocked), "the idle sample cleared the episode")
        assertEquals(StallSample.STALLED, detector.sample(Thread.State.WAITING, blocked))
    }
}
