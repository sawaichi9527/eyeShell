package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.google.re2j.PatternSyntaxException
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalLineSnapshot
import com.jediterm.terminal.util.CharUtils
import io.github.sawaichi9527.eyeshell.terminal.HighlightMergeMode
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JediTermHighlightControllerTest {
    @Test
    fun `regex highlight crosses soft wraps but not hard lines and maps CJK cells`() {
        val snapshot = MainBufferSnapshot(
            23,
            emptyList(),
            listOf(
                line("soft", wrapped = true),
                line("wrap"),
                line("hard"),
                line("break"),
                line("中${CharUtils.DWC}文${CharUtils.DWC}"),
            ),
        )

        val soft = highlightMainBuffer(snapshot, listOf(rule("ftwr")))
        assertEquals(2, soft.getSpans(0).size + soft.getSpans(1).size)
        assertTrue(highlightMainBuffer(snapshot, listOf(rule("hardbreak"))).getSpans(2).isEmpty())

        val wide = highlightMainBuffer(snapshot, listOf(rule("中文"))).getSpans(4).single()
        assertEquals(0, wide.startCell)
        assertEquals(4, wide.endCell)
        assertEquals(23, soft.revision)
    }

    @Test
    fun `priority composes merge styles and override clears lower rules`() {
        val snapshot = MainBufferSnapshot(1, emptyList(), listOf(line("ERROR")))
        val lower = rule("ERROR", foregroundRgb = 0xFF0000, bold = true)
        val merged = rule(
            "ERROR",
            priority = 1,
            backgroundRgb = 0x0000FF,
            underline = true,
        )

        val mergedStyle = highlightMainBuffer(snapshot, listOf(lower, merged)).getSpans(0).single().style
        assertEquals(0xFFFF0000.toInt(), mergedStyle.foreground!!.toColor().rgb)
        assertEquals(0xFF0000FF.toInt(), mergedStyle.background!!.toColor().rgb)
        assertTrue(mergedStyle.hasOption(TextStyle.Option.BOLD))
        assertTrue(mergedStyle.hasOption(TextStyle.Option.UNDERLINED))

        val override = merged.copy(mergeMode = HighlightMergeMode.OVERRIDE)
        val overriddenStyle = highlightMainBuffer(snapshot, listOf(lower, override)).getSpans(0).single().style
        assertNull(overriddenStyle.foreground)
        assertFalse(overriddenStyle.hasOption(TextStyle.Option.BOLD))
        assertTrue(overriddenStyle.hasOption(TextStyle.Option.UNDERLINED))
        assertTrue(highlightMainBuffer(snapshot, listOf(lower, override)).getSpans(0).single().isOverrideTerminalStyle)
    }

    @Test
    fun `RE2 rejects backreferences and ignores zero length matches`() {
        val snapshot = MainBufferSnapshot(1, emptyList(), listOf(line("aaa")))

        assertThrows(PatternSyntaxException::class.java) {
            highlightMainBuffer(snapshot, listOf(rule("(a)\\1")))
        }
        assertTrue(highlightMainBuffer(snapshot, listOf(rule("^"))).getSpans(0).isEmpty())
    }

    private fun rule(
        pattern: String,
        priority: Int = 0,
        foregroundRgb: Int? = null,
        backgroundRgb: Int? = 0xFFF176,
        bold: Boolean = false,
        underline: Boolean = false,
    ) = TerminalHighlightRule(
        name = pattern,
        pattern = pattern,
        matchCase = true,
        enabled = true,
        priority = priority,
        foregroundRgb = foregroundRgb,
        backgroundRgb = backgroundRgb,
        bold = bold,
        italic = false,
        underline = underline,
        mergeMode = HighlightMergeMode.MERGE,
    )

    private fun line(text: String, wrapped: Boolean = false): TerminalLineSnapshot =
        TerminalLineSnapshot(text, wrapped, text.isEmpty())
}
