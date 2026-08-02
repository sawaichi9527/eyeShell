package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKey
import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.platform.EyeShellPaths
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.KnownHostsStore
import io.github.sawaichi9527.eyeshell.ssh.KeyboardInteractiveChallenge
import io.github.sawaichi9527.eyeshell.ssh.KeyboardInteractiveResponder
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.PresentedHostKey
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreStatus
import io.github.sawaichi9527.eyeshell.secrets.PasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.secrets.StoredPassword
import io.github.sawaichi9527.eyeshell.secrets.UnavailablePasswordCredentialStore
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
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
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JCheckBox
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

internal class SshConnectionController(
    private val passwordStore: PasswordCredentialStore = UnavailablePasswordCredentialStore(),
    private val terminalConnector: SshTerminalConnector = MinaTerminalConnector,
    private val credentialGuard: ProfileCredentialGuard = ProfileCredentialGuard(),
) : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val knownHostsStore = KnownHostsStore(EyeShellPaths.knownHostsFile())
    private val connectionDialog = AtomicReference<JDialog>()
    private val challengeDialog = AtomicReference<JDialog>()
    private var connectionTask: Future<*>? = null

    fun connect(owner: EyeShellWindow) = connect(owner, null)

    internal fun connect(owner: EyeShellWindow, preset: HostConnectionPreset?) {
        check(SwingUtilities.isEventDispatchThread()) { "The SSH connection dialog must open on the Swing EDT" }
        if (closed.get() || connectionTask?.isDone == false) return
        owner.setConnectionState("Preparing SSH connection...", true)
        connectionTask = executor.submit {
            connectInBackground(owner, preset)
        }
    }

    private fun connectInBackground(owner: EyeShellWindow, preset: HostConnectionPreset?) {
        val loadedPassword = loadStoredPassword(preset)
        val storeStatus = loadedPassword.first
        var storedPassword = loadedPassword.second
        var credentialRevision = loadedPassword.third
        try {
            while (!closed.get()) {
                val password = storedPassword?.copyValue()
                val decision = try {
                    showConnectionDialogOnEdt(
                        owner, preset, password, storeStatus, storedPassword != null, credentialRevision,
                    )
                } finally {
                    password?.fill('\u0000')
                }
                when (decision) {
                    ConnectionDialogDecision.Cancel -> {
                        publishConnectionState(owner, "Not connected")
                        return
                    }
                    ConnectionDialogDecision.ForgetPassword -> {
                        try {
                            checkNotNull(preset)
                            credentialGuard.mutate(preset.profileId) { passwordStore.forget(preset.profileId) }
                            credentialRevision = credentialGuard.snapshot(preset.profileId) { Unit }.revision
                            storedPassword?.close()
                            storedPassword = null
                        } catch (_: Exception) {
                            showCredentialWarning(owner, "Could not forget the saved password.")
                        }
                    }
                    is ConnectionDialogDecision.Connect -> {
                        connectRequest(owner, decision.request)
                        return
                    }
                }
            }
        } finally {
            storedPassword?.close()
        }
    }

    internal fun loadStoredPassword(
        preset: HostConnectionPreset?,
    ): Triple<CredentialStoreStatus, StoredPassword?, Long?> {
        if (preset?.authenticationMethod != ConnectionAuthenticationMethod.PASSWORD) {
            return Triple(CredentialStoreStatus.UNAVAILABLE, null, null)
        }
        val storeStatus = safeStoreStatus()
        if (storeStatus != CredentialStoreStatus.AVAILABLE) return Triple(storeStatus, null, null)
        val snapshot = try {
            credentialGuard.snapshot(preset.profileId) { active ->
                if (active) passwordStore.retrieve(preset.profileId) else null
            }
        } catch (_: Exception) {
            return Triple(CredentialStoreStatus.UNAVAILABLE, null, null)
        }
        if (!snapshot.active) return Triple(CredentialStoreStatus.UNAVAILABLE, null, null)
        return Triple(storeStatus, snapshot.value, snapshot.revision)
    }

    private fun connectRequest(owner: EyeShellWindow, request: ConnectionRequest) {
        request.use {
            publishConnectionState(owner, "Connecting to ${request.endpoint.displayName}...", connecting = true)
            val changedHostKey = AtomicReference<ChangedHostKey>()
            try {
                val opened = openTerminal(
                    request,
                    HostKeyVerifier { confirmHostKey(owner, it) },
                    ChangedHostKeyHandler(changedHostKey::set),
                )
                SwingUtilities.invokeLater {
                    if (owner.isDisplayable && !closed.get()) {
                        owner.attachTerminal(opened.terminal)
                        if (opened.passwordSaveFailed) {
                            showCredentialWarning(owner, "Connected, but the password could not be saved.")
                        }
                    } else {
                        opened.terminal.close()
                    }
                }
            } catch (failure: Exception) {
                SwingUtilities.invokeLater {
                    if (owner.isDisplayable && !closed.get()) {
                        owner.setConnectionState("Connection failed", false)
                        val changedKey = changedHostKey.get()
                        JOptionPane.showMessageDialog(
                            owner,
                            changedKey?.let(::changedHostKeyMessage)
                                ?: (failure.message ?: failure.javaClass.simpleName),
                            "SSH connection failed",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            }
        }
    }

    internal fun openTerminal(
        request: ConnectionRequest,
        unknownHostVerifier: HostKeyVerifier,
        changedHostKeyHandler: ChangedHostKeyHandler,
    ): OpenedTerminal {
        val terminal = terminalConnector.open(
            endpoint = request.endpoint,
            authentication = request.authentication,
            knownHostsStore = knownHostsStore,
            unknownHostVerifier = unknownHostVerifier,
            changedHostKeyHandler = changedHostKeyHandler,
        )
        val saveFailed = request.passwordProfileId?.takeIf { request.rememberPassword }?.let { profileId ->
            val password = (request.authentication as? SshAuthentication.Password)?.copyValue()
            try {
                if (password == null || request.credentialRevision == null) {
                    true
                } else {
                    !credentialGuard.saveIfCurrent(profileId, request.credentialRevision) {
                        passwordStore.save(profileId, password)
                    }
                }
            } catch (_: Exception) {
                true
            } finally {
                password?.fill('\u0000')
            }
        } ?: false
        return OpenedTerminal(terminal, saveFailed)
    }

    private fun confirmHostKey(owner: EyeShellWindow, hostKey: PresentedHostKey): Boolean {
        var accepted = false
        SwingUtilities.invokeAndWait {
            if (!owner.isDisplayable || closed.get()) return@invokeAndWait
            accepted = JOptionPane.showConfirmDialog(
                owner,
                """
                Verify the SSH server host key before connecting.

                Address: ${hostKey.remoteAddress}
                Algorithm: ${hostKey.algorithm}
                Fingerprint: ${hostKey.fingerprint}

                Accept and save this key to:
                ${knownHostsStore.file}
                """.trimIndent(),
                "Verify SSH host key",
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
    }

    private fun showConnectionDialogOnEdt(
        owner: EyeShellWindow,
        preset: HostConnectionPreset?,
        storedPassword: CharArray?,
        storeStatus: CredentialStoreStatus,
        canForgetPassword: Boolean,
        credentialRevision: Long?,
    ): ConnectionDialogDecision {
        val decision = AtomicReference<ConnectionDialogDecision>(ConnectionDialogDecision.Cancel)
        SwingUtilities.invokeAndWait {
            if (!owner.isDisplayable || closed.get()) return@invokeAndWait
            decision.set(
                showConnectionDialog(
                    owner, preset, storedPassword, storeStatus, canForgetPassword, credentialRevision,
                ),
            )
        }
        return decision.get()
    }

    private fun showConnectionDialog(
        owner: EyeShellWindow,
        preset: HostConnectionPreset?,
        storedPassword: CharArray?,
        storeStatus: CredentialStoreStatus,
        canForgetPassword: Boolean,
        credentialRevision: Long?,
    ): ConnectionDialogDecision {
        val hostField = JTextField(preset?.endpoint?.host.orEmpty(), 24)
        val portSpinner = JSpinner(SpinnerNumberModel(preset?.endpoint?.port ?: 22, 1, 65535, 1))
        val usernameField = JTextField(preset?.endpoint?.username.orEmpty(), 24)
        val authenticationField = JComboBox(ConnectionAuthenticationMethod.entries.toTypedArray()).apply {
            selectedItem = preset?.authenticationMethod ?: ConnectionAuthenticationMethod.PASSWORD
        }
        val passwordField = JPasswordField(24)
        val rememberPassword = JCheckBox("Remember password in OS credential store", canForgetPassword)
        val keyFileField = JTextField(24)
        val keyFileButton = JButton("Browse...")
        val keyFilePanel = JPanel(java.awt.BorderLayout(6, 0)).apply {
            add(keyFileField, java.awt.BorderLayout.CENTER)
            add(keyFileButton, java.awt.BorderLayout.EAST)
        }
        val passphraseField = JPasswordField(24)
        val panel = JPanel(GridBagLayout())
        addField(panel, 0, "Host", hostField)
        addField(panel, 1, "Port", portSpinner)
        addField(panel, 2, "Username", usernameField)
        addField(panel, 3, "Authentication", authenticationField)
        addField(panel, 4, "Password", passwordField)
        addField(panel, 5, "Private key", keyFilePanel)
        addField(panel, 6, "Passphrase", passphraseField)
        addField(panel, 7, "", rememberPassword)
        if (preset?.authenticationMethod == ConnectionAuthenticationMethod.PASSWORD &&
            storeStatus != CredentialStoreStatus.AVAILABLE
        ) {
            addField(panel, 8, "", JLabel("OS credential store unavailable; password remains session-only."))
        } else if (storedPassword != null) {
            addField(panel, 8, "", JLabel("Saved password will be used unless a replacement is entered."))
        }

        fun updateAuthenticationFields() {
            val usePassword = authenticationField.selectedItem == ConnectionAuthenticationMethod.PASSWORD
            val usePublicKey = authenticationField.selectedItem == ConnectionAuthenticationMethod.PUBLIC_KEY
            passwordField.isEnabled = usePassword
            keyFileField.isEnabled = usePublicKey
            keyFileButton.isEnabled = usePublicKey
            passphraseField.isEnabled = usePublicKey
            rememberPassword.isEnabled = usePassword && preset != null && storeStatus == CredentialStoreStatus.AVAILABLE
        }
        authenticationField.addActionListener { updateAuthenticationFields() }
        keyFileButton.addActionListener {
            JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                dialogTitle = "Select SSH private key"
                if (showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                    keyFileField.text = selectedFile.toPath().toString()
                }
            }
        }
        updateAuthenticationFields()

        val options = if (canForgetPassword) arrayOf("Connect", "Cancel", "Forget saved password") else arrayOf("Connect", "Cancel")
        val optionPane = JOptionPane(
            panel,
            JOptionPane.PLAIN_MESSAGE,
            JOptionPane.DEFAULT_OPTION,
            null,
            options,
            "Connect",
        )
        val dialog = optionPane.createDialog(owner, "Connect with SSH")
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
            passwordField.text = ""
            passphraseField.text = ""
            return ConnectionDialogDecision.Cancel
        }

        val enteredPassword = passwordField.password
        val passphrase = passphraseField.password
        var password = enteredPassword
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
            ConnectionDialogDecision.Connect(ConnectionRequest(
                endpoint,
                authentication,
                passwordProfileId = preset?.profileId?.takeIf {
                    authenticationField.selectedItem == ConnectionAuthenticationMethod.PASSWORD
                },
                rememberPassword = rememberPassword.isSelected,
                credentialRevision = credentialRevision,
            ))
        } catch (failure: IllegalArgumentException) {
            JOptionPane.showMessageDialog(owner, failure.message, "Invalid SSH connection", JOptionPane.ERROR_MESSAGE)
            ConnectionDialogDecision.Cancel
        } finally {
            Arrays.fill(password, '\u0000')
            if (enteredPassword !== password) Arrays.fill(enteredPassword, '\u0000')
            Arrays.fill(passphrase, '\u0000')
            passwordField.text = ""
            passphraseField.text = ""
        }
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
    ) : AutoCloseable {
        override fun close() {
            authentication.close()
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
                JOptionPane.showMessageDialog(owner, message, "Credential store", JOptionPane.WARNING_MESSAGE)
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
            val dialog = optionPane.createDialog(owner, "SSH authentication challenge")
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

internal data class HostConnectionPreset(
    val profileId: UUID,
    val endpoint: SshEndpoint,
    val authenticationMethod: ConnectionAuthenticationMethod,
)

internal data class OpenedTerminal(
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
    ): TerminalSession
}

private object MinaTerminalConnector : SshTerminalConnector {
    override fun open(
        endpoint: SshEndpoint,
        authentication: SshAuthentication,
        knownHostsStore: KnownHostsStore,
        unknownHostVerifier: HostKeyVerifier,
        changedHostKeyHandler: ChangedHostKeyHandler,
    ): TerminalSession {
        val connection = MinaSshConnection.connect(
            endpoint = endpoint,
            authentication = authentication,
            knownHostsStore = knownHostsStore,
            unknownHostVerifier = unknownHostVerifier,
            changedHostKeyHandler = changedHostKeyHandler,
        )
        return try {
            connection.openTerminal()
        } catch (failure: Exception) {
            connection.close()
            throw failure
        }
    }
}

internal enum class ConnectionAuthenticationMethod(
    private val label: String,
) {
    PASSWORD("Password"),
    PUBLIC_KEY("Private key"),
    KEYBOARD_INTERACTIVE("Keyboard-interactive"),
    SSH_AGENT("SSH agent"),
    ;

    override fun toString(): String = label
}
