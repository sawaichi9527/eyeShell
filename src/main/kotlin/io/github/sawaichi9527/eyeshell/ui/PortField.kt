package io.github.sawaichi9527.eyeshell.ui

import java.beans.PropertyChangeListener
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

/**
 * Builds the port selector used by the connection dialogs.
 *
 * Swing's numeric spinner editor right-aligns its value and groups thousands by default, so a short
 * value such as `22` sits against the arrow buttons and reads like an empty field, while `2222`
 * shows as `2,222`. `JSpinner.NumberEditor.setComponentOrientation` also forces the alignment back
 * to the right whenever the dialog applies a component orientation, so the left alignment is
 * re-applied on every change instead of being set once.
 */
internal fun createPortField(initialPort: Int): JSpinner {
    val spinner = JSpinner(SpinnerNumberModel(initialPort, 1, 65535, 1))
    val editor = JSpinner.NumberEditor(spinner, "0")
    spinner.editor = editor
    val textField = editor.textField
    val keepLeftAligned = PropertyChangeListener { event ->
        if (event.newValue != SwingConstants.LEFT) {
            textField.horizontalAlignment = SwingConstants.LEFT
        }
    }
    textField.horizontalAlignment = SwingConstants.LEFT
    textField.addPropertyChangeListener("horizontalAlignment", keepLeftAligned)
    textField.addPropertyChangeListener("UI", keepLeftAligned)
    return spinner
}
