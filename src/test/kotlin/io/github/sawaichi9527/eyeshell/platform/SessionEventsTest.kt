package io.github.sawaichi9527.eyeshell.platform

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The event ring is what turns a dump from "what the terminal looks like now" into "what happened,
 * in which order" — the state that caused a freeze is gone by the time the user writes the dump.
 */
class SessionEventsTest {
    @BeforeEach
    fun reset() = SessionEvents.clear()

    @AfterEach
    fun cleanUp() = SessionEvents.clear()

    @Test
    fun `entries keep the order they happened in`() {
        SessionEvents.record("attach", "session" to "ops@10.0.0.5:22")
        SessionEvents.record("session-end", "session" to "ops@10.0.0.5:22", "reported" to "none")

        val events = SessionEvents.snapshot()

        assertEquals(2, events.size)
        assertTrue(events[0].contains("ev=attach session=ops@10.0.0.5:22"), events[0])
        assertTrue(events[1].contains("reported=none"), events[1])
        assertTrue(events[0] < events[1], "the timestamps must be ordered")
    }

    @Test
    fun `a value never breaks the one-line format`() {
        SessionEvents.record("attach", "profile" to "My Work Host", "note" to "line\nbreak")

        val line = SessionEvents.snapshot().single()

        assertTrue(line.contains("profile=My_Work_Host"), line)
        assertTrue(line.contains("note=line_break"), line)
        assertFalse(line.contains("\n"), line)
    }

    @Test
    fun `an unknown value is omitted instead of printed`() {
        SessionEvents.record("attach", "profile" to null, "session" to "a@b:22")

        val line = SessionEvents.snapshot().single()

        assertFalse(line.contains("profile="), line)
        assertTrue(line.contains("session=a@b:22"), line)
    }

    @Test
    fun `the ring keeps only the newest entries`() {
        repeat(SessionEvents.CAPACITY + 10) { index -> SessionEvents.record("e$index") }

        val events = SessionEvents.snapshot()

        assertEquals(SessionEvents.CAPACITY, events.size)
        assertTrue(events.first().contains("ev=e10"), events.first())
        assertTrue(events.last().contains("ev=e${SessionEvents.CAPACITY + 9}"), events.last())
    }
}
