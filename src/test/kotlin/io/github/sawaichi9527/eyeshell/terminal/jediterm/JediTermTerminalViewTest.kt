package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.HighlightMergeMode
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import java.io.StringWriter
import java.awt.Component
import java.awt.Container
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import javax.swing.SwingUtilities
import javax.swing.JLabel
import javax.swing.JTextField
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JediTermTerminalViewTest {
    @Test
    fun `select all leaves an empty terminal unselected`() {
        val view = onEdt { JediTermTerminalView() }

        try {
            onEdt { view.selectAllOutput() }
            assertEquals(null, view.selection)
        } finally {
            onEdt { view.close() }
        }
    }

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
            onEdt { view.selectAllOutput() }
            assertTrue(view.isRetainedMainSelection)

            val output = StringWriter().also(view::writeAllOutput).toString()

            assertTrue(output.contains("main output\n"), output)
            assertFalse(output.contains("alternate only"), output)
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `background search publishes only the latest query`() {
        val view = onEdt {
            JediTermTerminalView(columns = 40, rows = 3).also {
                it.attach(SyntheticTerminalSession.fromText("alpha beta alpha\r\n"))
            }
        }

        try {
            await(Duration.ofSeconds(5)) { !view.isSessionRunning }
            val edtServiced = CountDownLatch(1)
            onEdt {
                view.showSearch()
                val query = view.searchComponent.findByName("terminalSearchQuery") as JTextField
                query.text = "alpha"
                query.text = "missing"
                SwingUtilities.invokeLater(edtServiced::countDown)
            }
            assertTrue(edtServiced.await(1, TimeUnit.SECONDS), "Search blocked the Swing EDT")
            await(Duration.ofSeconds(5)) {
                onEdt {
                    (view.searchComponent.findByName("terminalSearchStatus") as JLabel).text == "0 matches"
                }
            }
            assertEquals(0, onEdt { view.coordinateSearchResult?.matches?.size })
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `background highlight publishes a revisioned main buffer overlay`() {
        val view = onEdt {
            JediTermTerminalView(columns = 40, rows = 3).also {
                it.attach(SyntheticTerminalSession.fromText("INFO error INFO\r\n"))
            }
        }

        try {
            await(Duration.ofSeconds(5)) { !view.isSessionRunning }
            onEdt {
                view.setHighlightRules(listOf(TerminalHighlightRule(
                    name = "Errors",
                    pattern = "error",
                    matchCase = false,
                    enabled = true,
                    priority = 0,
                    foregroundRgb = 0xFFFFFF,
                    backgroundRgb = 0xAA0000,
                    bold = true,
                    italic = false,
                    underline = false,
                    mergeMode = HighlightMergeMode.MERGE,
                )))
            }
            await(Duration.ofSeconds(5)) { onEdt { view.coordinateHighlightResult?.getSpans(0)?.size == 1 } }
            assertEquals(1, onEdt { view.coordinateHighlightResult?.getSpans(0)?.size })
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `context output actions enable after a session is attached`() {
        val copyCount = AtomicInteger()
        val saveCount = AtomicInteger()
        val addHighlightCount = AtomicInteger()
        val manageHighlightCount = AtomicInteger()
        val view = onEdt {
            JediTermTerminalView().also {
                it.setContextActions(TerminalContextActions(
                    copyCount::incrementAndGet,
                    saveCount::incrementAndGet,
                    addHighlightCount::incrementAndGet,
                    manageHighlightCount::incrementAndGet,
                ))
            }
        }

        try {
            onEdt {
                assertTrue(view.contextActions.none { it.isEnabled(null) })
                view.attach(SyntheticTerminalSession.fromText("ready\r\n"))
            }
            await(Duration.ofSeconds(5)) { !view.isSessionRunning }
            onEdt {
                val actions = view.contextActions
                assertEquals(listOf(
                    "Copy",
                    "Paste",
                    "Select Visible",
                    "Select All Output",
                    "Copy All Output",
                    "Save All Output...",
                    "Search...",
                    "Add Color Highlight Rule...",
                    "Manage Color Highlight Rules...",
                    "Clear Buffer",
                ), actions.map { it.name })
                assertEquals(listOf(2, 6, 9), actions.indices.filter { actions[it].isSeparated })
                assertFalse(actions.single { it.name == "Copy" }.isEnabled(null))
                assertTrue(actions.single { it.name == "Add Color Highlight Rule..." }.isEnabled(null))
                assertTrue(actions.single { it.name == "Manage Color Highlight Rules..." }.isEnabled(null))

                actions.single { it.name == "Select All Output" }.actionPerformed(null)
                assertTrue(view.isRetainedMainSelection)
                actions.single { it.name == "Copy" }.actionPerformed(null)
                actions.single { it.name == "Save All Output..." }.actionPerformed(null)
                actions.single { it.name == "Search..." }.actionPerformed(null)
                actions.single { it.name == "Add Color Highlight Rule..." }.actionPerformed(null)
                actions.single { it.name == "Manage Color Highlight Rules..." }.actionPerformed(null)
                assertTrue(view.searchComponent.isVisible)
            }

            assertEquals(1, copyCount.get())
            assertEquals(1, saveCount.get())
            assertEquals(1, addHighlightCount.get())
            assertEquals(1, manageHighlightCount.get())
            assertFalse(onEdt { view.contextActions.single { it.name == "Paste" }.isEnabled(null) })
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

    private fun Container.findByName(componentName: String): Component? {
        components.forEach { component ->
            if (component.name == componentName) return component
            if (component is Container) component.findByName(componentName)?.let { return it }
        }
        return null
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
