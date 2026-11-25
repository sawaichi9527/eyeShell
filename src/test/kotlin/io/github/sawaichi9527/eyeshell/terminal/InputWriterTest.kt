package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A terminal write must never wait for the transport: the caller is the Swing EDT, and an SSH
 * channel write blocks while the peer's window is exhausted (the "typing has no effect, then it
 * catches up" report).
 */
class InputWriterTest {
    @Test
    fun `queued writes are delivered in order`() {
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val writer = InputWriter("test", sink = { bytes -> delivered += String(bytes, StandardCharsets.UTF_8) })

        writer.submit("a".toByteArray())
        writer.submit("b".toByteArray())
        writer.submit("c".toByteArray())

        assertTrue(writer.awaitDrained(Duration.ofSeconds(5)), "the queue must drain")
        assertEquals(listOf("a", "b", "c"), delivered)
        assertEquals(0, writer.stats().pendingBytes)
        writer.close()
    }

    @Test
    fun `a stalled transport never blocks the caller`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val writer = InputWriter(
            "test",
            sink = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
        )

        writer.submit("x".toByteArray())
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the writer thread must start")
        val startedAt = System.nanoTime()
        repeat(20) { writer.submit("keystroke".toByteArray()) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMillis < 500, "submitting took ${elapsedMillis}ms while the sink was stalled")
        assertTrue(writer.stats().pendingBytes > 0, "the bytes must be queued, not written")

        release.countDown()
        assertTrue(writer.awaitDrained(Duration.ofSeconds(5)), "the queue must drain once the sink returns")
        writer.close()
    }

    @Test
    fun `the queue is bounded and drops the oldest bytes`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val dropped = AtomicLong()
        val writer = InputWriter(
            name = "test",
            sink = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
            maxPendingBytes = 8,
            onDropped = { count, _ -> dropped.addAndGet(count.toLong()) },
        )

        writer.submit("AAAA".toByteArray())
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the writer thread must start")
        // The writer holds the first chunk, so everything queued now counts towards the limit.
        writer.submit("BBBB".toByteArray())
        writer.submit("CCCC".toByteArray())
        writer.submit("DDDD".toByteArray())

        val stats = writer.stats()
        assertEquals(8, stats.pendingBytes, "the queue must stay within its limit")
        assertEquals(2, stats.queuedChunks, "the newest chunks must be kept")
        assertEquals(4, stats.droppedBytes, "the oldest chunk must be dropped")
        assertEquals(4, dropped.get(), "the drop must be reported")

        release.countDown()
        writer.awaitDrained(Duration.ofSeconds(5))
        writer.close()
    }

    @Test
    fun `a write slower than the threshold is reported as blocked`() {
        val blocked = CountDownLatch(1)
        val recorded = AtomicLong()
        val writer = InputWriter(
            name = "test",
            sink = { Thread.sleep(120) },
            blockedAfterMillis = 50,
            onBlocked = {
                recorded.set(it)
                blocked.countDown()
            },
        )

        writer.submit("x".toByteArray())

        assertTrue(blocked.await(5, TimeUnit.SECONDS), "a slow write must be reported")
        assertTrue(recorded.get() >= 50, "recorded ${recorded.get()}ms")
        assertTrue(writer.stats().lastBlockedMillis >= 50, writer.stats().toString())
        writer.close()
    }

    @Test
    fun `closing discards queued input`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val delivered = AtomicLong()
        val writer = InputWriter(
            "test",
            sink = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                delivered.incrementAndGet()
            },
        )
        writer.submit("first".toByteArray())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        writer.submit("second".toByteArray())

        writer.close()
        release.countDown()
        Thread.sleep(100)

        assertEquals(1, delivered.get(), "bytes queued before close must not be delivered")
        assertEquals(0, writer.stats().pendingBytes)

        writer.submit("after-close".toByteArray())
        Thread.sleep(50)
        assertEquals(1, delivered.get(), "a closed writer must ignore new input")
    }
}
