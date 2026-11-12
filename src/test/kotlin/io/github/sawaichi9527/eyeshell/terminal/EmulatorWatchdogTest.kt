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

class RepaintWatchdogTest {    @Test
    fun `output consumed without a paint is reported`() {
        val consumed = java.util.concurrent.atomic.AtomicLong(100)
        val reports = AtomicInteger()
        val watchdog = RepaintWatchdog(
            sample = { RepaintSample(consumed.getAndAdd(10), paints = 7) },
            intervalMillis = 10,
            onStuck = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (reports.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(reports.get() > 0, "a stale screen must be reported")
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun `painting while consuming is healthy`() {
        val counter = java.util.concurrent.atomic.AtomicLong(0)
        val reports = AtomicInteger()
        val watchdog = RepaintWatchdog(
            sample = {
                val step = counter.incrementAndGet()
                RepaintSample(consumedCharacters = step * 10, paints = step)
            },
            intervalMillis = 10,
            onStuck = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            Thread.sleep(150)
            assertEquals(0, reports.get(), "a repainting terminal is never reported")
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun `a silent terminal without output is not reported`() {
        val reports = AtomicInteger()
        val watchdog = RepaintWatchdog(
            sample = { RepaintSample(consumedCharacters = 42, paints = 3) },
            intervalMillis = 10,
            onStuck = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            Thread.sleep(150)
            assertEquals(0, reports.get(), "no output means no repaint is expected")
        } finally {
            watchdog.stop()
        }
    }
}

class FlowControlWatchdogTest {
    private fun flow(local: Long, remote: Long) = TerminalFlowControl(
        localWindow = local,
        localWindowMax = 2_097_152,
        remoteWindow = remote,
        remoteWindowMax = 2_097_152,
    )

    @Test
    fun `an exhausted window is reported once`() {
        val reports = AtomicInteger()
        val watchdog = FlowControlWatchdog(
            sample = { flow(local = 0, remote = 1_000_000) },
            intervalMillis = 10,
            requiredSamples = 2,
            onStuck = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (reports.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            Thread.sleep(80)
            assertEquals(1, reports.get(), "one report per exhausted episode")
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun `healthy windows are never reported`() {
        val reports = AtomicInteger()
        val watchdog = FlowControlWatchdog(
            sample = { flow(local = 2_097_152, remote = 2_097_152) },
            intervalMillis = 10,
            requiredSamples = 2,
            onStuck = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            Thread.sleep(120)
            assertEquals(0, reports.get())
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun `a missing transport is ignored`() {
        assertEquals(false, FlowControlWatchdog.isExhausted(flow(local = 1, remote = 1)))
        assertEquals(true, FlowControlWatchdog.isExhausted(flow(local = 0, remote = 1)))
        assertEquals(true, FlowControlWatchdog.isExhausted(flow(local = 1, remote = 0)))
    }
}

class ScrollRegionWatchdogTest {
    @Test
    fun `a shortened region is reported once`() {
        val reports = AtomicInteger()
        val watchdog = ScrollRegionWatchdog(
            sample = { ScrollRegionSample(top = 1, bottom = 1, height = 10, alternateScreen = false) },
            intervalMillis = 10,
            requiredSamples = 2,
            onShortened = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (reports.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            Thread.sleep(80)
            assertEquals(1, reports.get(), "one report per episode")
        } finally {
            watchdog.stop()
        }
    }

    @Test
    fun `a full region and the alternate screen are healthy`() {
        assertEquals(false, ScrollRegionSample(1, 10, 10, false).isShortened)
        assertEquals(false, ScrollRegionSample(1, 3, 10, alternateScreen = true).isShortened)
        assertEquals(true, ScrollRegionSample(1, 1, 10, false).isShortened)
        assertEquals(true, ScrollRegionSample(3, 10, 10, false).isShortened)
    }

    @Test
    fun `a full region is never reported`() {
        val reports = AtomicInteger()
        val watchdog = ScrollRegionWatchdog(
            sample = { ScrollRegionSample(1, 10, 10, false) },
            intervalMillis = 10,
            requiredSamples = 2,
            onShortened = { reports.incrementAndGet() },
        )
        watchdog.start()
        try {
            Thread.sleep(120)
            assertEquals(0, reports.get())
        } finally {
            watchdog.stop()
        }
    }
}
