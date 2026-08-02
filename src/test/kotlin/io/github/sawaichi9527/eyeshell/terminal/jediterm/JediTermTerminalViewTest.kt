package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.HighlightMergeMode
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import java.io.StringWriter
import java.io.IOException
import java.io.PipedReader
import java.io.PipedWriter
import java.awt.Component
import java.awt.Container
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import javax.swing.SwingUtilities
import javax.swing.JLabel
import javax.swing.JTextField
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JediTermTerminalViewTest {
    @Test
    fun `terminal retains the phase one scrollback baseline`() {
        assertEquals(EyeShellTerminalSettings.MAX_SCROLLBACK_LINES, EyeShellTerminalSettings().bufferMaxLinesCount)
    }

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
    fun `two terminal views keep session output isolated`() {
        val first = onEdt {
            JediTermTerminalView(columns = 20, rows = 3).also {
                it.attach(SyntheticTerminalSession.fromText("first session\r\n"))
            }
        }
        val second = onEdt {
            JediTermTerminalView(columns = 20, rows = 3).also {
                it.attach(SyntheticTerminalSession.fromText("second session\r\n"))
            }
        }

        try {
            await(Duration.ofSeconds(5)) { !first.isSessionRunning && !second.isSessionRunning }
            val firstOutput = StringWriter().also(first::writeAllOutput).toString()
            val secondOutput = StringWriter().also(second::writeAllOutput).toString()

            assertTrue(firstOutput.contains("first session"), firstOutput)
            assertFalse(firstOutput.contains("second session"), firstOutput)
            assertTrue(secondOutput.contains("second session"), secondOutput)
            assertFalse(secondOutput.contains("first session"), secondOutput)
        } finally {
            onEdt {
                first.close()
                second.close()
            }
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
    fun `retains and highlights one hundred thousand scrollback lines`() {
        val script = buildString {
            repeat(EyeShellTerminalSettings.MAX_SCROLLBACK_LINES + 10) { index ->
                append("line ")
                append(index.toString().padStart(6, '0'))
                append(if (index % 10_000 == 0) " ERROR\r\n" else " ok\r\n")
            }
            append("FINAL ERROR")
        }
        val view = onEdt {
            JediTermTerminalView(columns = 40, rows = 4).also {
                it.attach(SyntheticTerminalSession.fromText(script))
                it.setHighlightRules(listOf(errorRule()))
            }
        }

        try {
            await(Duration.ofSeconds(30)) { !view.isSessionRunning }
            assertEquals(EyeShellTerminalSettings.MAX_SCROLLBACK_LINES, view.historyLineCount)
            await(Duration.ofSeconds(30)) {
                onEdt {
                    val result = view.coordinateHighlightResult ?: return@onEdt false
                    result.revision == view.mainBufferRevision && result.getSpans(3).any {
                        it.startCell == 6 && it.endCell == 11
                    }
                }
            }
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `continuous output keeps the EDT responsive and converges after quiescence`() {
        val session = StreamingTerminalSession()
        val keepProducing = AtomicBoolean(true)
        val view = onEdt {
            JediTermTerminalView(columns = 40, rows = 4).also {
                it.attach(session)
                it.setHighlightRules(listOf(errorRule()))
            }
        }
        val producer = Thread.ofVirtual().name("highlight-output-test").start {
            var line = 0
            while (keepProducing.get()) {
                session.emit("line $line ${if (line % 100 == 0) "ERROR" else "ok"}\r\n")
                line++
            }
            session.emit("FINAL ERROR")
            session.finish()
        }

        try {
            await(Duration.ofSeconds(10)) { session.charactersRead.get() >= 10_000 }
            assertTrue(producer.isAlive, "Output stopped before the EDT responsiveness probe")
            repeat(5) {
                val edtServiced = CountDownLatch(1)
                SwingUtilities.invokeLater(edtServiced::countDown)
                assertTrue(edtServiced.await(2, TimeUnit.SECONDS), "Continuous highlighting blocked the Swing EDT")
                assertTrue(producer.isAlive, "Output stopped during the EDT responsiveness probes")
            }
            keepProducing.set(false)
            producer.join(Duration.ofSeconds(10))
            assertFalse(producer.isAlive, "Synthetic output producer did not finish")
            await(Duration.ofSeconds(10)) { !view.isSessionRunning }
            await(Duration.ofSeconds(10)) {
                onEdt {
                    val result = view.coordinateHighlightResult ?: return@onEdt false
                    result.revision == view.mainBufferRevision && result.getSpans(3).any {
                        it.startCell == 6 && it.endCell == 11
                    }
                }
            }
        } finally {
            keepProducing.set(false)
            session.close()
            onEdt { view.close() }
            producer.join(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `closing during active output unblocks the terminal and rejects later highlight work`() {
        val session = StreamingTerminalSession()
        val view = onEdt {
            JediTermTerminalView(columns = 40, rows = 4).also {
                it.attach(session)
                it.setHighlightRules(listOf(errorRule()))
            }
        }
        val producerStarted = CountDownLatch(1)
        val producer = Thread.ofVirtual().name("highlight-close-test").start {
            try {
                var line = 0
                while (true) {
                    session.emit("active $line ERROR\r\n")
                    if (line++ == 0) producerStarted.countDown()
                }
            } catch (_: IOException) {
                // Closing the terminal intentionally interrupts the synthetic stream.
            }
        }
        try {
            await(Duration.ofSeconds(5)) { onEdt { view.coordinateHighlightResult != null } }
            assertTrue(producerStarted.await(5, TimeUnit.SECONDS))
            val initialReadCount = session.charactersRead.get()
            await(Duration.ofSeconds(5)) { session.charactersRead.get() > initialReadCount }

            onEdt { view.close() }

            val resultAfterClose = onEdt { view.coordinateHighlightResult }
            assertTrue(session.closed.await(5, TimeUnit.SECONDS), "Terminal close did not close its session")
            producer.join(Duration.ofSeconds(5))
            assertFalse(producer.isAlive, "Output remained blocked after terminal close")
            assertTrue(view.awaitHighlightTermination(5, TimeUnit.SECONDS), "Highlight worker did not terminate")
            onEdt { }
            assertTrue(resultAfterClose === onEdt { view.coordinateHighlightResult }, "Highlight result changed after terminal close")
        } finally {
            session.close()
            onEdt { view.close() }
            producer.join(Duration.ofSeconds(5))
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

    private fun errorRule() = TerminalHighlightRule(
        name = "Errors",
        pattern = "ERROR",
        matchCase = true,
        enabled = true,
        priority = 0,
        foregroundRgb = 0xFFFFFF,
        backgroundRgb = 0xAA0000,
        bold = true,
        italic = false,
        underline = false,
        mergeMode = HighlightMergeMode.MERGE,
    )

    private class StreamingTerminalSession : TerminalSession {
        private val reader = PipedReader(64 * 1024)
        private val writer = PipedWriter(reader)
        private val open = AtomicBoolean(true)
        val charactersRead = AtomicInteger()
        val closed = CountDownLatch(1)

        override val name: String = "streaming-test"
        override val isOpen: Boolean
            get() = open.get()

        @Synchronized
        fun emit(text: String) {
            if (!open.get()) throw IOException("Synthetic terminal session is closed")
            writer.write(text)
            writer.flush()
        }

        fun finish() {
            if (open.compareAndSet(true, false)) writer.close()
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int =
            reader.read(buffer, offset, length).also { if (it > 0) charactersRead.addAndGet(it) }
        override fun write(bytes: ByteArray) = Unit
        override fun write(text: String) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun ready(): Boolean = reader.ready()
        override fun awaitExit(): Int = 0

        override fun close() {
            finish()
            reader.close()
            closed.countDown()
        }
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
