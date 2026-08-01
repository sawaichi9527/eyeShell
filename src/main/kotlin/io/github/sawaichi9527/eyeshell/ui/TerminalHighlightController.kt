package io.github.sawaichi9527.eyeshell.ui

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import io.github.sawaichi9527.eyeshell.terminal.HighlightMergeMode
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.Color
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JColorChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

internal class TerminalHighlightController(
    private val terminalView: TerminalView,
) {
    private val rules = mutableListOf<TerminalHighlightRule>()

    fun addRule(owner: Component) {
        check(SwingUtilities.isEventDispatchThread()) { "Highlight dialogs must open on the Swing EDT" }
        editRule(owner, null)?.let {
            rules += it
            publish()
        }
    }

    fun manageRules(owner: Component) {
        check(SwingUtilities.isEventDispatchThread()) { "Highlight dialogs must open on the Swing EDT" }
        val model = DefaultListModel<String>()
        val list = JList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 8
        }
        fun refresh(selected: Int = list.selectedIndex) {
            model.clear()
            rules.forEach { rule ->
                model.addElement("${if (rule.enabled) "[on]" else "[off]"} ${rule.name}  /${rule.pattern}/  priority ${rule.priority}")
            }
            if (model.size > 0) list.selectedIndex = selected.coerceIn(0, model.size - 1)
        }
        refresh()
        val content = JPanel(GridBagLayout()).apply {
            add(JLabel("Current Session rules"), constraints(0, 0))
            add(JScrollPane(list), constraints(0, 1, fill = GridBagConstraints.BOTH, weightY = 1.0))
        }
        while (true) {
            when (JOptionPane.showOptionDialog(
                owner,
                content,
                "Manage color highlight rules",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                arrayOf("Add", "Edit", "Remove", "Close"),
                "Close",
            )) {
                0 -> editRule(owner, null)?.let {
                    rules += it
                    publish()
                    refresh(rules.lastIndex)
                }
                1 -> {
                    val index = list.selectedIndex
                    if (index >= 0) editRule(owner, rules[index])?.let {
                        rules[index] = it
                        publish()
                        refresh(index)
                    }
                }
                2 -> {
                    val index = list.selectedIndex
                    if (index >= 0 && JOptionPane.showConfirmDialog(
                            owner,
                            "Remove '${rules[index].name}'?",
                            "Remove highlight rule",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                        ) == JOptionPane.YES_OPTION
                    ) {
                        rules.removeAt(index)
                        publish()
                        refresh(index)
                    }
                }
                else -> return
            }
        }
    }

    private fun editRule(owner: Component, existing: TerminalHighlightRule?): TerminalHighlightRule? {
        val name = JTextField(existing?.name ?: "Rule ${rules.size + 1}", 24)
        val pattern = JTextField(existing?.pattern ?: "", 24)
        val matchCase = JCheckBox("Match case", existing?.matchCase ?: false)
        val enabled = JCheckBox("Enabled", existing?.enabled ?: true)
        val priority = JSpinner(SpinnerNumberModel(existing?.priority ?: 0, -10_000, 10_000, 1))
        val foreground = OptionalColorField("Foreground", existing?.foregroundRgb?.toAwtColor())
        val background = OptionalColorField(
            "Background",
            existing?.backgroundRgb?.toAwtColor() ?: DEFAULT_BACKGROUND,
            useDefault = existing != null && existing.backgroundRgb == null,
        )
        val bold = JCheckBox("Bold", existing?.bold ?: false)
        val italic = JCheckBox("Italic", existing?.italic ?: false)
        val underline = JCheckBox("Underline", existing?.underline ?: false)
        val mergeMode = JComboBox(HighlightMergeMode.entries.toTypedArray()).apply {
            selectedItem = existing?.mergeMode ?: HighlightMergeMode.MERGE
        }
        val form = JPanel(GridBagLayout()).apply {
            var row = 0
            addRow(row++, "Name", name)
            addRow(row++, "Regex", pattern)
            addRow(row++, "Priority", priority)
            addRow(row++, "Mode", mergeMode)
            addRow(row++, "", matchCase)
            addRow(row++, "", enabled)
            addRow(row++, "", foreground)
            addRow(row++, "", background)
            addRow(row, "Style", JPanel().apply {
                add(bold)
                add(italic)
                add(underline)
            })
        }

        while (JOptionPane.showConfirmDialog(
                owner,
                form,
                if (existing == null) "Add color highlight rule" else "Edit color highlight rule",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            ) == JOptionPane.OK_OPTION
        ) {
            val ruleName = name.text.trim()
            val regex = pattern.text
            val failure = when {
                ruleName.isEmpty() -> "Rule name must not be empty."
                regex.isEmpty() -> "Regex must not be empty."
                else -> try {
                    Pattern.compile(regex)
                    null
                } catch (error: PatternSyntaxException) {
                    error.message ?: "Invalid RE2-compatible regex."
                }
            }
            if (failure != null) {
                JOptionPane.showMessageDialog(owner, failure, "Invalid highlight rule", JOptionPane.ERROR_MESSAGE)
                continue
            }
            return TerminalHighlightRule(
                name = ruleName,
                pattern = regex,
                matchCase = matchCase.isSelected,
                enabled = enabled.isSelected,
                priority = priority.value as Int,
                foregroundRgb = foreground.rgb,
                backgroundRgb = background.rgb,
                bold = bold.isSelected,
                italic = italic.isSelected,
                underline = underline.isSelected,
                mergeMode = mergeMode.selectedItem as HighlightMergeMode,
            )
        }
        return null
    }

    private fun publish() = terminalView.setHighlightRules(rules)

    private class OptionalColorField(
        label: String,
        initial: Color?,
        useDefault: Boolean = initial == null,
    ) : JPanel() {
        private var selectedColor = initial ?: Color.WHITE
        private val choose = JButton(label).apply {
            background = selectedColor
            foreground = contrastingText(selectedColor)
        }
        private val terminalDefault = JCheckBox("Keep terminal color", useDefault)

        val rgb: Int?
            get() = if (terminalDefault.isSelected) null else selectedColor.rgb and 0xFFFFFF

        init {
            choose.isEnabled = !terminalDefault.isSelected
            choose.addActionListener {
                JColorChooser.showDialog(this, "Choose $label color", selectedColor)?.let { color ->
                    selectedColor = color
                    choose.background = color
                    choose.foreground = contrastingText(color)
                }
            }
            terminalDefault.addActionListener { choose.isEnabled = !terminalDefault.isSelected }
            add(choose)
            add(terminalDefault)
        }

        companion object {
            private fun contrastingText(color: Color): Color =
                if (color.red * 299 + color.green * 587 + color.blue * 114 >= 128_000) Color.BLACK else Color.WHITE
        }
    }

    companion object {
        private val DEFAULT_BACKGROUND = Color(0xFF, 0xF1, 0x76)

        private fun JPanel.addRow(row: Int, label: String, component: Component) {
            if (label.isNotEmpty()) add(JLabel(label), constraints(0, row))
            add(component, constraints(1, row, fill = GridBagConstraints.HORIZONTAL, weightX = 1.0))
        }

        private fun constraints(
            x: Int,
            y: Int,
            fill: Int = GridBagConstraints.NONE,
            weightX: Double = 0.0,
            weightY: Double = 0.0,
        ) = GridBagConstraints().apply {
            gridx = x
            gridy = y
            this.fill = fill
            this.weightx = weightX
            this.weighty = weightY
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
        }
    }
}

private fun Int.toAwtColor(): Color = Color(this)
