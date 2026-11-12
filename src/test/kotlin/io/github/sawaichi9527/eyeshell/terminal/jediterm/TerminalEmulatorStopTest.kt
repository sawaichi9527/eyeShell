package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.io.StringWriter
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A terminal that looks connected but never updates again is what a dead emulator thread and a
 * stalled stream both produce. Only the first one is an error, so the view distinguishes the two
 * and the old "silent freeze" now has a visible path: a reported failure plus a log line.
 */
class TerminalEmulatorStopTest {
    @Test
    fun `an emulator that dies while the session is open is reported as unexpected`() {
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 6) }
        val reported = AtomicReference<Boolean>()
        val stopped = CountDownLatch(1)
        onEdt { view.setEmulatorStopHandler { unexpected -> reported.set(unexpected); stopped.countDown() } }

        try {
            onEdt { view.attach(ThrowingSession()) }
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "the emulator stop was never reported")
            assertTrue(reported.get(), "an exception inside the emulator is not an expected end")
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `a remote end of stream is not reported as unexpected`() {
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 6) }
        val reported = AtomicReference<Boolean>()
        val stopped = CountDownLatch(1)
        onEdt { view.setEmulatorStopHandler { unexpected -> reported.set(unexpected); stopped.countDown() } }

        try {
            onEdt { view.attach(FinishedSession()) }
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "the emulator stop was never reported")
            assertFalse(reported.get(), "a closed stream ends the emulator by design")
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `detaching for an encoding replay is not reported as unexpected`() {
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 6) }
        val reported = AtomicReference<Boolean>()
        val stopped = CountDownLatch(1)
        onEdt { view.setEmulatorStopHandler { unexpected -> reported.set(unexpected); stopped.countDown() } }
        val session = QuietSession()

        try {
            onEdt { view.attach(session) }
            assertTrue(onEdt { view.detachSession() }, "a quiet session must detach promptly")
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "the detach was never reported")
            assertFalse(reported.get(), "a deliberate detach is not a failure")
        } finally {
            onEdt { view.close() }
        }
    }

    /**
     * Homebrew emits `CSI ? 2026 l` (the end of a synchronized-update block) before its output. The
     * pinned JediTerm used to start a sync block on that sequence too and then block the emulator
     * thread until the next byte arrived. Upstream fixed it in 3.76 (`1b354a6`); this keeps the
     * behaviour from regressing with a future submodule bump.
     */
    @Test
    fun `an end of synchronized output sequence does not swallow the output that follows`() {
        val session = ScriptedSession("\u001B[?2026lhello\r\n")
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 6) }

        try {
            onEdt { view.attach(session) }
            await(Duration.ofSeconds(5)) { output(view).contains("hello") }
        } finally {
            onEdt { view.close() }
            session.close()
        }
    }

    private fun output(view: JediTermTerminalView): String =
        StringWriter().also { writer -> view.writeAllOutput(writer) }.toString()

    private fun await(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met within $timeout" }
            Thread.sleep(10)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    /** Serves its text once and then stays open without data, like a quiet shell prompt. */
    private open class ScriptedSession(private val text: String) : TerminalSession {
        private var position = 0
        private val closedFlag = AtomicBoolean()

        override val name: String = "scripted-session"

        override val isOpen: Boolean
            get() = !closedFlag.get()

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (position >= text.length) return -1
            val count = minOf(length, text.length - position)
            text.toCharArray().copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = position < text.length

        override fun awaitExit(): Int = 0

        override fun close() {
            closedFlag.set(true)
        }
    }

    private class FinishedSession : ScriptedSession("bye\r\n") {
        private val done = AtomicBoolean()

        override val isOpen: Boolean
            get() = super.isOpen && !done.get()

        override fun ready(): Boolean {
            if (!super.ready()) {
                done.set(true)
                return false
            }
            return true
        }
    }

    /** An open session whose ready stream throws, i.e. a broken channel or emulator input path. */
    private class ThrowingSession : TerminalSession {
        override val name: String = "throwing-session"
        override val isOpen: Boolean = true

        override fun read(buffer: CharArray, offset: Int, length: Int): Int =
            throw IllegalStateException("terminal input broke")

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = true

        override fun awaitExit(): Int = 0

        override fun close() = Unit
    }

    /** Open, but never produces a byte: the detach path has to interrupt the polling loop. */
    private class QuietSession : TerminalSession {
        private val closedFlag = AtomicBoolean()

        override val name: String = "quiet-session"
        override val isOpen: Boolean
            get() = !closedFlag.get()

        override fun read(buffer: CharArray, offset: Int, length: Int): Int = 0

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = false

        override fun awaitExit(): Int = 0

        override fun close() {
            closedFlag.set(true)
        }
    }
}
