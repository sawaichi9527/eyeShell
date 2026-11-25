package io.github.sawaichi9527.eyeshell.terminal

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The writer thread is blocked inside the transport write, so it cannot report its own stall. This
 * watchdog samples the queue from outside and is what produces a dump while typing "does nothing".
 */
class InputStallWatchdogTest {
    @Test
    fun `queued input is reported once per episode`() {
        val stalls = AtomicInteger()
        val pending = AtomicInteger(0)
        val watchdog = InputStallWatchdog(
            stats = { InputStats(pending.get(), 1, 0, 0) },
            interval = Duration.ofMillis(20),
            confirmations = 2,
            onStall = { stalls.incrementAndGet() },
        )

        watchdog.start()
        try {
            pending.set(42)
            assertTrue(awaitUntil { stalls.get() == 1 }, "the stall must be reported")
            Thread.sleep(150)
            assertEquals(1, stalls.get(), "a single episode must not repeat")

            pending.set(0)
            Thread.sleep(100)
            pending.set(7)
            assertTrue(awaitUntil { stalls.get() == 2 }, "a new episode must be reported")
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun `an idle queue is never reported`() {
        val stalls = AtomicInteger()
        val watchdog = InputStallWatchdog(
            stats = { InputStats(0, 0, 0, 0) },
            interval = Duration.ofMillis(10),
            confirmations = 2,
            onStall = { stalls.incrementAndGet() },
        )

        watchdog.start()
        Thread.sleep(150)
        watchdog.stop()

        assertEquals(0, stalls.get())
    }

    @Test
    fun `a session that cannot report input ends the watchdog`() {
        val stalls = AtomicInteger()
        val watchdog = InputStallWatchdog(
            stats = { null },
            interval = Duration.ofMillis(10),
            confirmations = 1,
            onStall = { stalls.incrementAndGet() },
        )

        watchdog.start()
        Thread.sleep(100)
        watchdog.stop()

        assertEquals(0, stalls.get())
    }

    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }
}
