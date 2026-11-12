package io.github.sawaichi9527.eyeshell.platform

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShutdownNoiseFilterTest {
    private fun minaExecutorRace(): IllegalStateException =
        IllegalStateException("Executor has been shut down").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "org.apache.sshd.common.util.threads.NoCloseExecutor",
                    "execute",
                    "NoCloseExecutor.java",
                    100,
                ),
                StackTraceElement("java.base/sun.nio.ch.Iocp\$EventHandlerTask", "run", "Iocp.java", 1),
            )
        }

    @Test
    fun `recognises the MINA executor shutdown race`() {
        assertTrue(ShutdownNoiseFilter.isMinaExecutorShutdownRace(minaExecutorRace()))
    }

    @Test
    fun `does not hide the same message raised outside MINA`() {
        val error = IllegalStateException("Executor has been shut down").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection",
                    "close",
                    "MinaSshConnection.kt",
                    90,
                ),
            )
        }

        assertFalse(ShutdownNoiseFilter.isMinaExecutorShutdownRace(error))
    }

    @Test
    fun `does not hide other MINA illegal state failures`() {
        val error = IllegalStateException("Channel not registered").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "org.apache.sshd.common.session.helpers.AbstractConnectionService",
                    "registerChannel",
                    "AbstractConnectionService.java",
                    424,
                ),
            )
        }

        assertFalse(ShutdownNoiseFilter.isMinaExecutorShutdownRace(error))
    }

    @Test
    fun `does not hide other exception types with the same message`() {
        val error = RuntimeException("Executor has been shut down").apply {
            stackTrace = arrayOf(
                StackTraceElement("org.apache.sshd.common.Closeable", "close", "Closeable.java", 1),
            )
        }

        assertFalse(ShutdownNoiseFilter.isMinaExecutorShutdownRace(error))
    }

    @Test
    fun `swallows the race without calling the previous handler`() {
        val forwarded = AtomicInteger()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> forwarded.incrementAndGet() }

        val suppressed = ShutdownNoiseFilter.dispatch(previous, Thread.currentThread(), minaExecutorRace())

        assertTrue(suppressed)
        assertEquals(0, forwarded.get())
    }

    @Test
    fun `forwards any other failure to the previous handler`() {
        val forwarded = AtomicInteger()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> forwarded.incrementAndGet() }

        val suppressed = ShutdownNoiseFilter.dispatch(
            previous,
            Thread.currentThread(),
            IllegalStateException("Channel not registered"),
        )

        assertFalse(suppressed)
        assertEquals(1, forwarded.get())
    }

    @Test
    fun `writes an unexpected uncaught failure into the diagnostic log`() {
        val records = java.util.concurrent.CopyOnWriteArrayList<LogRecord>()
        val logger = Logger.getLogger(ShutdownNoiseFilter::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.SEVERE.intValue()) records.add(record)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        logger.addHandler(handler)
        ShutdownNoiseFilter.resetLogThrottleForTests()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }

        try {
            val error = IllegalStateException("paint broke")
            ShutdownNoiseFilter.dispatch(previous, Thread.currentThread(), error)

            assertTrue(records.any { it.thrown === error }, records.toString())
        } finally {
            logger.removeHandler(handler)
            ShutdownNoiseFilter.resetLogThrottleForTests()
        }
    }
}
