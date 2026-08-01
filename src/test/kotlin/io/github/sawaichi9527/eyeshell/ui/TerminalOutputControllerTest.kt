package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TerminalOutputControllerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `copy collection enforces its character limit`() {
        val view = TestTerminalView { it.write("12345") }

        assertEquals("12345", collectAllOutput(view.captureAllOutput(), 5))
        assertThrows(IOException::class.java) { collectAllOutput(view.captureAllOutput(), 4) }
        assertEquals("😀", collectAllOutput(TestTerminalView { it.write("😀") }.captureAllOutput(), 2))
        assertThrows(IOException::class.java) {
            collectAllOutput(TestTerminalView { it.write("😀") }.captureAllOutput(), 1)
        }
        Thread.currentThread().interrupt()
        assertThrows(IOException::class.java) { collectAllOutput(view.captureAllOutput(), 5) }
        Thread.interrupted()
    }

    @Test
    fun `atomic save replaces target only after complete output`() {
        val target = temporaryDirectory.resolve("output.txt")
        Files.writeString(target, "old")

        writeAllOutputAtomically(TestTerminalView { it.write("Unicode 中文\n") }.captureAllOutput(), target)
        assertEquals("Unicode 中文\n", Files.readString(target))

        assertThrows(IOException::class.java) {
            writeAllOutputAtomically(TestTerminalView {
                it.write("partial")
                throw IOException("synthetic export failure")
            }.captureAllOutput(), target)
        }
        assertEquals("Unicode 中文\n", Files.readString(target))

        assertThrows(IOException::class.java) {
            writeAllOutputAtomically(TestTerminalView {
                it.write("cancelled")
                Thread.currentThread().interrupt()
            }.captureAllOutput(), target)
        }
        Thread.interrupted()
        assertEquals("Unicode 中文\n", Files.readString(target))

        Files.list(temporaryDirectory).use { files ->
            assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    @Test
    fun `controller defers terminal close only until snapshot capture finishes`() {
        val view = BlockingCaptureTerminalView()
        val controller = TerminalOutputController(view)
        val owner = JPanel()
        SwingUtilities.invokeAndWait {
            controller.install(owner)
            view.actions!!.copyAllOutput()
        }
        assertTrue(view.captureStarted.await(2, TimeUnit.SECONDS))

        SwingUtilities.invokeAndWait { controller.close(view::close) }
        assertFalse(view.closed.get())

        view.releaseCapture.countDown()
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (!view.closed.get()) {
            check(System.nanoTime() < deadline) { "Terminal did not close after snapshot capture completed" }
            Thread.sleep(10)
        }
    }

    private class TestTerminalView(
        private val output: (Writer) -> Unit,
    ) : TerminalView {
        override val component: JComponent = JPanel()

        override fun attach(session: TerminalSession) = Unit

        override fun captureAllOutput(): TerminalOutputSnapshot = TerminalOutputSnapshot(output)

        override fun setContextActions(actions: TerminalContextActions) = Unit

        override fun selectVisible() = Unit

        override fun selectAllOutput() = Unit

        override fun showSearch() = Unit

        override fun setHighlightRules(rules: List<TerminalHighlightRule>) = Unit

        override fun clearScrollback() = Unit

        override fun close() = Unit
    }

    private class BlockingCaptureTerminalView : TerminalView {
        override val component: JComponent = JPanel()
        val captureStarted = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val closed = AtomicBoolean()
        var actions: TerminalContextActions? = null

        override fun attach(session: TerminalSession) = Unit

        override fun captureAllOutput(): TerminalOutputSnapshot {
            captureStarted.countDown()
            while (releaseCapture.count > 0) {
                try {
                    releaseCapture.await()
                } catch (_: InterruptedException) {
                    // Model lock acquisition is non-interruptible; emulate it for lifecycle coverage.
                }
            }
            return TerminalOutputSnapshot { it.write("captured") }
        }

        override fun setContextActions(actions: TerminalContextActions) {
            this.actions = actions
        }

        override fun selectVisible() = Unit

        override fun selectAllOutput() = Unit

        override fun showSearch() = Unit

        override fun setHighlightRules(rules: List<TerminalHighlightRule>) = Unit

        override fun clearScrollback() = Unit

        override fun close() {
            closed.set(true)
        }
    }
}
