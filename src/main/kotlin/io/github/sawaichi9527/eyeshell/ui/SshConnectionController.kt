package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKey
import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.KnownHostsStore
import io.github.sawaichi9527.eyeshell.ssh.KeyboardInteractiveChallenge
import io.github.sawaichi9527.eyeshell.ssh.KeyboardInteractiveResponder
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.ssh.TransportProvider
import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreStatus
import io.github.sawaichi9527.eyeshell.secrets.PasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.secrets.SessionPasswordCache
import io.github.sawaichi9527.eyeshell.secrets.StoredPassword
import io.github.sawaichi9527.eyeshell.secrets.UnavailablePasswordCredentialStore
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalTypography
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

internal class SshConnectionController(
    private val passwordStore: PasswordCredentialStore = UnavailablePasswordCredentialStore(),
    private val terminalConnector: SshTerminalConnector = MinaTerminalConnector,
    private val credentialGuard: ProfileCredentialGuard = ProfileCredentialGuard(),
    private val sessionPasswords: SessionPasswordCache = SessionPasswordCache(),
) : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val knownHostsStore = KnownHostsStore(EyeShellPaths.knownHostsFile())
    private val connectionDialog = AtomicReference<JDialog>()
    private val challengeDialog = AtomicReference<JDialog>()
    private var connectionTask: Future<*>? = null

    fun connect(owner: EyeShellWindow): Boolean = connect(owner, null)

    internal fun connect(owner: EyeShellWindow, preset: HostConnectionPreset?): Boolean =
        connect(owner, preset, null)

    /**
     * @param target existing terminal tab to reconnect in place. When null, or when the tab can no
     *   longer accept a session, the connection opens a new tab instead.
     */
    internal fun connect(owner: EyeShellWindow, preset: HostConnectionPreset?, target: JComponent?): Boolean {
        check(SwingUtilities.isEventDispatchThread()) { "The SSH connection dialog must open on the Swing EDT" }
        if (closed.get() || connectionTask?.isDone == false) return false
        owner.setConnectionState(I18n.t("conn.preparing"), true)
        connectionTask = executor.submit {
            connectInBackground(owner, preset, target)
        }
        return true
    }

    private fun connectInBackground(owner: EyeShellWindow, preset: HostConnectionPreset?, target: JComponent?) {
        var credential = loadStoredPassword(preset)
        var jumpCredential = preset?.jumpHost?.let { loadStoredPassword(it.toPreset()) }
            ?: LoadedCredential(CredentialStoreStatus.UNAVAILABLE, null, null, CredentialSource.NONE)
        try {
            while (!closed.get()) {
                val password = credential.password?.copyValue()
                val jumpPassword = jumpCredential.password?.copyValue()
                val decision = try {
                    showConnectionDialogOnEdt(owner, preset, credential, password, jumpCredential, jumpPassword)
                } finally {
                    password?.fill('\u0000')
                    jumpPassword?.fill('\u0000')
                }
                when (decision) {
                    ConnectionDialogDecision.Cancel -> {
                        publishConnectionState(owner, I18n.t("conn.notConnected"))
                        return
                    }
                    ConnectionDialogDecision.ForgetPassword -> {
                        try {
                            checkNotNull(preset)
                            credentialGuard.mutate(preset.profileId) { passwordStore.forget(preset.profileId) }
                            credential.close()
                            credential = loadStoredPassword(preset)
                            jumpCredential.close()
                            jumpCredential = preset.jumpHost?.let { loadStoredPassword(it.toPreset()) }
                                ?: LoadedCredential(CredentialStoreStatus.UNAVAILABLE, null, null, CredentialSource.NONE)
                        } catch (_: Exception) {
                            showCredentialWarning(owner, I18n.t("conn.forgetFailed"))
                        }
                    }
                    is ConnectionDialogDecision.Connect -> {
                        connectRequest(owner, decision.request, target)
                        return
                    }
                }
            }
        } finally {
            credential.close()
            jumpCredential.close()
        }
    }

    internal fun loadStoredPassword(
        preset: HostConnectionPreset?,
    ): LoadedCredential {
        if (preset?.authenticationMethod != ConnectionAuthenticationMethod.PASSWORD) {
            return LoadedCredential(CredentialStoreStatus.UNAVAILABLE, null, null, CredentialSource.NONE)
        }
        val storeStatus = safeStoreStatus()
        val sessionPassword = sessionPasswords.copyOf(preset.profileId)
        if (sessionPassword != null) {
            try {
                return LoadedCredential(
                    storeStatus = storeStatus,
                    password = StoredPassword(sessionPassword),
                    credentialRevision = null,
                    source = CredentialSource.SESSION,
                )
            } finally {
                java.util.Arrays.fill(sessionPassword, '\u0000')
            }
        }
        if (storeStatus != CredentialStoreStatus.AVAILABLE && storeStatus != CredentialStoreStatus.LOCKED) {
            return LoadedCredential(storeStatus, null, null, CredentialSource.NONE)
        }
        // Ask the store whether an item exists before reading it: retrieving unlocks a locked
        // keyring, and prompting for the OS password when nothing is stored is not expected.
        if (!passwordStore.contains(preset.profileId)) {
            // Still carry the profile revision so a password typed for a first save can be stored
            // later, without touching the credential store now.
            val revision = try {
                credentialGuard.snapshot(preset.profileId) { }.revision
            } catch (_: Exception) {
                null
            }
            return LoadedCredential(storeStatus, null, revision, CredentialSource.NONE)
        }
        val snapshot = try {
            credentialGuard.snapshot(preset.profileId) { active ->
                if (active) passwordStore.retrieve(preset.profileId) else null
            }
        } catch (_: Exception) {
            return LoadedCredential(storeStatus, null, null, CredentialSource.NONE)
        }
        if (!snapshot.active) return LoadedCredential(CredentialStoreStatus.UNAVAILABLE, null, null, CredentialSource.NONE)
        val stored = snapshot.value ?: return LoadedCredential(storeStatus, null, null, CredentialSource.NONE)
        return LoadedCredential(safeStoreStatus(), stored, snapshot.revision, CredentialSource.OS_STORE)
    }

    private fun connectRequest(owner: EyeShellWindow, request: ConnectionRequest, target: JComponent?) {
        request.use {
            publishConnectionState(owner, I18n.t("conn.connecting", request.endpoint.displayName), connecting = true)
            val changedHostKey = AtomicReference<ChangedHostKey>()
            val changedJumpHostKey = AtomicReference<ChangedHostKey>()
            val review = HostKeyReview(request.endpoint, request.jumpHost?.endpoint)
            val transport = request.jumpHost?.let { jump ->
                TransportProvider.JumpHost(
                    endpoint = jump.endpoint,
                    authentication = checkNotNull(request.jumpAuthentication) {
                        I18n.t("conn.jumpAuthMissing")
                    },
                    unknownHostVerifier = HostKeyVerifier { presented ->
                        confirmHostKey(owner, review.jumpPrompt(presented)).also { accepted ->
                            if (accepted) review.acceptJumpKey(presented)
                        }
                    },
                    changedHostKeyHandler = ChangedHostKeyHandler(changedJumpHostKey::set),
                )
            } ?: TransportProvider.Direct
            try {
                val opened = openTerminal(
                    request,
                    HostKeyVerifier { confirmHostKey(owner, review.targetPrompt(it)) },
                    ChangedHostKeyHandler(changedHostKey::set),
                    transport,
                )
                val reconnectProfileId = request.passwordProfileId ?: UUID.randomUUID()
                rememberForReconnect(request, opened, reconnectProfileId)
                val reconnectPreset = HostConnectionPreset(
                    profileId = reconnectProfileId,
                    endpoint = request.endpoint,
                    authenticationMethod = request.authentication.toConnectionMethod(),
                    jumpHost = request.jumpHost,
                )
                SwingUtilities.invokeLater {
                    if (owner.isDisplayable && !closed.get()) {
                        try {
                            val reused = target != null &&
                                owner.reconnectTerminal(target, opened.hostSession, opened.terminal, reconnectPreset)
                            if (!reused) {
                                owner.attachTerminal(opened.hostSession, opened.terminal, reconnectPreset) { preset, component ->
                                    connect(owner, preset, component)
                                }
                            }
                            if (opened.passwordSaveFailed) {
                                showCredentialWarning(owner, I18n.t("conn.saveFailed"))
                            }
                        } catch (failure: Exception) {
                            opened.hostSession.close()
                            owner.setConnectionState(I18n.t("conn.failed"), false)
                            JOptionPane.showMessageDialog(
                                owner,
                                describeFailure(failure),
                                if (target != null) I18n.t("conn.reconnectFailed") else I18n.t("conn.openTabFailed"),
                                JOptionPane.ERROR_MESSAGE,
                            )
                        }
                    } else {
                        opened.hostSession.close()
                    }
                }
            } catch (failure: Exception) {
                SwingUtilities.invokeLater {
                    if (owner.isDisplayable && !closed.get()) {
                        owner.setConnectionState(I18n.t("conn.failed"), false)
                        val changedKey = changedJumpHostKey.get() ?: changedHostKey.get()
                        JOptionPane.showMessageDialog(
                            owner,
                            changedKey?.let(::changedHostKeyMessage)
                                ?: describeFailure(failure),
                            I18n.t("conn.failedTitle"),
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            }
        }
    }

    internal fun rememberForReconnect(request: ConnectionRequest, opened: OpenedTerminal, profileId: UUID) {
        val targetPassword = (request.authentication as? SshAuthentication.Password)?.copyValue()
        try {
            if (targetPassword != null && targetPassword.isNotEmpty() &&
                !(request.rememberPassword && !opened.passwordSaveFailed)
            ) {
                sessionPasswords.put(profileId, targetPassword)
            }
        } catch (_: IllegalStateException) {
            // The cache is closed during shutdown.
        } finally {
            targetPassword?.fill('\u0000')
        }

        val jumpPassword = (request.jumpAuthentication as? SshAuthentication.Password)?.copyValue()
        val jumpProfileId = request.jumpHost?.profileId
        try {
            if (jumpPassword != null && jumpPassword.isNotEmpty() && jumpProfileId != null) {
                sessionPasswords.put(jumpProfileId, jumpPassword)
            }
        } catch (_: IllegalStateException) {
            // The cache is closed during shutdown.
        } finally {
            jumpPassword?.fill('\u0000')
        }
    }

    internal fun openTerminal(
        request: ConnectionRequest,
        unknownHostVerifier: HostKeyVerifier,
        changedHostKeyHandler: ChangedHostKeyHandler,
        transport: TransportProvider = TransportProvider.Direct,
    ): OpenedTerminal {
        val hostSession = terminalConnector.open(
            endpoint = request.endpoint,
            authentication = request.authentication,
            knownHostsStore = knownHostsStore,
            unknownHostVerifier = unknownHostVerifier,
            changedHostKeyHandler = changedHostKeyHandler,
            transport = transport,
        )
        val terminal = try {
            hostSession.openTerminal(
                columns = TerminalTypography.INITIAL_COLUMNS,
                rows = TerminalTypography.INITIAL_ROWS,
            )
        } catch (failure: Exception) {
            hostSession.close()
            throw failure
        }
        val saveFailed = request.passwordProfileId?.takeIf { request.rememberPassword }?.let { profileId ->
            val password = (request.authentication as? SshAuthentication.Password)?.copyValue()
            try {
                if (password == null || request.credentialRevision == null) {
                    true
                } else {
                    val saved = credentialGuard.saveIfCurrent(profileId, request.credentialRevision) {
                        passwordStore.save(profileId, password)
                    }
                    if (saved) sessionPasswords.invalidate(profileId)
                    !saved
                }
            } catch (_: Exception) {
                true
            } finally {
                password?.fill('\u0000')
            }
        } ?: false
        return OpenedTerminal(hostSession, terminal, saveFailed)
    }

    private fun confirmHostKey(owner: EyeShellWindow, prompt: HostKeyPrompt): Boolean {
        val context = prompt.contextLines.takeIf(List<String>::isNotEmpty)
            ?.joinToString(separator = "\n", postfix = "\n")
            .orEmpty()
        val message = """
            ${prompt.subject.replaceFirstChar(Char::uppercase)} host key

            ${context}Address: ${prompt.address}
            Algorithm: ${prompt.key.algorithm}
            Fingerprint: ${prompt.key.fingerprint}

            Accept and save this key to:
            ${knownHostsStore.file}
        """.trimIndent()
        var accepted = false
        SwingUtilities.invokeAndWait {
            if (!owner.isDisplayable || closed.get()) return@invokeAndWait
            accepted = JOptionPane.showConfirmDialog(
                owner,
                message,
                prompt.title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            ) == JOptionPane.YES_OPTION
        }
        return accepted
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dismissConnectionDialog()
        dismissKeyboardInteractiveDialog()
        connectionTask?.cancel(true)
        executor.shutdownNow()
        sessionPasswords.close()
    }

    private fun showConnectionDialogOnEdt(
        owner: EyeShellWindow,
        preset: HostConnectionPreset?,
        credential: LoadedCredential,
        storedPassword: CharArray?,
        jumpCredential: LoadedCredential,
        storedJumpPassword: CharArray?,
    ): ConnectionDialogDecision {
        val decision = AtomicReference<ConnectionDialogDecision>(ConnectionDialogDecision.Cancel)
        SwingUtilities.invokeAndWait {
            if (!owner.isDisplayable || closed.get()) return@invokeAndWait
            decision.set(
                showConnectionDialog(
                    owner, preset, credential, storedPassword, jumpCredential, storedJumpPassword,
                ),
            )
        }
        return decision.get()
    }

    private fun showConnectionDialog(
        owner: EyeShellWindow,
        preset: HostConnectionPreset?,
        credential: LoadedCredential,
        storedPassword: CharArray?,
        jumpCredential: LoadedCredential,
        storedJumpPassword: CharArray?,
    ): ConnectionDialogDecision {
        val storeStatus = credential.storeStatus
        val credentialRevision = credential.credentialRevision
        val canForgetPassword = credential.source == CredentialSource.OS_STORE
        val jumpStoreStatus = jumpCredential.storeStatus
        val jumpSource = jumpCredential.source
        val hostField = JTextField(preset?.endpoint?.host.orEmpty(), 24)
        val portSpinner = JSpinner(SpinnerNumberModel(preset?.endpoint?.port ?: 22, 1, 65535, 1))
        val usernameField = JTextField(preset?.endpoint?.username.orEmpty(), 24)
        val authenticationField = JComboBox(ConnectionAuthenticationMethod.entries.toTypedArray()).apply {
            selectedItem = preset?.authenticationMethod ?: ConnectionAuthenticationMethod.PASSWORD
        }
        val passwordField = JPasswordField(24)
        val rememberPassword = JCheckBox(I18n.t("conn.remember"), canForgetPassword)
        val keyFileField = JTextField(24)
        val keyFileButton = JButton(I18n.t("conn.browse"))
        val keyFilePanel = JPanel(java.awt.BorderLayout(6, 0)).apply {
            add(keyFileField, java.awt.BorderLayout.CENTER)
            add(keyFileButton, java.awt.BorderLayout.EAST)
        }
        val passphraseField = JPasswordField(24)
        val jump = preset?.jumpHost
        val jumpPasswordField = JPasswordField(24)
        val jumpKeyFileField = JTextField(24)
        val jumpKeyFileButton = JButton(I18n.t("conn.browse"))
        val jumpPassphraseField = JPasswordField(24)
        val credentialHint = JLabel()
        val panel = JPanel(GridBagLayout())
        addField(panel, 0, I18n.t("conn.host"), hostField)
        addField(panel, 1, I18n.t("conn.port"), portSpinner)
        addField(panel, 2, I18n.t("conn.username"), usernameField)
        addField(panel, 3, I18n.t("conn.authentication"), authenticationField)
        addField(panel, 4, I18n.t("conn.password"), passwordField)
        addField(panel, 5, I18n.t("conn.privateKey"), keyFilePanel)
        addField(panel, 6, I18n.t("conn.passphrase"), passphraseField)
        addField(panel, 7, "", rememberPassword)
        addField(panel, 8, "", credentialHint)
        if (jump != null) {
            addField(panel, 9, I18n.t("conn.jumpHost"), buildJumpCredentialPanel(
                jump = jump,
                storedJumpPassword = storedJumpPassword,
                jumpStoreStatus = jumpStoreStatus,
                jumpSource = jumpSource,
                passwordField = jumpPasswordField,
                keyFileField = jumpKeyFileField,
                keyFileButton = jumpKeyFileButton,
                passphraseField = jumpPassphraseField,
            ))
            jumpKeyFileButton.addActionListener {
                JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    dialogTitle = I18n.t("conn.chooseJumpKey")
                    if (showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                        jumpKeyFileField.text = selectedFile.toPath().toString()
                    }
                }
            }
        }

        fun updateAuthenticationFields() {
            val usePassword = authenticationField.selectedItem == ConnectionAuthenticationMethod.PASSWORD
            val usePublicKey = authenticationField.selectedItem == ConnectionAuthenticationMethod.PUBLIC_KEY
            passwordField.isEnabled = usePassword
            passwordField.putClientProperty(
                "JTextField.placeholderText",
                UiText.passwordPlaceholder(passwordAuthentication = usePassword, savedPassword = storedPassword != null),
            )
            keyFileField.isEnabled = usePublicKey
            keyFileButton.isEnabled = usePublicKey
            passphraseField.isEnabled = usePublicKey
            rememberPassword.isEnabled = usePassword && preset != null &&
                (storeStatus == CredentialStoreStatus.AVAILABLE || storeStatus == CredentialStoreStatus.LOCKED) &&
                credential.source != CredentialSource.SESSION
            credentialHint.text = when {
                !usePassword -> ""
                credential.source == CredentialSource.SESSION -> UiText.CONNECT_SESSION_PASSWORD
                storeStatus == CredentialStoreStatus.LOCKED -> UiText.CONNECT_STORE_LOCKED
                storeStatus != CredentialStoreStatus.AVAILABLE ->
                    I18n.t("conn.storedUnavailable")
                storedPassword != null -> I18n.t("conn.storedUsed")
                else -> ""
            }
        }
        authenticationField.addActionListener { updateAuthenticationFields() }
        keyFileButton.addActionListener {
            JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                dialogTitle = I18n.t("conn.chooseKey")
                if (showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                    keyFileField.text = selectedFile.toPath().toString()
                }
            }
        }
        updateAuthenticationFields()

        val options = if (canForgetPassword) arrayOf(I18n.t("conn.connect"), I18n.t("conn.cancel"), I18n.t("conn.forget")) else arrayOf(I18n.t("conn.connect"), I18n.t("conn.cancel"))
        val optionPane = JOptionPane(
            panel,
            JOptionPane.PLAIN_MESSAGE,
            JOptionPane.DEFAULT_OPTION,
            null,
            options,
            I18n.t("conn.connect"),
        )
        val dialog = optionPane.createDialog(owner, I18n.t("conn.title"))
        connectionDialog.set(dialog)
        val selected = try {
            dialog.isVisible = true
            options.indexOf(optionPane.value)
        } finally {
            connectionDialog.compareAndSet(dialog, null)
            dialog.dispose()
        }
        if (selected == 2) {
            passwordField.text = ""
            passphraseField.text = ""
            return ConnectionDialogDecision.ForgetPassword
        }
        if (selected != 0) {
            Arrays.fill(passwordField.password, '\u0000')
            Arrays.fill(passphraseField.password, '\u0000')
            Arrays.fill(jumpPasswordField.password, '\u0000')
            Arrays.fill(jumpPassphraseField.password, '\u0000')
            passwordField.text = ""
            passphraseField.text = ""
            jumpPasswordField.text = ""
            jumpPassphraseField.text = ""
            return ConnectionDialogDecision.Cancel
        }

        val enteredPassword = passwordField.password
        val passphrase = passphraseField.password
        var password = enteredPassword
        var jumpAuthentication: SshAuthentication? = null
        return try {
            val endpoint = SshEndpoint(
                host = hostField.text.trim(),
                port = portSpinner.value as Int,
                username = usernameField.text.trim(),
            )
            val authentication = when (authenticationField.selectedItem as ConnectionAuthenticationMethod) {
                ConnectionAuthenticationMethod.PASSWORD -> {
                    if (password.isEmpty() && storedPassword != null) password = storedPassword.copyOf()
                    SshAuthentication.Password(password)
                }
                ConnectionAuthenticationMethod.PUBLIC_KEY -> {
                    require(keyFileField.text.isNotBlank()) { "Private key file must not be blank" }
                    SshAuthentication.PublicKey(Path.of(keyFileField.text.trim()), passphrase)
                }
                ConnectionAuthenticationMethod.KEYBOARD_INTERACTIVE -> SshAuthentication.KeyboardInteractive(
                    responder = KeyboardInteractiveResponder { respondToKeyboardInteractive(owner, it) },
                    cancel = ::dismissKeyboardInteractiveDialog,
                )
                ConnectionAuthenticationMethod.SSH_AGENT -> SshAuthentication.Agent.system()
            }
            jumpAuthentication = jump?.let { gateway ->
                buildJumpAuthentication(
                    owner = owner,
                    jump = gateway,
                    storedPassword = storedJumpPassword,
                    passwordField = jumpPasswordField,
                    keyFileField = jumpKeyFileField,
                    passphraseField = jumpPassphraseField,
                )
            }
            ConnectionDialogDecision.Connect(ConnectionRequest(
                endpoint,
                authentication,
                passwordProfileId = preset?.profileId?.takeIf {
                    authenticationField.selectedItem == ConnectionAuthenticationMethod.PASSWORD
                },
                rememberPassword = rememberPassword.isSelected,
                credentialRevision = credentialRevision,
                jumpHost = jump,
                jumpAuthentication = jumpAuthentication,
            )).also { jumpAuthentication = null }
        } catch (failure: IllegalArgumentException) {
            jumpAuthentication?.close()
            JOptionPane.showMessageDialog(owner, failure.message, I18n.t("conn.invalid"), JOptionPane.ERROR_MESSAGE)
            ConnectionDialogDecision.Cancel
        } finally {
            Arrays.fill(password, '\u0000')
            if (enteredPassword !== password) Arrays.fill(enteredPassword, '\u0000')
            Arrays.fill(passphrase, '\u0000')
            jumpAuthentication?.close()
            Arrays.fill(jumpPasswordField.password, '\u0000')
            Arrays.fill(jumpPassphraseField.password, '\u0000')
            passwordField.text = ""
            passphraseField.text = ""
            jumpPasswordField.text = ""
            jumpPassphraseField.text = ""
        }
    }

    private fun buildJumpAuthentication(
        owner: EyeShellWindow,
        jump: JumpHostPreset,
        storedPassword: CharArray?,
        passwordField: JPasswordField,
        keyFileField: JTextField,
        passphraseField: JPasswordField,
    ): SshAuthentication = when (jump.authenticationMethod) {
        ConnectionAuthenticationMethod.PASSWORD -> {
            val entered = passwordField.password
            val value = if (entered.isEmpty() && storedPassword != null) {
                Arrays.fill(entered, '\u0000')
                storedPassword.copyOf()
            } else {
                entered
            }
            try {
                require(value.isNotEmpty()) { "Jump host password must not be empty" }
                SshAuthentication.Password(value)
            } finally {
                Arrays.fill(value, '\u0000')
            }
        }
        ConnectionAuthenticationMethod.PUBLIC_KEY -> {
            require(keyFileField.text.isNotBlank()) { "Jump host private key file must not be blank" }
            val passphrase = passphraseField.password
            try {
                SshAuthentication.PublicKey(Path.of(keyFileField.text.trim()), passphrase)
            } finally {
                Arrays.fill(passphrase, '\u0000')
            }
        }
        ConnectionAuthenticationMethod.KEYBOARD_INTERACTIVE -> SshAuthentication.KeyboardInteractive(
            responder = KeyboardInteractiveResponder { respondToKeyboardInteractive(owner, it) },
            cancel = ::dismissKeyboardInteractiveDialog,
        )
        ConnectionAuthenticationMethod.SSH_AGENT -> SshAuthentication.Agent.system()
    }

    private fun buildJumpCredentialPanel(
        jump: JumpHostPreset,
        storedJumpPassword: CharArray?,
        jumpStoreStatus: CredentialStoreStatus,
        jumpSource: CredentialSource,
        passwordField: JPasswordField,
        keyFileField: JTextField,
        keyFileButton: JButton,
        passphraseField: JPasswordField,
    ): JPanel {
        val inner = JPanel(GridBagLayout())
        inner.border = BorderFactory.createTitledBorder(jump.endpoint.displayName)
        var row = 0
        addField(inner, row++, I18n.t("conn.authentication"), JLabel(jump.authenticationMethod.toString()))
        when (jump.authenticationMethod) {
            ConnectionAuthenticationMethod.PASSWORD -> {
                val hint = when {
                    jumpSource == CredentialSource.SESSION -> UiText.CONNECT_SESSION_PASSWORD
                    jumpStoreStatus == CredentialStoreStatus.LOCKED -> UiText.CONNECT_STORE_LOCKED
                    jumpStoreStatus != CredentialStoreStatus.AVAILABLE ->
                        I18n.t("conn.jumpStoredUnavailable")
                    storedJumpPassword != null -> I18n.t("conn.jumpStoredUsed")
                    else -> I18n.t("conn.jumpPrompt")
                }
                addField(inner, row++, "", JLabel(hint))
                passwordField.putClientProperty(
                    "JTextField.placeholderText",
                    UiText.passwordPlaceholder(passwordAuthentication = true, savedPassword = storedJumpPassword != null),
                )
                addField(inner, row++, I18n.t("conn.password"), passwordField)
            }
            ConnectionAuthenticationMethod.PUBLIC_KEY -> {
                addField(inner, row++, I18n.t("conn.privateKey"), JPanel(java.awt.BorderLayout(6, 0)).apply {
                    add(keyFileField, java.awt.BorderLayout.CENTER)
                    add(keyFileButton, java.awt.BorderLayout.EAST)
                })
                addField(inner, row++, I18n.t("conn.passphrase"), passphraseField)
            }
            ConnectionAuthenticationMethod.KEYBOARD_INTERACTIVE ->
                addField(inner, row, "", JLabel(I18n.t("conn.jumpInteractive")))
            ConnectionAuthenticationMethod.SSH_AGENT ->
                addField(inner, row, "", JLabel(I18n.t("conn.jumpAgent")))
        }
        return inner
    }

    private fun addField(panel: JPanel, row: Int, label: String, field: java.awt.Component) {
        panel.add(JLabel(label), GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.LINE_END
            insets = Insets(5, 5, 5, 10)
        })
        panel.add(field, GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(5, 0, 5, 5)
        })
    }

    internal class ConnectionRequest(
        val endpoint: SshEndpoint,
        val authentication: SshAuthentication,
        val passwordProfileId: UUID?,
        val rememberPassword: Boolean,
        val credentialRevision: Long?,
        val jumpHost: JumpHostPreset? = null,
        val jumpAuthentication: SshAuthentication? = null,
    ) : AutoCloseable {
        override fun close() {
            authentication.close()
            jumpAuthentication?.close()
        }
    }

    private sealed interface ConnectionDialogDecision {
        data class Connect(val request: ConnectionRequest) : ConnectionDialogDecision
        data object ForgetPassword : ConnectionDialogDecision
        data object Cancel : ConnectionDialogDecision
    }

    private fun safeStoreStatus(): CredentialStoreStatus = try {
        passwordStore.status()
    } catch (_: Exception) {
        CredentialStoreStatus.UNAVAILABLE
    }

    private fun publishConnectionState(owner: EyeShellWindow, message: String, connecting: Boolean = false) {
        SwingUtilities.invokeLater {
            if (owner.isDisplayable && !closed.get()) owner.setConnectionState(message, connecting)
        }
    }

    private fun showCredentialWarning(owner: EyeShellWindow, message: String) {
        val show = {
            if (owner.isDisplayable && !closed.get()) {
                JOptionPane.showMessageDialog(owner, message, I18n.t("conn.credentialStore"), JOptionPane.WARNING_MESSAGE)
            }
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeLater(show)
    }

    private fun changedHostKeyMessage(hostKey: ChangedHostKey): String = """
        The SSH host key has changed. The connection was rejected.

        Address: ${hostKey.remoteAddress}
        Algorithm: ${hostKey.algorithm}
        Expected: ${hostKey.expectedFingerprint}
        Actual: ${hostKey.actualFingerprint}

        Verify the server outside eyeShell before editing:
        ${knownHostsStore.file}
    """.trimIndent()

    private fun respondToKeyboardInteractive(
        owner: EyeShellWindow,
        challenge: KeyboardInteractiveChallenge,
    ): List<CharArray>? {
        var responses: List<CharArray>? = null
        SwingUtilities.invokeAndWait {
            if (!owner.isDisplayable || closed.get()) return@invokeAndWait
            val fields = challenge.prompts.map { prompt ->
                JPasswordField(24).apply {
                    if (prompt.echo) echoChar = '\u0000'
                }
            }
            val panel = JPanel(GridBagLayout())
            val description = listOf(challenge.name, challenge.instruction)
                .filter(String::isNotBlank)
                .joinToString("\n")
            if (description.isNotEmpty()) {
                panel.add(JTextArea(description).apply {
                    isEditable = false
                    isOpaque = false
                    lineWrap = true
                    wrapStyleWord = true
                }, GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    gridwidth = 2
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(5, 5, 10, 5)
                })
            }
            challenge.prompts.forEachIndexed { index, prompt ->
                addField(panel, index + 1, prompt.text, fields[index])
            }
            val optionPane = JOptionPane(
                panel,
                JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION,
            )
            val dialog = optionPane.createDialog(owner, I18n.t("conn.challenge"))
            challengeDialog.set(dialog)
            try {
                dialog.isVisible = true
                if (optionPane.value == JOptionPane.OK_OPTION) {
                    responses = fields.map(JPasswordField::getPassword)
                }
            } finally {
                challengeDialog.compareAndSet(dialog, null)
                dialog.dispose()
                fields.forEach { it.text = "" }
            }
        }
        return responses
    }

    private fun dismissKeyboardInteractiveDialog() {
        val dismiss = Runnable { challengeDialog.getAndSet(null)?.dispose() }
        if (SwingUtilities.isEventDispatchThread()) dismiss.run() else SwingUtilities.invokeLater(dismiss)
    }

    private fun dismissConnectionDialog() {
        val dismiss = Runnable { connectionDialog.getAndSet(null)?.dispose() }
        if (SwingUtilities.isEventDispatchThread()) dismiss.run() else SwingUtilities.invokeLater(dismiss)
    }
}

internal enum class CredentialSource { OS_STORE, SESSION, NONE }

internal data class LoadedCredential(
    val storeStatus: CredentialStoreStatus,
    val password: StoredPassword?,
    val credentialRevision: Long?,
    val source: CredentialSource,
) : AutoCloseable {
    override fun close() {
        password?.close()
    }
}

private fun SshAuthentication.toConnectionMethod(): ConnectionAuthenticationMethod = when (this) {
    is SshAuthentication.Password -> ConnectionAuthenticationMethod.PASSWORD
    is SshAuthentication.PublicKey -> ConnectionAuthenticationMethod.PUBLIC_KEY
    is SshAuthentication.KeyboardInteractive -> ConnectionAuthenticationMethod.KEYBOARD_INTERACTIVE
    is SshAuthentication.Agent -> ConnectionAuthenticationMethod.SSH_AGENT
}

internal fun describeFailure(failure: Throwable): String {
    val messages = generateSequence(failure) { current ->
        current.cause?.takeIf { cause -> cause !== current }
    }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()
    return messages.takeIf(List<String>::isNotEmpty)?.joinToString("\nCaused by: ")
        ?: failure.javaClass.simpleName
}

internal data class HostConnectionPreset(
    val profileId: UUID,
    val endpoint: SshEndpoint,
    val authenticationMethod: ConnectionAuthenticationMethod,
    val jumpHost: JumpHostPreset? = null,
)

internal data class JumpHostPreset(
    val profileId: UUID,
    val endpoint: SshEndpoint,
    val authenticationMethod: ConnectionAuthenticationMethod,
)

internal fun JumpHostPreset.toPreset(): HostConnectionPreset = HostConnectionPreset(
    profileId = profileId,
    endpoint = endpoint,
    authenticationMethod = authenticationMethod,
)

internal data class OpenedTerminal(
    val hostSession: HostSession,
    val terminal: TerminalSession,
    val passwordSaveFailed: Boolean,
)

internal fun interface SshTerminalConnector {
    fun open(
        endpoint: SshEndpoint,
        authentication: SshAuthentication,
        knownHostsStore: KnownHostsStore,
        unknownHostVerifier: HostKeyVerifier,
        changedHostKeyHandler: ChangedHostKeyHandler,
        transport: TransportProvider,
    ): HostSession
}

private object MinaTerminalConnector : SshTerminalConnector {
    override fun open(
        endpoint: SshEndpoint,
        authentication: SshAuthentication,
        knownHostsStore: KnownHostsStore,
        unknownHostVerifier: HostKeyVerifier,
        changedHostKeyHandler: ChangedHostKeyHandler,
        transport: TransportProvider,
    ): HostSession = MinaSshConnection.connect(
        endpoint = endpoint,
        authentication = authentication,
        knownHostsStore = knownHostsStore,
        unknownHostVerifier = unknownHostVerifier,
        changedHostKeyHandler = changedHostKeyHandler,
        transport = transport,
    )
}

internal enum class ConnectionAuthenticationMethod(
    private val label: String,
) {
    PASSWORD("conn.auth.password"),
    PUBLIC_KEY("conn.auth.privateKey"),
    KEYBOARD_INTERACTIVE("conn.auth.keyboardInteractive"),
    SSH_AGENT("conn.auth.agent"),
    ;

    override fun toString(): String = I18n.t(label)
}
