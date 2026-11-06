package io.github.sawaichi9527.eyeshell.ui

import java.awt.Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElidedLabelTest {
    @Test
    fun `a short title is shown unchanged and has no tooltip`() {
        val label = ElidedLabel("Freelancer", 240)

        assertEquals("Freelancer", label.text)
        assertNull(label.toolTipText)
    }

    @Test
    fun `a long title is shortened and the tooltip keeps the full text`() {
        val full = "operator@a-very-long-host-name.example.internal:2222"
        val label = ElidedLabel(full, 60)
        label.font = Font(Font.DIALOG, Font.PLAIN, 12)

        assertTrue(label.text.endsWith("\u2026"), "Elided text must end with an ellipsis: ${label.text}")
        assertTrue(label.text.length < full.length)
        assertEquals(full, label.toolTipText)
    }

    @Test
    fun `the endpoint detail stays in the tooltip`() {
        val label = ElidedLabel("Freelancer", 240)
        label.toolTipDetail = "eye@192.168.23.214:22"

        assertEquals("Freelancer \u2014 eye@192.168.23.214:22", label.toolTipText)
    }

    @Test
    fun `elide trims whole characters when the text does not fit`() {
        val shortened = elide("abcdefghij", 5) { it.length }

        assertEquals("abcd\u2026", shortened)
        assertEquals("abcdefghij", elide("abcdefghij", 10) { it.length })
    }
}
