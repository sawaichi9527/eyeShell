package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.storage.CommandSnippet
import io.github.sawaichi9527.eyeshell.storage.CommandSnippetDraft
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
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
    private val addButton = JButton("Add...")
    private val editButton = JButton("Edit...").apply { isEnabled = false }
    private val deleteButton = JButton("Delete").apply { isEnabled = false }
    private val runButton = JButton("Run").apply {
        name = "runCommandButton"
        isEnabled = false
    }
    private val status = JLabel("No saved commands.").apply { name = "commandSnippetStatus" }
    private var controller: CommandsController? = null
    private var sendCommand: ((String) -> Unit)? = null

    init {
        name = "commandsPanel"
        getAccessibleContext().accessibleName = "Saved commands"
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
                border = BorderFactory.createTitledBorder("Command")
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
            status.text = "No saved commands."
            updateSelectionState()
            return
        }
        status.text = "Loading..."
        controller.load { result ->
            result.onSuccess(::render)
                .onFailure { failure ->
                    status.text = "Could not load saved commands."
                    JOptionPane.showMessageDialog(
                        this,
                        failure.message,
                        "Command snippets unavailable",
                        JOptionPane.ERROR_MESSAGE,
                    )
                }
        }
    }

    fun bindSession(sender: ((String) -> Unit)?) {
        sendCommand = sender
        updateSelectionState()
    }

    internal fun runSelected(): Boolean {
        val snippet = list.selectedValue ?: return false
        val sender = sendCommand
        if (sender == null) {
            status.text = "Connect a session to run a command."
            return false
        }
        sender(snippet.command)
        status.text = "Sent \"${snippet.name}\" to the active session."
        return true
    }

    internal fun snippetNames(): List<String> = (0 until model.size()).map { model.get(it).name }

    private fun render(snippets: List<CommandSnippet>) {
        val selectedName = list.selectedValue?.name
        model.clear()
        snippets.forEach(model::addElement)
        status.text = if (snippets.isEmpty()) "No saved commands." else "${snippets.size} saved commands"
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
                .onFailure { showFailure(it, "Could not save command") }
        }
    }

    private fun editSnippet() {
        val active = controller ?: return
        val snippet = list.selectedValue ?: return
        val draft = showEditor(snippet) ?: return
        active.update(snippet.id, draft) { result ->
            result.onSuccess { reload(active) }
                .onFailure { showFailure(it, "Could not save command") }
        }
    }

    private fun deleteSnippet() {
        val active = controller ?: return
        val snippet = list.selectedValue ?: return
        if (JOptionPane.showConfirmDialog(
                this,
                "Delete '${snippet.name}'?",
                "Delete saved command",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) != JOptionPane.YES_OPTION
        ) {
            return
        }
        active.delete(snippet.id) { result ->
            result.onSuccess { reload(active) }
                .onFailure { showFailure(it, "Could not delete command") }
        }
    }

    private fun reload(active: CommandsController) {
        active.load { result ->
            result.onSuccess(::render)
                .onFailure { showFailure(it, "Could not load saved commands") }
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
                add(JLabel("Name"), BorderLayout.WEST)
                add(name, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JScrollPane(command), BorderLayout.CENTER)
        }
        while (JOptionPane.showConfirmDialog(
                this,
                panel,
                if (existing == null) "Add saved command" else "Edit saved command",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            ) == JOptionPane.OK_OPTION
        ) {
            try {
                return CommandSnippetDraft(name.text, command.text)
            } catch (failure: IllegalArgumentException) {
                JOptionPane.showMessageDialog(this, failure.message, "Invalid command", JOptionPane.ERROR_MESSAGE)
            }
        }
        return null
    }
}
