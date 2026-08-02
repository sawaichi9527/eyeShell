package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.storage.HostCatalog
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
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

internal class HostCatalogController(
    private val catalog: HostCatalog,
    private val connect: (EyeShellWindow, HostConnectionPreset) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("host-catalog-", 0).factory())
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private val activeDialog = AtomicReference<JDialog>()

    fun open(owner: EyeShellWindow) {
        check(SwingUtilities.isEventDispatchThread()) { "Host catalog must open on the Swing EDT" }
        if (closed.get()) return
        activeDialog.get()?.takeIf(JDialog::isDisplayable)?.let {
            it.toFront()
            return
        }
        val state = createDialog(owner)
        activeDialog.set(state.dialog)
        state.dialog.isVisible = true
        refresh(state)
    }

    internal fun loadHosts(onLoaded: (List<SavedHost>) -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        submit(
            operation = catalog::listHosts,
            success = onLoaded,
            failure = {},
        )
    }

    private fun createDialog(owner: EyeShellWindow): DialogState {
        val model = DefaultListModel<SavedHost>()
        val list = JList(model).apply {
            name = "hostCatalogList"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    if (value is SavedHost) {
                        val group = value.draft.group?.let { "$it / " }.orEmpty()
                        val tags = value.draft.tags.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "  [", postfix = "]").orEmpty()
                        (this as JLabel).text = "$group${value.draft.name}  ${value.draft.endpoint.displayName}$tags"
                    }
                }
            }
        }
        val status = JLabel("Loading...").apply { name = "hostCatalogStatus" }
        val add = JButton("Add...")
        val edit = JButton("Edit...").apply { isEnabled = false }
        val delete = JButton("Delete").apply { isEnabled = false }
        val connectButton = JButton("Connect").apply { isEnabled = false }
        val close = JButton("Close")
        val dialog = JDialog(owner, "Saved hosts", false).apply {
            name = "hostCatalogDialog"
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)
                add(JScrollPane(list), BorderLayout.CENTER)
                add(status, BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
                    add(add)
                    add(edit)
                    add(delete)
                    add(connectButton)
                    add(close)
                }, BorderLayout.SOUTH)
            }
            setSize(760, 420)
            setLocationRelativeTo(owner)
        }
        val state = DialogState(dialog, model, list, status, listOf(add, edit, delete, connectButton))
        fun updateSelectionActions() {
            val selected = list.selectedValue != null && state.busy.not()
            edit.isEnabled = selected
            delete.isEnabled = selected
            connectButton.isEnabled = selected
        }
        list.addListSelectionListener { updateSelectionActions() }
        add.addActionListener {
            showHostEditor(dialog, null)?.let { draft ->
                mutate(state) { catalog.createHost(draft) }
            }
        }
        edit.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            showHostEditor(dialog, selected)?.let { draft ->
                mutate(state) { catalog.updateHost(selected.id, draft) }
            }
        }
        delete.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            if (JOptionPane.showConfirmDialog(
                    dialog,
                    "Delete '${selected.draft.name}'?",
                    "Delete saved host",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                ) == JOptionPane.YES_OPTION
            ) {
                mutate(state) { catalog.deleteHost(selected.id) }
            }
        }
        connectButton.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            if (!owner.canAttachTerminal) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Close the active terminal before opening another connection.",
                    "Terminal already connected",
                    JOptionPane.INFORMATION_MESSAGE,
                )
                return@addActionListener
            }
            dialog.dispose()
            connect(owner, selected.toPreset())
        }
        close.addActionListener { dialog.dispose() }
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                generation.incrementAndGet()
                activeDialog.compareAndSet(dialog, null)
            }
        })
        return state
    }

    private fun refresh(state: DialogState) {
        setBusy(state, true, "Loading...")
        submit(
            operation = catalog::listHosts,
            success = { hosts ->
                if (!state.dialog.isDisplayable) return@submit
                state.model.clear()
                hosts.forEach(state.model::addElement)
                setBusy(state, false, if (hosts.isEmpty()) "No saved hosts." else "${hosts.size} saved hosts")
            },
            failure = { showCatalogFailure(state) },
        )
    }

    private fun mutate(state: DialogState, operation: () -> Unit) {
        setBusy(state, true, "Saving...")
        submit(
            operation = {
                operation()
                catalog.listHosts()
            },
            success = { hosts ->
                if (!state.dialog.isDisplayable) return@submit
                state.model.clear()
                hosts.forEach(state.model::addElement)
                setBusy(state, false, if (hosts.isEmpty()) "No saved hosts." else "${hosts.size} saved hosts")
            },
            failure = { showCatalogFailure(state) },
        )
    }

    private fun <T> submit(
        operation: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
    ) {
        if (closed.get()) return
        val request = generation.incrementAndGet()
        try {
            executor.execute {
                val result = try {
                    Result.success(operation())
                } catch (error: Exception) {
                    Result.failure(error)
                }
                SwingUtilities.invokeLater {
                    if (closed.get() || generation.get() != request) return@invokeLater
                    result.fold(success, failure)
                }
            }
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    private fun setBusy(state: DialogState, busy: Boolean, message: String) {
        state.busy = busy
        state.status.text = message
        state.buttons.forEach { it.isEnabled = !busy }
        if (!busy) {
            val selected = state.list.selectedValue != null
            state.buttons.drop(1).forEach { it.isEnabled = selected }
        }
    }

    private fun showCatalogFailure(state: DialogState) {
        if (!state.dialog.isDisplayable) return
        setBusy(state, false, "Host catalog unavailable")
        JOptionPane.showMessageDialog(
            state.dialog,
            "Could not access the local host catalog.",
            "Host catalog error",
            JOptionPane.ERROR_MESSAGE,
        )
    }

    private fun showHostEditor(owner: Component, existing: SavedHost?): HostDraft? {
        val name = JTextField(existing?.draft?.name.orEmpty(), 24)
        val host = JTextField(existing?.draft?.endpoint?.host.orEmpty(), 24)
        val port = JSpinner(SpinnerNumberModel(existing?.draft?.endpoint?.port ?: 22, 1, 65535, 1))
        val username = JTextField(existing?.draft?.endpoint?.username.orEmpty(), 24)
        val authentication = JComboBox(ConnectionAuthenticationMethod.entries.toTypedArray()).apply {
            selectedItem = existing?.draft?.authenticationMethod?.toConnectionMethod()
                ?: ConnectionAuthenticationMethod.PASSWORD
        }
        val group = JTextField(existing?.draft?.group.orEmpty(), 24)
        val tags = JTextField(existing?.draft?.tags?.joinToString(", ").orEmpty(), 24)
        val panel = JPanel(GridBagLayout()).apply {
            addField(0, "Name", name)
            addField(1, "Host", host)
            addField(2, "Port", port)
            addField(3, "Username", username)
            addField(4, "Authentication", authentication)
            addField(5, "Group", group)
            addField(6, "Tags", tags)
        }
        while (JOptionPane.showConfirmDialog(
                owner,
                panel,
                if (existing == null) "Add saved host" else "Edit saved host",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            ) == JOptionPane.OK_OPTION
        ) {
            try {
                return HostDraft(
                    name = name.text,
                    endpoint = SshEndpoint(host.text.trim(), port.value as Int, username.text.trim()),
                    authenticationMethod = (authentication.selectedItem as ConnectionAuthenticationMethod).toSavedMethod(),
                    group = group.text.trim().takeIf(String::isNotEmpty),
                    tags = tags.text.split(',').map(String::trim).filter(String::isNotEmpty),
                ).normalized()
            } catch (failure: IllegalArgumentException) {
                JOptionPane.showMessageDialog(
                    owner,
                    failure.message,
                    "Invalid saved host",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
        return null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        SwingUtilities.invokeLater { activeDialog.getAndSet(null)?.dispose() }
        try {
            executor.execute(catalog::close)
        } catch (_: RejectedExecutionException) {
            catalog.close()
        }
        executor.shutdown()
    }

    private data class DialogState(
        val dialog: JDialog,
        val model: DefaultListModel<SavedHost>,
        val list: JList<SavedHost>,
        val status: JLabel,
        val buttons: List<JButton>,
        var busy: Boolean = false,
    )

    companion object {
        private fun JPanel.addField(row: Int, label: String, field: Component) {
            add(JLabel(label), GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.LINE_END
                insets = Insets(5, 5, 5, 10)
            })
            add(field, GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(5, 0, 5, 5)
            })
        }
    }
}

internal fun SavedHost.toPreset(): HostConnectionPreset = HostConnectionPreset(
    endpoint = draft.endpoint,
    authenticationMethod = draft.authenticationMethod.toConnectionMethod(),
)

private fun SavedAuthenticationMethod.toConnectionMethod(): ConnectionAuthenticationMethod =
    ConnectionAuthenticationMethod.valueOf(name)

private fun ConnectionAuthenticationMethod.toSavedMethod(): SavedAuthenticationMethod =
    SavedAuthenticationMethod.valueOf(name)
