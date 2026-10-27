package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.UIManager

/**
 * Settings entry point reachable from the gear button on the Monitor sidebar.
 *
 * Only controls that are actually implemented are enabled. Terminal font size is applied to every
 * open session immediately and persisted; the language row is shown disabled with an explicit hint
 * so the dialog does not imply a feature that does not exist yet.
 */
internal class SettingsController(
    private val settings: AppSettings,
    private val applyFontSize: (Float) -> Unit,
) {
    private val activeDialog = AtomicReference<JDialog>()

    fun open(owner: Frame) {
        activeDialog.get()?.takeIf(JDialog::isDisplayable)?.let { dialog ->
            dialog.toFront()
            dialog.requestFocus()
            return
        }
        val dialog = createDialog(owner)
        activeDialog.set(dialog)
        dialog.isVisible = true
    }

    fun close() {
        activeDialog.getAndSet(null)?.dispose()
    }

    internal fun createDialog(owner: Frame): JDialog {
        val dialog = JDialog(owner, "Settings", false).apply { name = "settingsDialog" }
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val body = JPanel(GridBagLayout()).apply {
            name = "settingsBody"
            border = BorderFactory.createEmptyBorder(14, 16, 6, 16)
        }
        var row = 0

        row = addHeading(body, row, "Terminal")
        val fontSpinner = JSpinner(
            SpinnerNumberModel(
                settings.terminalFontSize,
                AppSettings.MIN_TERMINAL_FONT_SIZE,
                AppSettings.MAX_TERMINAL_FONT_SIZE,
                1,
            ),
        ).apply {
            name = "settingsFontSize"
            toolTipText = "Terminal font size in points"
        }
        fontSpinner.addChangeListener {
            val size = (fontSpinner.value as Number).toInt()
            if (size != settings.terminalFontSize) {
                settings.terminalFontSize = size
                applyFontSize(size.toFloat())
            }
        }
        row = addRow(body, row, "Font size", fontSpinner, "pt · applies to every open session")

        row = addHeading(body, row, "Language")
        val language = JComboBox(arrayOf("English")).apply {
            name = "settingsLanguage"
            isEnabled = false
        }
        row = addRow(body, row, "Language", language, "More languages are not available yet")

        row = addHeading(body, row, "About")
        row = addRow(body, row, "Version", JLabel(AppInfo.windowTitle), null)
        row = addRow(body, row, "Java", JLabel(System.getProperty("java.version").orEmpty()), null)
        addRow(
            body,
            row,
            "System",
            JLabel("${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.arch").orEmpty()}"),
            null,
        )

        val close = JButton("Close").apply {
            name = "settingsClose"
            addActionListener { dialog.dispose() }
        }
        val footer = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 16, 12, 16)
            add(close, BorderLayout.EAST)
        }

        dialog.contentPane.add(body, BorderLayout.CENTER)
        dialog.contentPane.add(footer, BorderLayout.SOUTH)
        dialog.pack()
        dialog.minimumSize = dialog.size
        dialog.setLocationRelativeTo(owner)
        return dialog
    }

    private fun addHeading(panel: JPanel, row: Int, text: String): Int {
        panel.add(
            JLabel(text).apply {
                font = font.deriveFont(java.awt.Font.BOLD)
            },
            constraints(column = 0, row = row, width = 2, insets = Insets(10, 0, 6, 0)),
        )
        return row + 1
    }

    private fun addRow(
        panel: JPanel,
        row: Int,
        label: String,
        field: JComponent,
        hint: String?,
    ): Int {
        panel.add(
            JLabel(label, SwingConstants.LEFT).apply { foreground = mutedColor() },
            constraints(column = 0, row = row, width = 1, insets = Insets(0, 0, 6, 12)),
        )
        val holder = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(field, BorderLayout.WEST)
        }
        if (hint != null) {
            holder.add(
                JLabel(hint).apply {
                    foreground = mutedColor()
                    font = font.deriveFont(font.size2D - 1f)
                },
                BorderLayout.CENTER,
            )
        }
        panel.add(
            holder,
            constraints(column = 1, row = row, width = 1, insets = Insets(0, 0, 6, 0), weightx = 1.0),
        )
        return row + 1
    }

    private fun constraints(
        column: Int,
        row: Int,
        width: Int,
        insets: Insets,
        weightx: Double = 0.0,
    ): GridBagConstraints = GridBagConstraints().apply {
        gridx = column
        gridy = row
        gridwidth = width
        this.weightx = weightx
        this.insets = insets
        anchor = GridBagConstraints.WEST
        fill = GridBagConstraints.HORIZONTAL
    }

    private fun mutedColor(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
}
