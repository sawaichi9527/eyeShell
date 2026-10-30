package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.sftp.SftpClient
import io.github.sawaichi9527.eyeshell.ssh.ExecResult
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import io.github.sawaichi9527.eyeshell.terminal.jediterm.JediTermTerminalView
import java.io.StringWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tab keeps its live SSH session when the encoding changes: the retained raw bytes are re-decoded
 * and replayed, so output that was already on screen becomes readable without reconnecting.
 */
class TerminalCharsetRedecodeTest {
    @Test
    fun `switching the encoding re-decodes output that was already displayed`() {
        val gbk = Charset.forName("GBK")
        val bytes = "中文輸出\r\n".toByteArray(gbk)
        val session = ByteStreamSession(bytes)
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 6) }
        val page = onEdt { TerminalSessionPage(JPanel(), view, RecordingHostSession(session), session) }

        try {
            onEdt { page.attach() }
            await(Duration.ofSeconds(5)) { output(view).isNotEmpty() }
            assertFalse(output(view).contains("中文輸出"), "UTF-8 must garble GBK bytes first")

            onEdt { page.applyCharset(gbk) }

            await(
                timeout = Duration.ofSeconds(10),
                describe = { output(view) },
                condition = { output(view).contains("中文輸出") },
            )
            assertFalse(session.closed, "Re-decoding must keep the SSH session alive")
        } finally {
            onEdt { page.close() }
        }
    }

    private fun output(view: TerminalView): String =
        StringWriter().also { writer -> view.writeAllOutput(writer) }.toString()

    private fun await(timeout: Duration, describe: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Condition not met within $timeout: ${describe()}")
            }
            Thread.sleep(10)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    /**
     * Behaves like a session with a fixed byte stream: output is decoded with the active charset and
     * the original bytes stay available for a re-decode.
     */
    private class ByteStreamSession(
        private val bytes: ByteArray,
        initialCharset: Charset = StandardCharsets.UTF_8,
    ) : TerminalSession {
        private var position = 0
        private val closedFlag = AtomicBoolean()

        @Volatile
        private var activeCharset: Charset = initialCharset

        override val name: String = "byte-stream-session"

        override val isOpen: Boolean
            get() = !closedFlag.get()

        override val charset: Charset
            get() = activeCharset

        var closed: Boolean
            get() = closedFlag.get()
            set(value) {
                closedFlag.set(value)
            }

        override fun setCharset(charset: Charset) {
            activeCharset = charset
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val text = String(bytes, activeCharset)
            if (position >= text.length) return -1
            val count = minOf(length, text.length - position)
            text.toCharArray().copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        /** Stays idle-but-open after the scripted bytes, like a shell sitting at its prompt. */
        override fun ready(): Boolean = position < String(bytes, activeCharset).length

        override fun rawOutput(): ByteArray = bytes

        override fun awaitExit(): Int = 0

        override fun close() {
            closedFlag.set(true)
        }
    }

    private class RecordingHostSession(private val terminal: TerminalSession) : HostSession {
        override val endpoint: SshEndpoint = SshEndpoint("127.0.0.1", 22, "test-user")

        override fun openTerminal(columns: Int, rows: Int): TerminalSession = terminal

        override fun execute(command: String): ExecResult = throw UnsupportedOperationException()

        override fun sftp(): SftpClient = throw UnsupportedOperationException()

        override fun isOpen(): Boolean = true

        override fun close() = Unit
    }
}
