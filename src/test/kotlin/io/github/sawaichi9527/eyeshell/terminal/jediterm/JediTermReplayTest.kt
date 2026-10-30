package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.ReplayTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.io.StringWriter
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the two primitives the encoding re-decode relies on: detaching the view without closing the
 * SSH session, and replaying re-decoded history into the same widget.
 */
class JediTermReplayTest {
    @Test
    fun `detach stops the emulator but keeps the session, and a replay replaces the buffer`() {
        val session = ScriptedSession("first output\r\n")
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 6) }

        try {
            onEdt { view.attach(session) }
            await(Duration.ofSeconds(5)) { output(view).contains("first output") }

            assertTrue(view.detachSession(), "A polling connector must detach promptly")
            assertFalse(session.closed, "Detaching must not close the live session")
            assertTrue(view.canAttach())

            val prefix = "\u001B[3J\u001B[H" + "re-decoded 中文\r\n"
            onEdt { view.attach(ReplayTerminalSession(prefix.toCharArray(), session)) }

            await(Duration.ofSeconds(5)) { output(view).contains("re-decoded 中文") }
            assertFalse(output(view).contains("first output"), "The replay must replace the old buffer")

            onEdt { view.close() }
            assertFalse(session.closed, "Closing a replay view must not close the live session")
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
    private class ScriptedSession(private val text: String) : TerminalSession {
        private var position = 0
        private val closedFlag = AtomicBoolean()

        override val name: String = "scripted-session"

        override val isOpen: Boolean
            get() = !closedFlag.get()

        var closed: Boolean
            get() = closedFlag.get()
            set(value) {
                closedFlag.set(value)
            }

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
}
