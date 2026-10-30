package io.github.sawaichi9527.eyeshell.terminal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalCharsetsTest {
    @Test
    fun `every bundled charset resolves in a standard JVM`() {
        TerminalCharsets.ALL.forEach { entry ->
            assertTrue(Charset.isSupported(entry.id), "Missing JDK charset: ${entry.id}")
            assertEquals(Charset.forName(entry.id), entry.charset)
        }
    }

    @Test
    fun `ids and display names are unique and non-blank`() {
        val ids = TerminalCharsets.ALL.map { it.id.lowercase() }
        val names = TerminalCharsets.ALL.map { it.displayName }

        assertEquals(ids.size, ids.toSet().size, ids.toString())
        assertEquals(names.size, names.toSet().size, names.toString())
        TerminalCharsets.ALL.forEach { entry ->
            assertFalse(entry.id.isBlank())
            assertFalse(entry.displayName.isBlank())
        }
    }

    @Test
    fun `utf-8 is the default`() {
        assertEquals("UTF-8", TerminalCharsets.DEFAULT.id)
        assertEquals(StandardCharsets.UTF_8, TerminalCharsets.DEFAULT_CHARSET)
        assertEquals(TerminalCharsets.DEFAULT, TerminalCharsets.fromId(null))
        assertEquals(TerminalCharsets.DEFAULT, TerminalCharsets.fromId(""))
        assertEquals(TerminalCharsets.DEFAULT, TerminalCharsets.fromId("  "))
    }

    @Test
    fun `a stored id resolves ignoring case and surrounding whitespace`() {
        assertEquals("BIG5", TerminalCharsets.fromId(" Big5 ").id.uppercase())
        assertEquals("GBK", TerminalCharsets.fromId("gbk").id)
    }

    @Test
    fun `an unknown or removed id falls back to utf-8`() {
        assertEquals(TerminalCharsets.DEFAULT, TerminalCharsets.fromId("KOI8-R"))
        assertEquals(StandardCharsets.UTF_8, TerminalCharsets.charsetFromId("no-such-charset"))
    }

    @Test
    fun `the display name is what the combo box shows`() {
        assertEquals(TerminalCharsets.UTF_8.displayName, TerminalCharsets.UTF_8.toString())
    }
}
