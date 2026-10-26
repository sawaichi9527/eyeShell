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
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.DefaultListCellRenderer
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
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

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
        val model = DefaultTreeModel(DefaultMutableTreeNode())
        val tree = JTree(model).apply {
            name = "hostCatalogTree"
            getAccessibleContext().accessibleName = "Saved hosts grouped by folder"
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(
                    tree: JTree?,
                    value: Any?,
                    isSelected: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean,
                ): Component = super.getTreeCellRendererComponent(tree, value, isSelected, expanded, leaf, row, hasFocus).apply {
                    when (val node = (value as? DefaultMutableTreeNode)?.userObject ?: value) {
                        is HostGroupNode -> text = HostCatalogTree.groupLabel(node)
                        is SavedHost -> text = HostCatalogTree.hostLabel(node)
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
                add(JScrollPane(tree), BorderLayout.CENTER)
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
        val state = DialogState(dialog, tree, status, listOf(add, edit, delete, connectButton))
        fun updateSelectionActions() {
            val selected = selectedHost(state) != null && state.busy.not()
            edit.isEnabled = selected
            delete.isEnabled = selected
            connectButton.isEnabled = selected
        }
        tree.addTreeSelectionListener { updateSelectionActions() }
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
                rebuildTree(state, hosts)
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
                rebuildTree(state, hosts)
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
        (state.tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SavedHost

    private fun selectedGroup(state: DialogState): String? =
        ((state.tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? HostGroupNode)?.name

    private fun rebuildTree(state: DialogState, hosts: List<SavedHost>) {
        val expanded = captureExpandedGroups(state)
        val selectedProfileId = selectedHost(state)?.profileId
        val firstLoad = state.firstLoad
        val model = state.tree.model as DefaultTreeModel
        val root = HostCatalogTree.rootNode(hosts)
        model.setRoot(root)
        state.hosts = hosts
        state.firstLoad = false
        for (index in 0 until root.childCount) {
            val groupNode = root.getChildAt(index) as DefaultMutableTreeNode
            val group = groupNode.userObject as? HostGroupNode ?: continue
            if (firstLoad || group.name in expanded) {
                state.tree.expandPath(TreePath(arrayOf(root, groupNode)))
            }
        }
        val target = selectedProfileId?.let { profileId -> findHostNode(root, profileId) }
        if (target != null) {
            val path = TreePath(target.path)
            path.parentPath?.let(state.tree::expandPath)
            state.tree.selectionPath = path
        } else {
            state.tree.clearSelection()
        }
        state.expandedGroups = captureExpandedGroups(state)
    }

    private fun captureExpandedGroups(state: DialogState): Set<String> {
        val tree = state.tree
        val groups = mutableSetOf<String>()
        for (row in 0 until tree.rowCount) {
            val path = tree.getPathForRow(row) ?: continue
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val group = node.userObject as? HostGroupNode ?: continue
            if (group.name != null && tree.isExpanded(path)) groups.add(group.name)
        }
        return groups
    }

    private fun findHostNode(root: DefaultMutableTreeNode, profileId: UUID): DefaultMutableTreeNode? {
        val pending = ArrayDeque<DefaultMutableTreeNode>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if ((node.userObject as? SavedHost)?.profileId == profileId) return node
            for (index in 0 until node.childCount) pending.add(node.getChildAt(index) as DefaultMutableTreeNode)
        }
        return null
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
        val tree: JTree,
        val status: JLabel,
        val buttons: List<JButton>,
    ) {
        var busy = false
        var hosts: List<SavedHost> = emptyList()
        var expandedGroups: Set<String> = emptySet()
        var firstLoad = true
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
