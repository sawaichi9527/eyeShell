package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SyntheticTerminalSessionTest {
    @Test
    fun `session provides deterministic input and captures terminal writes`() {
        val session = SyntheticTerminalSession.fromText("terminal input")
        val buffer = CharArray(32)

        assertEquals(14, session.read(buffer, 0, buffer.size))
        assertEquals("terminal input", buffer.concatToString(0, 14))
        assertEquals(-1, session.read(buffer, 0, buffer.size))
        assertFalse(session.isOpen)

        session.write("text")
        session.write(" bytes".toByteArray(StandardCharsets.UTF_8))
        assertEquals("text bytes", session.writtenText())
    }
}
