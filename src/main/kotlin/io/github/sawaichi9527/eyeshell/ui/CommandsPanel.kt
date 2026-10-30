package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.storage.CommandSnippet
import io.github.sawaichi9527.eyeshell.storage.CommandSnippetDraft
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import io.github.sawaichi9527.eyeshell.platform.I18n
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel

class CommandsPanel : JPanel(BorderLayout()) {    private val model = DefaultListModel<CommandSnippet>()
    private val list = JList(model).apply {
        name = "commandSnippetList"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                if (value is CommandSnippet) text = value.name
            }
        }
    }
    private val preview = JTextArea().apply {
        name = "commandSnippetPreview"
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }
    private val addButton = JButton(I18n.t("commands.add"))
    private val editButton = JButton(I18n.t("commands.edit")).apply { isEnabled = false }
    private val deleteButton = JButton(I18n.t("commands.delete")).apply { isEnabled = false }
    private val runButton = JButton(I18n.t("commands.run")).apply {
        name = "runCommandButton"
        isEnabled = false
    }
    private val status = JLabel(I18n.t("commands.noSaved")).apply { name = "commandSnippetStatus" }
    private var controller: CommandsController? = null
    private var sendCommand: ((String) -> Unit)? = null

    init {
        name = "commandsPanel"
        getAccessibleContext().accessibleName = I18n.t("commands.panel.accessible")
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        list.addListSelectionListener { updateSelectionState() }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2) runSelected()
            }
        })
        add(JPanel(BorderLayout()).apply {
            add(JScrollPane(list).apply { preferredSize = Dimension(240, 0) }, BorderLayout.WEST)
            add(JScrollPane(preview).apply {
                border = BorderFactory.createTitledBorder(I18n.t("commands.previewTitle"))
            }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
                add(addButton)
                add(editButton)
                add(deleteButton)
                add(runButton)
            }, BorderLayout.EAST)
        }.apply { border = BorderFactory.createEmptyBorder(6, 0, 0, 0) }, BorderLayout.SOUTH)

        addButton.addActionListener { addSnippet() }
        editButton.addActionListener { editSnippet() }
        deleteButton.addActionListener { deleteSnippet() }
        runButton.addActionListener { runSelected() }
    }

    fun bind(controller: CommandsController?) {
        this.controller = controller
        if (controller == null) {
            model.clear()
            status.text = I18n.t("commands.noSaved")
            updateSelectionState()
            return
        }
        status.text = I18n.t("commands.loading")
        controller.load { result ->
            result.onSuccess(::render)
                .onFailure { failure ->
                    status.text = I18n.t("commands.loadFailed")
                    JOptionPane.showMessageDialog(
                        this,
                        failure.message,
                        I18n.t("commands.unavailable.title"),
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
        }
    }

    /** Re-applies localized texts after the language changed. */
    fun relocalize() {
        addButton.text = I18n.t("commands.add")
        editButton.text = I18n.t("commands.edit")
        deleteButton.text = I18n.t("commands.delete")
        runButton.text = I18n.t("commands.run")
        getAccessibleContext().accessibleName = I18n.t("commands.panel.accessible")
        (preview.parent?.parent as? JScrollPane)?.border = BorderFactory.createTitledBorder(I18n.t("commands.previewTitle"))
        status.text = if (model.isEmpty) I18n.t("commands.noSaved") else I18n.t("commands.count", model.size())
    }

    fun bindSession(sender: ((String) -> Unit)?) {
        sendCommand = sender
        updateSelectionState()
    }

    internal fun runSelected(): Boolean {
        val snippet = list.selectedValue ?: return false
        val sender = sendCommand
        if (sender == null) {
            status.text = I18n.t("commands.connectToRun")
            return false
        }
        sender(snippet.command)
        status.text = I18n.t("commands.sent", snippet.name)
        return true
    }

    internal fun snippetNames(): List<String> = (0 until model.size()).map { model.get(it).name }

    private fun render(snippets: List<CommandSnippet>) {
        val selectedName = list.selectedValue?.name
        model.clear()
        snippets.forEach(model::addElement)
        status.text = if (snippets.isEmpty()) I18n.t("commands.noSaved") else I18n.t("commands.count", snippets.size)
        if (selectedName != null) {
            (0 until model.size()).firstOrNull { model.get(it).name == selectedName }
                ?.let { list.selectedIndex = it }
        }
        updateSelectionState()
    }

    private fun updateSelectionState() {
        val selected = list.selectedValue != null
        editButton.isEnabled = selected
        deleteButton.isEnabled = selected
        runButton.isEnabled = selected && sendCommand != null
        preview.text = list.selectedValue?.command.orEmpty()
    }

    private fun addSnippet() {
        val active = controller ?: return
        val draft = showEditor(null) ?: return
        active.create(draft) { result ->
            result.onSuccess { reload(active) }
                .onFailure { showFailure(it, I18n.t("commands.saveFailed")) }
        }
    }

    private fun editSnippet() {
        val active = controller ?: return
        val snippet = list.selectedValue ?: return
        val draft = showEditor(snippet) ?: return
        active.update(snippet.id, draft) { result ->
            result.onSuccess { reload(active) }
                .onFailure { showFailure(it, I18n.t("commands.saveFailed")) }
        }
    }

    private fun deleteSnippet() {
        val active = controller ?: return
        val snippet = list.selectedValue ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                I18n.t("commands.delete.message", snippet.name),
                I18n.t("commands.delete.title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) != JOptionPane.YES_OPTION
        ) {
            return
        }
        active.delete(snippet.id) { result ->
            result.onSuccess { reload(active) }
                .onFailure { showFailure(it, I18n.t("commands.delete.failed")) }
        }
    }

    private fun reload(active: CommandsController) {
        active.load { result ->
            result.onSuccess(::render)
                .onFailure { showFailure(it, I18n.t("commands.loadFailed")) }
        }
    }

    private fun showFailure(failure: Throwable, title: String) {
        JOptionPane.showMessageDialog(this, failure.message, title, JOptionPane.ERROR_MESSAGE)
    }

    private fun showEditor(existing: CommandSnippet?): CommandSnippetDraft? {
        val name = JTextField(existing?.name.orEmpty(), 28)
        val command = JTextArea(existing?.command.orEmpty(), 4, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val panel = JPanel(BorderLayout(6, 6)).apply {
            add(JPanel(BorderLayout()).apply {
                add(JLabel(I18n.t("commands.name")), BorderLayout.WEST)
                add(name, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JScrollPane(command), BorderLayout.CENTER)
        }
        while (JOptionPane.showConfirmDialog(
                this,
                panel,
                if (existing == null) I18n.t("commands.add.title") else I18n.t("commands.edit.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            ) == JOptionPane.OK_OPTION
        ) {
            try {
                return CommandSnippetDraft(name.text, command.text)
            } catch (failure: IllegalArgumentException) {
                JOptionPane.showMessageDialog(this, failure.message, I18n.t("commands.invalid"), JOptionPane.ERROR_MESSAGE)
            }
        }
        return null
    }
}
