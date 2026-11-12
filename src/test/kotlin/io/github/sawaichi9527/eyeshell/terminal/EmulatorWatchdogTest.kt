package io.github.sawaichi9527.eyeshell.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmulatorWatchdogTest {
    @Test
    fun `a blocked emulator thread is reported exactly once`() {
        val release = CountDownLatch(1)
        val blocked = Thread {
            runCatching { release.await() }
        }.apply {
            name = "TerminalEmulator-test"
            isDaemon = true
            start()
        }
        awaitState(blocked, Thread.State.WAITING)

        val reports = AtomicInteger()
        val watchdog = EmulatorWatchdog(
            thread = { blocked },
            intervalMillis = 10,
            detector = EmulatorStallDetector(requiredSamples = 2),
            onStall = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (reports.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            Thread.sleep(100)
            assertEquals(1, reports.get(), "one report per stalled episode")
        } finally {
            watchdog.stop()
            release.countDown()
            blocked.join(1_000)
        }
    }

    @Test
    fun `a thread that ends is not reported`() {
        val done = Thread { }.apply {
            name = "TerminalEmulator-finished"
            start()
            join(1_000)
        }
        val reports = AtomicInteger()
        val watchdog = EmulatorWatchdog(
            thread = { done },
            intervalMillis = 10,
            detector = EmulatorStallDetector(requiredSamples = 2),
            onStall = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            Thread.sleep(150)
            assertEquals(0, reports.get(), "a finished emulator is not a stall")
        } finally {
            watchdog.stop()
        }
    }

    private fun awaitState(thread: Thread, state: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != state && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(thread.state == state, "expected $state but was ${thread.state}")
    }
}
