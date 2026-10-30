package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplayTerminalSessionTest {
    @Test
    fun `the prefix is served before the live session`() {
        val delegate = RecordingSession("live")
        val replay = ReplayTerminalSession("history".toCharArray(), delegate)

        assertEquals("historylive", readAll(replay))
        assertTrue(delegate.reads > 0)
    }

    @Test
    fun `the delegate is not read while the prefix remains`() {
        val delegate = RecordingSession("live")
        val replay = ReplayTerminalSession("abc".toCharArray(), delegate)
        val buffer = CharArray(2)

        assertEquals(2, replay.read(buffer, 0, 2))
        assertEquals("ab", String(buffer, 0, 2))
        assertTrue(replay.ready())
        assertEquals(0, delegate.reads)
    }

    @Test
    fun `closing the replay does not close the live session`() {
        val delegate = RecordingSession("live")
        val replay = ReplayTerminalSession("h".toCharArray(), delegate)

        replay.close()

        assertFalse(delegate.closed)
    }

    @Test
    fun `charset, raw output, and identity delegate to the live session`() {
        val delegate = RecordingSession("live")
        delegate.setCharset(Charset.forName("GBK"))
        val replay = ReplayTerminalSession(charArrayOf('x'), delegate)

        assertEquals(Charset.forName("GBK"), replay.charset)
        assertEquals("recording-session", replay.name)
        assertTrue(replay.isOpen)
        assertTrue(replay.rawOutput()?.isNotEmpty() == true)
    }

    private fun readAll(session: TerminalSession): String {
        val result = StringBuilder()
        val buffer = CharArray(16)
        while (true) {
            val count = session.read(buffer, 0, buffer.size)
            if (count < 0) return result.toString()
            result.append(buffer, 0, count)
        }
    }

    private class RecordingSession(private val text: String) : TerminalSession {
        var reads = 0
        var closed = false
        private var position = 0
        private var activeCharset: Charset = StandardCharsets.UTF_8

        override val name: String = "recording-session"
        override val isOpen: Boolean = true
        override val charset: Charset
            get() = activeCharset

        override fun setCharset(charset: Charset) {
            activeCharset = charset
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            reads += 1
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

        override fun rawOutput(): ByteArray = "bytes".toByteArray()

        override fun awaitExit(): Int = 0

        override fun close() {
            closed = true
        }
    }
}
