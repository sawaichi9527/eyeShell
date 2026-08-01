package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalLineSnapshot
import com.jediterm.terminal.util.CharUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JediTermSearchControllerTest {
    @Test
    fun `search crosses soft wraps but not hard line breaks`() {
        val snapshot = MainBufferSnapshot(
            7,
            emptyList(),
            listOf(
                line("soft", wrapped = true),
                line("wrap"),
                line("hard"),
                line("break"),
            ),
        )

        val softMatch = searchMainBuffer(snapshot, "ftwr", ignoreCase = false).matches.single()
        assertEquals(listOf(0, 1), softMatch.spans.map { it.row })
        assertEquals(0, searchMainBuffer(snapshot, "hardbreak", ignoreCase = false).matches.size)
    }

    @Test
    fun `search maps adjacent CJK text across double width markers`() {
        val snapshot = MainBufferSnapshot(
            11,
            emptyList(),
            listOf(line("中${CharUtils.DWC}文${CharUtils.DWC}")),
        )

        val result = searchMainBuffer(snapshot, "中文", ignoreCase = false)
        val span = result.matches.single().spans.single()

        assertEquals(0, span.startCell)
        assertEquals(4, span.endCell)
        assertEquals(11, result.revision)
    }

    @Test
    fun `case insensitive search is locale independent`() {
        val snapshot = MainBufferSnapshot(1, emptyList(), listOf(line("INFO Info info")))

        assertEquals(3, searchMainBuffer(snapshot, "info", ignoreCase = true).matches.size)
        assertTrue(searchMainBuffer(snapshot, "info", ignoreCase = false).matches.size == 1)
    }

    @Test
    fun `visible logical line scan includes both sides of a soft wrap`() {
        val snapshot = MainBufferSnapshot(
            1,
            emptyList(),
            listOf(line("soft", wrapped = true), line("wrap"), line("outside")),
        )
        val scanned = mutableListOf<String>()

        snapshot.forEachLogicalLineInRows(1, 1) { text, _ -> scanned += text }

        assertEquals(listOf("softwrap"), scanned)
    }

    private fun line(text: String, wrapped: Boolean = false): TerminalLineSnapshot =
        TerminalLineSnapshot(text, wrapped, text.isEmpty())
}
