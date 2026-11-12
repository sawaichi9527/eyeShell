package io.github.sawaichi9527.eyeshell.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalReadStallTest {
    @Test
    fun `a quiet session is reported once after the threshold`() {
        var now = 0L
        val stall = TerminalReadStall(thresholdMillis = 60_000, clock = { now })

        now = 59_999
        assertFalse(stall.shouldReport(), "The threshold has not been reached yet")

        now = 60_000
        assertTrue(stall.shouldReport(), "The first poll after the threshold reports")
        assertFalse(stall.shouldReport(), "The same episode is reported only once")
        assertEquals(60_000, stall.silenceMillis())
    }

    @Test
    fun `new output re-arms the report`() {
        var now = 0L
        val stall = TerminalReadStall(thresholdMillis = 1_000, clock = { now })

        now = 1_000
        assertTrue(stall.shouldReport())

        stall.progress()
        now = 1_500
        assertFalse(stall.shouldReport(), "Progress restarts the silence window")
        assertEquals(500, stall.silenceMillis())

        now = 2_500
        assertTrue(stall.shouldReport(), "A second episode is reported again")
    }
}
