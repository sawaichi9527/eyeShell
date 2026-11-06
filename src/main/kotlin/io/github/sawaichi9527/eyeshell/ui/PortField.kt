package io.github.sawaichi9527.eyeshell.ui

import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants

/**
 * Builds the port selector used by the connection dialogs.
 *
 * Swing's numeric spinner editor right-aligns its value and groups thousands by default, so a short
 * value such as `22` sits against the arrow buttons and reads like an empty field, while `2222`
 * shows as `2,222`. Aligning it left with a plain integer format keeps the field consistent with the
 * left-aligned text fields around it.
 */
internal fun createPortField(initialPort: Int): JSpinner =
    JSpinner(SpinnerNumberModel(initialPort, 1, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "0").apply {
            textField.horizontalAlignment = SwingConstants.LEFT
        }
    }
