package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A frozen tab must never forward keystrokes to a remote shell the user cannot see. The session is
 * closed when the emulator fails or stalls, and this guard makes any keystroke after that point a
 * no-op instead of a blind command.
 */
class SessionTtyConnectorTest {
    @Test
    fun `writes are dropped once the session ended`() {
        val session = RecordingSession()
        val connector = SessionTtyConnector(session)
        session.open = false

        connector.write("exit\r")
        connector.write(byteArrayOf(0x0D))

        assertEquals(0, session.writeCount)
    }

    @Test
    fun `writes are forwarded while the session is open`() {
        val session = RecordingSession()
        val connector = SessionTtyConnector(session)

        connector.write("ls\r")

        assertEquals(1, session.writeCount)
        assertEquals("ls\r", session.text.toString())
    }

    private class RecordingSession : TerminalSession {
        private val closed = AtomicBoolean()

        @Volatile
        var open: Boolean = true

        var writeCount = 0
        val text = StringBuilder()

        override val name: String = "recording-session"

        override val isOpen: Boolean
            get() = open && !closed.get()

        override fun read(buffer: CharArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray) {
            writeCount += 1
            text.append(String(bytes, Charsets.UTF_8))
        }

        override fun write(text: String) {
            writeCount += 1
            this.text.append(text)
        }

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = false

        override fun awaitExit(): Int = 0

        override fun close() {
            closed.set(true)
        }
    }
}
