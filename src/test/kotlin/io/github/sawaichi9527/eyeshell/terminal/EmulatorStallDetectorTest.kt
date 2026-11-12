package io.github.sawaichi9527.eyeshell.terminal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
            assertFalse(detector.sample(Thread.State.TIMED_WAITING, idle), "an idle terminal is healthy")
        }
    }

    @Test
    fun `a repeatedly blocked stack is reported exactly once`() {
        val detector = EmulatorStallDetector(requiredSamples = 3)

        assertFalse(detector.sample(Thread.State.WAITING, blocked))
        assertFalse(detector.sample(Thread.State.WAITING, blocked))
        assertTrue(detector.sample(Thread.State.WAITING, blocked), "the third identical sample reports")
        assertFalse(detector.sample(Thread.State.WAITING, blocked), "one report per episode")
    }

    @Test
    fun `a busy or changing emulator is not reported`() {
        val detector = EmulatorStallDetector(requiredSamples = 2)

        repeat(5) { detector.sample(Thread.State.RUNNABLE, blocked) }

        assertFalse(detector.sample(Thread.State.BLOCKED, blocked))
        val other = blocked + "com.jediterm.terminal.model.TerminalTextBuffer.lock"
        assertFalse(detector.sample(Thread.State.BLOCKED, other), "a moving stack is progress")
    }

    @Test
    fun `progress between samples re-arms the detector`() {
        val detector = EmulatorStallDetector(requiredSamples = 2)

        detector.sample(Thread.State.WAITING, blocked)
        detector.sample(Thread.State.TIMED_WAITING, idle)
        assertFalse(detector.sample(Thread.State.WAITING, blocked), "the idle sample cleared the episode")
        assertTrue(detector.sample(Thread.State.WAITING, blocked))
    }
}
