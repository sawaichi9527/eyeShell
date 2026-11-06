package io.github.sawaichi9527.eyeshell.ui

import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortFieldTest {
    @Test
    fun `port field aligns its value like the surrounding text fields`() {
        val spinner = createPortField(22)
        val editor = spinner.editor as JSpinner.DefaultEditor

        assertEquals(SwingConstants.LEFT, editor.textField.horizontalAlignment)

        spinner.value = 2222
        assertEquals("2222", editor.textField.text)
        assertEquals(SwingConstants.LEFT, editor.textField.horizontalAlignment)
    }

    @Test
    fun `port field stays left aligned when the look and feel re-applies the orientation`() {
        val spinner = createPortField(22)
        javax.swing.SwingUtilities.updateComponentTreeUI(spinner)
        // JSpinner.NumberEditor forces the alignment back to the right on every orientation change.
        spinner.applyComponentOrientation(java.awt.ComponentOrientation.LEFT_TO_RIGHT)

        val editor = spinner.editor as JSpinner.DefaultEditor
        assertEquals(SwingConstants.LEFT, editor.textField.horizontalAlignment)
        assertEquals("22", editor.textField.text)
    }

    @Test
    fun `port field keeps the valid SSH port range`() {
        val model = createPortField(2222).model as SpinnerNumberModel

        assertEquals(2222, model.value)
        assertEquals(1, model.minimum)
        assertEquals(65535, model.maximum)
    }
}
