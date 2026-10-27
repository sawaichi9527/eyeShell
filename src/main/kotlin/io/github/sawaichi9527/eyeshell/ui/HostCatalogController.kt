package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreStatus
import io.github.sawaichi9527.eyeshell.secrets.PasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.secrets.SessionPasswordCache
import io.github.sawaichi9527.eyeshell.storage.HostCatalog
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Path2D
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer

internal class HostCatalogController(
    private val catalog: HostCatalog,
    private val passwordStore: PasswordCredentialStore,
    private val credentialGuard: ProfileCredentialGuard = ProfileCredentialGuard(),
    private val sessionPasswords: SessionPasswordCache = SessionPasswordCache(),
    private val connect: (EyeShellWindow, HostConnectionPreset) -> Boolean,
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

    internal fun updateHost(existing: SavedHost, draft: HostDraft): SavedHost {
        if (existing.draft.authenticationMethod == SavedAuthenticationMethod.PASSWORD &&
            draft.authenticationMethod != SavedAuthenticationMethod.PASSWORD
        ) {
            return credentialGuard.invalidate(existing.profileId) {
                withoutCredential(existing.profileId) { catalog.updateHost(existing.id, draft) }
            }.also { sessionPasswords.invalidate(existing.profileId) }
        }
        if (existing.draft.authenticationMethod != SavedAuthenticationMethod.PASSWORD &&
            draft.authenticationMethod == SavedAuthenticationMethod.PASSWORD
        ) {
            return credentialGuard.activate(existing.profileId) { catalog.updateHost(existing.id, draft) }
        }
        return catalog.updateHost(existing.id, draft)
    }

    internal fun deleteHost(host: SavedHost) {
        if (host.draft.authenticationMethod == SavedAuthenticationMethod.PASSWORD) {
            credentialGuard.invalidate(host.profileId) {
                withoutCredential(host.profileId) { catalog.deleteHost(host.id) }
            }
        } else {
            credentialGuard.invalidate(host.profileId) { catalog.deleteHost(host.id) }
        }
        sessionPasswords.invalidate(host.profileId)
    }

    private fun <T> withoutCredential(profileId: java.util.UUID, mutation: () -> T): T {
        val storedPassword = passwordStore.retrieve(profileId)
        return try {
            try {
                passwordStore.forget(profileId)
                mutation()
            } catch (failure: Exception) {
                try {
                    storedPassword?.copyValue()?.let { password ->
                        try {
                            passwordStore.save(profileId, password)
                        } finally {
                            password.fill('\u0000')
                        }
                    }
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                    throw CredentialRollbackException(failure)
                }
                throw failure
            }
        } finally {
            storedPassword?.close()
        }
    }

    private class CredentialRollbackException(cause: Exception) : Exception(
        "The profile was unchanged, but its saved password could not be restored.",
        cause,
    )

    private fun createDialog(owner: EyeShellWindow): DialogState {
        val model = HostTableModel()
        val table = JTable(model).apply {
            name = "hostCatalogTable"
            getAccessibleContext().accessibleName = "Saved hosts"
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
            rowHeight = 24
            fillsViewportHeight = true
            tableHeader.reorderingAllowed = false
            setDefaultRenderer(Any::class.java, HostTableRenderer())
            columnModel.getColumn(HostTableModel.NAME_COLUMN).preferredWidth = 200
            columnModel.getColumn(HostTableModel.HOST_COLUMN).preferredWidth = 170
            columnModel.getColumn(HostTableModel.PORT_COLUMN).preferredWidth = 55
            columnModel.getColumn(HostTableModel.USER_COLUMN).preferredWidth = 110
            columnModel.getColumn(HostTableModel.AUTH_COLUMN).preferredWidth = 130
            columnModel.getColumn(HostTableModel.GROUP_COLUMN).preferredWidth = 110
        }
        val search = JTextField().apply {
            name = "hostCatalogSearch"
            putClientProperty("JTextField.placeholderText", "Filter by name, host, user, or group")
            toolTipText = "Filter saved hosts"
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
                add(search, BorderLayout.NORTH)
                add(JScrollPane(table).apply { name = "hostCatalogScroll" }, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply {
                    add(status, BorderLayout.WEST)
                    add(JPanel(FlowLayout(FlowLayout.TRAILING, 6, 0)).apply {
                        add(add)
                        add(edit)
                        add(delete)
                        add(connectButton)
                        add(close)
                    }, BorderLayout.EAST)
                }, BorderLayout.SOUTH)
            }
            setSize(840, 460)
            setLocationRelativeTo(owner)
        }
        val state = DialogState(dialog, table, model, search, status, listOf(add, edit, delete, connectButton))
        fun updateSelectionActions() {
            val selected = selectedHost(state) != null && state.busy.not()
            edit.isEnabled = selected
            delete.isEnabled = selected
            connectButton.isEnabled = selected
        }
        table.selectionModel.addListSelectionListener { updateSelectionActions() }
        fun connectHost(host: SavedHost, closeOnStart: Boolean) {
            val hosts = currentHosts(state)
            val jumpProfileId = host.draft.jumpHostProfileId
            val jumpHost = jumpProfileId?.let { profileId -> hosts.firstOrNull { it.profileId == profileId } }
            if (jumpProfileId != null && jumpHost == null) {
                JOptionPane.showMessageDialog(
                    dialog,
                    "The selected jump host no longer exists. Edit '${host.draft.name}' to choose another jump host.",
                    "Jump host unavailable",
                    JOptionPane.ERROR_MESSAGE,
                )
                return
            }
            if (connect(owner, host.toPreset(jumpHost))) {
                if (closeOnStart) dialog.dispose()
            } else {
                JOptionPane.showMessageDialog(
                    dialog,
                    "Another SSH connection attempt is already in progress.",
                    "Connection in progress",
                    JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val rowIndex = table.rowAtPoint(event.point)
                if (rowIndex < 0) return
                when (val row = state.model.row(rowIndex)) {
                    is HostRow.Group -> {
                        state.toggleGroup(row.name)
                        refreshRows(state)
                        selectGroup(state, row.name)
                    }
                    is HostRow.Host -> if (event.clickCount >= 2) connectHost(row.host, closeOnStart = true)
                    null -> Unit
                }
            }
        })
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = refreshRows(state)

            override fun removeUpdate(event: DocumentEvent) = refreshRows(state)

            override fun changedUpdate(event: DocumentEvent) = refreshRows(state)
        })
        add.addActionListener {
            val editor = showHostEditor(dialog, null, currentHosts(state), selectedGroup(state)) ?: return@addActionListener
            val warning = AtomicReference<String?>()
            val created = AtomicReference<SavedHost?>()
            mutate(
                state,
                operation = {
                    val host = catalog.createHost(editor.draft)
                    created.set(host)
                    try {
                        applyEditorCredentials(host, editor)
                    } catch (_: Exception) {
                        warning.set("Host saved, but the password could not be saved.")
                    }
                },
                afterSave = {
                    warning.get()?.let { message -> showCredentialWarning(dialog, message) }
                    val host = created.get()
                    if (host != null && editor.connectAfterSave) connectHost(host, closeOnStart = true)
                },
                onRejected = editor::discardPassword,
            )
        }
        edit.addActionListener {
            val selected = selectedHost(state) ?: return@addActionListener
            showHostEditor(dialog, selected, currentHosts(state))?.let { editor ->
                val warning = AtomicReference<String?>()
                mutate(
                    state,
                    operation = {
                        val updated = updateHost(selected, editor.draft)
                        try {
                            applyEditorCredentials(updated, editor)
                        } catch (_: Exception) {
                            warning.set(
                                if (editor.clearSavedPassword) {
                                    "Host saved, but the saved password could not be cleared."
                                } else {
                                    "Host saved, but the password could not be saved."
                                },
                            )
                        }
                    },
                    afterSave = { warning.get()?.let { message -> showCredentialWarning(dialog, message) } },
                    onRejected = editor::discardPassword,
                )
            }
        }
        delete.addActionListener {
            val selected = selectedHost(state) ?: return@addActionListener
            if (JOptionPane.showConfirmDialog(
                    dialog,
                    "Delete '${selected.draft.name}'?",
                    "Delete saved host",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                ) == JOptionPane.YES_OPTION
            ) {
                mutate(state) {
                    deleteHost(selected)
                }
            }
        }
        connectButton.addActionListener {
            val selected = selectedHost(state) ?: return@addActionListener
            connectHost(selected, closeOnStart = true)
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
                rebuildTable(state, hosts)
                setBusy(state, false, if (hosts.isEmpty()) "No saved hosts." else "${hosts.size} saved hosts")
            },
            failure = { showCatalogFailure(state, it) },
        )
    }

    private fun mutate(
        state: DialogState,
        afterSave: (() -> Unit)? = null,
        onRejected: (() -> Unit)? = null,
        operation: () -> Unit,
    ) {
        setBusy(state, true, "Saving...")
        submit(
            operation = {
                operation()
                catalog.listHosts()
            },
            success = { hosts ->
                if (!state.dialog.isDisplayable) return@submit
                rebuildTable(state, hosts)
                setBusy(state, false, if (hosts.isEmpty()) "No saved hosts." else "${hosts.size} saved hosts")
                afterSave?.invoke()
            },
            failure = { showCatalogFailure(state, it) },
            onRejected = onRejected,
        )
    }

    private fun <T> submit(
        operation: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
        onRejected: (() -> Unit)? = null,
    ) {
        if (closed.get()) {
            onRejected?.invoke()
            return
        }
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
            onRejected?.invoke()
            return
        }
    }

    private fun setBusy(state: DialogState, busy: Boolean, message: String) {
        state.busy = busy
        state.status.text = message
        state.buttons.forEach { it.isEnabled = !busy }
        if (!busy) {
            val selected = selectedHost(state) != null
            state.buttons.drop(1).forEach { it.isEnabled = selected }
            updateStatus(state)
        }
    }

    private fun showCatalogFailure(state: DialogState, failure: Throwable) {
        if (!state.dialog.isDisplayable) return
        setBusy(state, false, "Host catalog unavailable")
        val message = when (failure) {
            is CredentialRollbackException, is IllegalArgumentException -> failure.message
            else -> "Could not access the local host catalog."
        }
        JOptionPane.showMessageDialog(
            state.dialog,
            message,
            "Host catalog error",
            JOptionPane.ERROR_MESSAGE,
        )
    }

    private fun currentHosts(state: DialogState): List<SavedHost> = state.hosts

    private fun selectedHost(state: DialogState): SavedHost? =
        (state.model.row(state.table.selectedRow) as? HostRow.Host)?.host

    private fun selectedGroup(state: DialogState): String? =
        (state.model.row(state.table.selectedRow) as? HostRow.Group)?.name

    private fun refreshRows(state: DialogState) {
        val profileId = selectedHost(state)?.profileId
        state.model.setRows(HostTableRows.build(state.hosts, state.expandedGroups, state.search.text))
        updateStatus(state)
        restoreSelection(state, profileId)
    }

    private fun rebuildTable(state: DialogState, hosts: List<SavedHost>) {
        val profileId = selectedHost(state)?.profileId
        state.hosts = hosts
        if (state.firstLoad) {
            state.expandedGroups = hosts.mapNotNull { host -> host.draft.group }.toSet()
            state.firstLoad = false
        }
        state.model.setRows(HostTableRows.build(hosts, state.expandedGroups, state.search.text))
        updateStatus(state)
        restoreSelection(state, profileId)
    }

    private fun restoreSelection(state: DialogState, profileId: UUID?) {
        if (profileId == null) {
            state.table.clearSelection()
            return
        }
        val index = state.model.rows().indexOfFirst { row -> (row as? HostRow.Host)?.host?.profileId == profileId }
        if (index >= 0) state.table.setRowSelectionInterval(index, index) else state.table.clearSelection()
    }

    private fun selectGroup(state: DialogState, name: String) {
        val index = state.model.rows().indexOfFirst { row -> (row as? HostRow.Group)?.name == name }
        if (index >= 0) state.table.setRowSelectionInterval(index, index)
    }

    private fun updateStatus(state: DialogState) {
        val total = state.hosts.size
        val shown = state.model.rows().count { it is HostRow.Host }
        state.status.text = when {
            total == 0 -> "No saved hosts."
            state.search.text.isBlank() -> "$total saved hosts"
            else -> "$shown of $total saved hosts"
        }
    }

    private fun showHostEditor(
        owner: Component,
        existing: SavedHost?,
        availableHosts: List<SavedHost>,
        defaultGroup: String? = null,
    ): HostEditorResult? {
        val name = JTextField(existing?.draft?.name.orEmpty(), 24)
        val host = JTextField(existing?.draft?.endpoint?.host.orEmpty(), 24)
        val port = JSpinner(SpinnerNumberModel(existing?.draft?.endpoint?.port ?: 22, 1, 65535, 1))
        val username = JTextField(existing?.draft?.endpoint?.username.orEmpty(), 24)
        val authentication = JComboBox(ConnectionAuthenticationMethod.entries.toTypedArray()).apply {
            selectedItem = existing?.draft?.authenticationMethod?.toConnectionMethod()
                ?: ConnectionAuthenticationMethod.PASSWORD
        }
        val jumpCandidates = availableHosts.filter { candidate ->
            candidate.profileId != existing?.profileId && candidate.draft.jumpHostProfileId == null
        }
        val jumpHost = JComboBox<SavedHost?>().apply {
            this.name = "jumpHostField"
            addItem(null)
            jumpCandidates.forEach(::addItem)
            selectedItem = jumpCandidates.firstOrNull {
                it.profileId == existing?.draft?.jumpHostProfileId
            }
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    (this as JLabel).text = when (value) {
                        null -> "(none)"
                        is SavedHost -> "${value.draft.name}  ${value.draft.endpoint.displayName}"
                        else -> value.toString()
                    }
                }
            }
        }
        val group = JTextField(existing?.draft?.group ?: defaultGroup.orEmpty(), 24)
        val tags = JTextField(existing?.draft?.tags?.joinToString(", ").orEmpty(), 24)
        val storeStatus = credentialStoreStatus()
        val storeUsable = storeStatus == CredentialStoreStatus.AVAILABLE || storeStatus == CredentialStoreStatus.LOCKED
        val savedPassword = existing != null && storeUsable && try {
            passwordStore.contains(existing.profileId)
        } catch (_: Exception) {
            false
        }
        val password = JPasswordField(24)
        val rememberPassword = JCheckBox("Save password in OS credential store", storeUsable)
        val clearPassword = JButton("Clear saved password")
        val connectAfterSave = JCheckBox("Connect after saving", false)
        val credentialHint = JLabel()
        var clearRequested = false

        fun updateCredentialFields() {
            val state = hostEditorCredentialState(
                passwordAuthentication = authentication.selectedItem == ConnectionAuthenticationMethod.PASSWORD,
                storeStatus = storeStatus,
                savedPassword = savedPassword,
                rememberSelected = rememberPassword.isSelected,
                clearRequested = clearRequested,
            )
            password.isEnabled = state.passwordEnabled
            password.putClientProperty(
                "JTextField.placeholderText",
                UiText.passwordPlaceholder(
                    passwordAuthentication = state.passwordEnabled,
                    savedPassword = savedPassword && !clearRequested,
                ),
            )
            rememberPassword.isEnabled = state.rememberEnabled
            clearPassword.isEnabled = state.clearEnabled
            credentialHint.text = state.hint
        }
        authentication.addActionListener { updateCredentialFields() }
        rememberPassword.addActionListener { updateCredentialFields() }
        clearPassword.addActionListener {
            clearRequested = true
            password.text = ""
            updateCredentialFields()
        }
        updateCredentialFields()

        val panel = JPanel(GridBagLayout()).apply {
            addField(0, "Name", name)
            addField(1, "Host", host)
            addField(2, "Port", port)
            addField(3, "Username", username)
            addField(4, "Authentication", authentication)
            addField(5, "Password", password)
            addField(6, "Remember", rememberPassword)
            addField(7, "", clearPassword)
            addField(8, "Jump host", jumpHost)
            addField(9, "Group", group)
            addField(10, "Tags", tags)
            addField(11, "", credentialHint)
            if (existing == null) addField(12, "", connectAfterSave)
        }
        try {
            while (JOptionPane.showConfirmDialog(
                    owner,
                    panel,
                    if (existing == null) "Add saved host" else "Edit saved host",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                ) == JOptionPane.OK_OPTION
            ) {
                try {
                    val draft = HostDraft(
                        name = name.text,
                        endpoint = SshEndpoint(host.text.trim(), port.value as Int, username.text.trim()),
                        authenticationMethod = (authentication.selectedItem as ConnectionAuthenticationMethod).toSavedMethod(),
                        group = group.text.trim().takeIf(String::isNotEmpty),
                        tags = tags.text.split(',').map(String::trim).filter(String::isNotEmpty),
                        jumpHostProfileId = (jumpHost.selectedItem as SavedHost?)?.profileId,
                    ).normalized()
                    val entered = password.password
                    val savePassword = password.isEnabled && storeUsable &&
                        rememberPassword.isSelected && entered.isNotEmpty()
                    val sessionPassword = password.isEnabled && !savePassword && entered.isNotEmpty()
                    if (!savePassword && !sessionPassword) java.util.Arrays.fill(entered, '\u0000')
                    return HostEditorResult(
                        draft = draft,
                        password = entered.takeIf { savePassword },
                        clearSavedPassword = clearRequested,
                        sessionPassword = entered.takeIf { sessionPassword },
                        connectAfterSave = connectAfterSave.isSelected,
                    )
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
        } finally {
            password.text = ""
        }
    }

    private fun credentialStoreStatus(): CredentialStoreStatus = try {
        passwordStore.status()
    } catch (_: Exception) {
        CredentialStoreStatus.UNAVAILABLE
    }

    private fun savePassword(host: SavedHost, password: CharArray) {
        credentialGuard.mutate(host.profileId) { passwordStore.save(host.profileId, password) }
    }

    private fun clearPassword(host: SavedHost) {
        credentialGuard.mutate(host.profileId) { passwordStore.forget(host.profileId) }
    }

    internal fun applyEditorCredentials(host: SavedHost, editor: HostEditorResult): CredentialAction {
        var action = CredentialAction.NONE
        editor.consumePassword { password ->
            when {
                editor.clearSavedPassword -> {
                    clearPassword(host)
                    action = CredentialAction.CLEARED
                }
                password != null -> {
                    savePassword(host, password)
                    sessionPasswords.invalidate(host.profileId)
                    action = CredentialAction.SAVED
                }
            }
        }
        if (editor.clearSavedPassword) sessionPasswords.invalidate(host.profileId)
        editor.consumeSessionPassword { password ->
            if (password != null) {
                sessionPasswords.put(host.profileId, password)
                action = CredentialAction.SESSION
            }
        }
        return action
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

    private fun showCredentialWarning(owner: Component, message: String) {
        JOptionPane.showMessageDialog(
            owner,
            message,
            "OS credential store",
            JOptionPane.WARNING_MESSAGE,
        )
    }

    internal class HostEditorResult(
        val draft: HostDraft,
        private var password: CharArray?,
        val clearSavedPassword: Boolean,
        private var sessionPassword: CharArray? = null,
        val connectAfterSave: Boolean = false,
    ) {
        fun consumePassword(block: (CharArray?) -> Unit) {
            val value = password
            password = null
            try {
                block(value)
            } finally {
                value?.fill('\u0000')
            }
        }

        fun consumeSessionPassword(block: (CharArray?) -> Unit) {
            val value = sessionPassword
            sessionPassword = null
            try {
                block(value)
            } finally {
                value?.fill('\u0000')
            }
        }

        fun discardPassword() {
            password?.fill('\u0000')
            password = null
            sessionPassword?.fill('\u0000')
            sessionPassword = null
        }
    }

    private class DialogState(
        val dialog: JDialog,
        val table: JTable,
        val model: HostTableModel,
        val search: JTextField,
        val status: JLabel,
        val buttons: List<JButton>,
    ) {
        var busy = false
        var hosts: List<SavedHost> = emptyList()
        var expandedGroups: Set<String> = emptySet()
        var firstLoad = true

        fun toggleGroup(name: String) {
            expandedGroups = if (name in expandedGroups) expandedGroups - name else expandedGroups + name
        }
    }

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

internal enum class CredentialAction { NONE, SAVED, CLEARED, SESSION }

internal data class HostEditorCredentialState(
    val passwordEnabled: Boolean,
    val rememberEnabled: Boolean,
    val clearEnabled: Boolean,
    val hint: String,
)

internal fun hostEditorCredentialState(
    passwordAuthentication: Boolean,
    storeStatus: CredentialStoreStatus,
    savedPassword: Boolean,
    rememberSelected: Boolean,
    clearRequested: Boolean,
): HostEditorCredentialState {
    val storeAvailable = storeStatus == CredentialStoreStatus.AVAILABLE
    val storeUsable = storeAvailable || storeStatus == CredentialStoreStatus.LOCKED
    return HostEditorCredentialState(
        passwordEnabled = passwordAuthentication,
        rememberEnabled = passwordAuthentication && storeUsable,
        clearEnabled = passwordAuthentication && storeAvailable && savedPassword && !clearRequested,
        hint = when {
            !passwordAuthentication -> ""
            clearRequested -> UiText.CLEAR_PENDING
            storeStatus == CredentialStoreStatus.LOCKED -> UiText.STORE_LOCKED
            !storeAvailable -> UiText.STORE_UNAVAILABLE
            rememberSelected -> UiText.STORE_WILL_SAVE
            savedPassword -> UiText.STORE_KEEP_OR_SESSION
            else -> UiText.STORE_SESSION_ONLY
        },
    )
}

internal fun SavedHost.toPreset(jumpHost: SavedHost? = null): HostConnectionPreset = HostConnectionPreset(
    profileId = profileId,
    endpoint = draft.endpoint,
    authenticationMethod = draft.authenticationMethod.toConnectionMethod(),
    jumpHost = jumpHost?.let { gateway ->
        JumpHostPreset(
            profileId = gateway.profileId,
            endpoint = gateway.draft.endpoint,
            authenticationMethod = gateway.draft.authenticationMethod.toConnectionMethod(),
        )
    },
)

private fun SavedAuthenticationMethod.toConnectionMethod(): ConnectionAuthenticationMethod =
    ConnectionAuthenticationMethod.valueOf(name)

private fun ConnectionAuthenticationMethod.toSavedMethod(): SavedAuthenticationMethod =
    SavedAuthenticationMethod.valueOf(name)

/**
 * Renders every Saved hosts column. Folder rows get a disclosure triangle, bold name, and muted
 * text; host rows are indented so the hierarchy stays readable in a flat table.
 */
private class HostTableRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val rowData = (table.model as? HostTableModel)?.row(row)
        icon = when {
            column != HostTableModel.NAME_COLUMN -> null
            rowData is HostRow.Group -> DisclosureIcon(rowData.expanded)
            rowData is HostRow.Host -> HostIndentIcon
            else -> null
        }
        font = if (rowData is HostRow.Group && column == HostTableModel.NAME_COLUMN) {
            table.font.deriveFont(Font.BOLD)
        } else {
            table.font
        }
        if (!isSelected) {
            foreground = if (rowData is HostRow.Group) {
                UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            } else {
                table.foreground
            }
        }
        toolTipText = if (rowData is HostRow.Group && column == HostTableModel.NAME_COLUMN) {
            "Click to expand or collapse ${rowData.name}"
        } else {
            null
        }
        return this
    }
}

/** Small triangle that shows whether a folder row is expanded. */
private class DisclosureIcon(private val expanded: Boolean) : Icon {
    override fun getIconWidth(): Int = INDENT

    override fun getIconHeight(): Int = INDENT

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = component?.foreground ?: Color.GRAY
            val triangle = Path2D.Double()
            if (expanded) {
                triangle.moveTo(x + 2.0, y + 4.0)
                triangle.lineTo(x + 10.0, y + 4.0)
                triangle.lineTo(x + 6.0, y + 9.0)
            } else {
                triangle.moveTo(x + 4.0, y + 2.0)
                triangle.lineTo(x + 9.0, y + 6.0)
                triangle.lineTo(x + 4.0, y + 10.0)
            }
            triangle.closePath()
            g.fill(triangle)
        } finally {
            g.dispose()
        }
    }

    private companion object {
        const val INDENT = 12
    }
}

/** Transparent spacer that keeps host names aligned with the folder disclosure triangle. */
private object HostIndentIcon : Icon {
    override fun getIconWidth(): Int = 12

    override fun getIconHeight(): Int = 12

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) = Unit
}
