package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputActions
import java.io.StringWriter
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JediTermTerminalViewTest {
    @Test
    fun `exports main buffer with logical line breaks and scrollback`() {
        val view = onEdt {
            JediTermTerminalView(columns = 12, rows = 3).also {
                it.attach(SyntheticTerminalSession.fromText(SCRIPT))
            }
        }

        try {
            await(Duration.ofSeconds(5)) { !view.isSessionRunning }
            val output = StringWriter().also(view::writeAllOutput).toString()

            assertTrue(view.historyLineCount > 0, "Expected synthetic output to create scrollback")
            assertTrue(output.contains("ANSI red\n"), output)
            assertTrue(output.contains("Unicode 中文\n"), output)
            assertTrue(output.contains("soft-wrap-123456\n"), output)
            assertTrue(output.contains("hard break\n"), output)
            assertTrue(output.contains("scroll 5\n"), output)
            assertTrue(output.contains("after alternate\n"), output)
            assertFalse(output.contains("alternate only"), output)
            assertFalse(view.isUsingAlternateBuffer)

            onEdt { view.clearScrollback() }
            val clearedOutput = StringWriter().also(view::writeAllOutput).toString()
            assertTrue(view.historyLineCount == 0)
            assertFalse(clearedOutput.contains("ANSI red"), clearedOutput)
            assertTrue(clearedOutput.contains("after alternate\n"), clearedOutput)
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `exports retained main buffer while alternate screen is active`() {
        val view = onEdt {
            JediTermTerminalView(columns = 20, rows = 3).also {
                it.attach(SyntheticTerminalSession.fromText("main output\r\n$ESC[?1049halternate only"))
            }
        }

        try {
            await(Duration.ofSeconds(5)) { !view.isSessionRunning }
            assertTrue(view.isUsingAlternateBuffer)

            val output = StringWriter().also(view::writeAllOutput).toString()

            assertTrue(output.contains("main output\n"), output)
            assertFalse(output.contains("alternate only"), output)
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `context output actions enable after a session is attached`() {
        val copyCount = AtomicInteger()
        val saveCount = AtomicInteger()
        val view = onEdt {
            JediTermTerminalView().also {
                it.setOutputActions(TerminalOutputActions(copyCount::incrementAndGet, saveCount::incrementAndGet))
            }
        }

        try {
            onEdt {
                assertTrue(view.contextOutputActions.none { it.isEnabled(null) })
                view.attach(SyntheticTerminalSession.fromText("ready\r\n"))
                val actions = view.contextOutputActions
                assertTrue(actions.all { it.isEnabled(null) })
                assertFalse(actions.first().isSeparated)
                actions.single { it.name == "Copy All Output" }.actionPerformed(null)
                actions.single { it.name == "Save All Output..." }.actionPerformed(null)
            }

            assertEquals(1, copyCount.get())
            assertEquals(1, saveCount.get())
        } finally {
            onEdt { view.close() }
        }
    }

    private fun await(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Terminal processing did not finish within $timeout" }
            Thread.sleep(10)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }

    companion object {
        private const val ESC = '\u001B'
        private val SCRIPT = buildString {
            append("ANSI $ESC[31mred$ESC[0m\r\n")
            append("Unicode 中文\r\n")
            append("soft-wrap-123456\r\n")
            append("hard break\r\n")
            repeat(5) { append("scroll ${it + 1}\r\n") }
            append("$ESC[?1049halternate only$ESC[?1049l")
            append("after alternate\r\n")
        }
    }
}
