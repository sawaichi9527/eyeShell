package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession
import java.io.StringWriter
import java.time.Duration
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
