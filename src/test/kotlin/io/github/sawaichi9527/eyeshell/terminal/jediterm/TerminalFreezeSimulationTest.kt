package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.ssh.ExecResult
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.ui.SessionStatus
import io.github.sawaichi9527.eyeshell.ui.TerminalSessionPage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Local simulation of the reported freeze: the session stays open but its reader never returns, so
 * the tab stops updating while the SSH channel is still there.
 *
 * The watchdog interval is injected (10 ms instead of 5 s) so the whole production path runs in the
 * test: sample the emulator thread, classify the stall, log it, write the thread dump, report the
 * failure, and close the session.
 */
class TerminalFreezeSimulationTest {
    @TempDir
    lateinit var diagnostics: Path

    @Test
    fun `a terminal that stops mid-stream is detected, logged, and dumped`() {
        val records = CopyOnWriteArrayList<String>()
        val logHandler = collectingHandler(records)
        val log = Logger.getLogger("io.github.sawaichi9527.eyeshell.terminal")
        log.addHandler(logHandler)

        val view = onEdt {
            JediTermTerminalView(
                columns = 60,
                rows = 10,
                diagnosticsDirectory = diagnostics,
                emulatorStallSampleMillis = 10,
                emulatorStallSamples = 2,
            )
        }
        val session = FreezingSession()
        val reported = AtomicReference<Boolean>()
        val stop = CountDownLatch(1)
        onEdt { view.setEmulatorStopHandler { unexpected -> reported.set(unexpected); stop.countDown() } }

        try {
            onEdt { view.attach(session) }
            assertTrue(stop.await(5, TimeUnit.SECONDS), "the stall was never reported")

            assertTrue(reported.get(), "a stalled emulator is a failure, not a clean exit")
            val dumps = Files.list(diagnostics).use { files ->
                files.map { it.fileName.toString() }.toList()
            }
            assertTrue(
                dumps.any { it.startsWith("thread-dump-terminal-stall-freezing-session-") },
                "expected a thread dump, found $dumps",
            )
            assertTrue(
                records.any { it.startsWith("Terminal emulator stalled on freezing-session") },
                "expected a SEVERE stall line, found $records",
            )
        } finally {
            log.removeHandler(logHandler)
            onEdt { view.close() }
        }
    }

    @Test
    fun `the page closes the stalled session and marks the tab failed`() {
        val status = AtomicReference<SessionStatus>()
        val failed = CountDownLatch(1)
        lateinit var page: TerminalSessionPage
        val session = FreezingSession()

        SwingUtilities.invokeAndWait {
            val view = JediTermTerminalView(
                columns = 60,
                rows = 10,
                diagnosticsDirectory = diagnostics,
                emulatorStallSampleMillis = 10,
                emulatorStallSamples = 2,
            )
            page = TerminalSessionPage(
                JPanel(),
                view,
                FakeHostSession(session),
                session,
                captureRawBytes = false,
            )
            page.attach()
            page.startEmulatorMonitor {
                status.set(SessionStatus.FAILED)
                failed.countDown()
            }
        }

        try {
            assertTrue(failed.await(5, TimeUnit.SECONDS), "the page never reported the stall")
            assertEquals(SessionStatus.FAILED, status.get())
            assertTrue(session.closed, "the channel must be closed so blind typing cannot reach the shell")
        } finally {
            SwingUtilities.invokeAndWait { page.close() }
        }
    }

    @Test
    fun `an idle shell that simply has no output is not a stall`() {
        val view = onEdt {
            JediTermTerminalView(
                columns = 60,
                rows = 10,
                diagnosticsDirectory = diagnostics,
                emulatorStallSampleMillis = 10,
                emulatorStallSamples = 2,
            )
        }
        val stop = AtomicBoolean()
        onEdt { view.setEmulatorStopHandler { unexpected -> if (unexpected) stop.set(true) } }
        val session = QuietSession()

        try {
            onEdt { view.attach(session) }
            Thread.sleep(400)
            assertFalse(stop.get(), "a quiet prompt is healthy and must never be failed")
            assertFalse(Files.exists(diagnostics.resolve("thread-dump-terminal-stall-quiet-session.txt")))
        } finally {
            onEdt { view.close() }
            session.close()
        }
    }

    private fun collectingHandler(records: MutableList<String>): Handler = object : Handler() {
        override fun publish(record: LogRecord) {
            if (record.level.intValue() >= Level.WARNING.intValue()) records.add(record.message)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    /** Reports data is available, then blocks in `read` the way a channel read would. */
    private class FreezingSession : TerminalSession {
        private val release = CountDownLatch(1)
        private val closedFlag = AtomicBoolean()

        override val name: String = "freezing-session"
        override val isOpen: Boolean
            get() = !closedFlag.get()

        val closed: Boolean
            get() = closedFlag.get()

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            release.await()
            return 0
        }

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = true

        override fun awaitExit(): Int = 0

        override fun close() {
            closedFlag.set(true)
            release.countDown()
        }
    }

    /** A healthy prompt: the session is open and the poll loop simply has nothing to deliver. */
    private class QuietSession : TerminalSession {
        private val closedFlag = AtomicBoolean()

        override val name: String = "quiet-session"
        override val isOpen: Boolean
            get() = !closedFlag.get()

        override fun read(buffer: CharArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = false

        override fun awaitExit(): Int = 0

        override fun close() {
            closedFlag.set(true)
        }
    }

    private class FakeHostSession(private val terminal: TerminalSession) : HostSession {
        override val endpoint: SshEndpoint = SshEndpoint("test.example", 22, "operator")

        override fun openTerminal(columns: Int, rows: Int): TerminalSession = terminal

        override fun execute(command: String): ExecResult = ExecResult(0, "")

        override fun sftp(): io.github.sawaichi9527.eyeshell.sftp.SftpClient =
            throw UnsupportedOperationException("SFTP is not used by this test")

        override fun isOpen(): Boolean = terminal.isOpen

        override fun close() = Unit
    }
}
