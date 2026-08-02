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
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class SshConnectionController : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private val knownHostsStore = KnownHostsStore(EyeShellPaths.knownHostsFile())
    private val challengeDialog = AtomicReference<JDialog>()
    private var connectionTask: Future<*>? = null

    fun connect(owner: EyeShellWindow) = connect(owner, null)

    internal fun connect(owner: EyeShellWindow, preset: HostConnectionPreset?) {
        check(SwingUtilities.isEventDispatchThread()) { "The SSH connection dialog must open on the Swing EDT" }
        if (closed.get() || connectionTask?.isDone == false) return
        val request = showConnectionDialog(owner, preset) ?: return

        owner.setConnectionState("Connecting to ${request.endpoint.displayName}...", true)
        connectionTask = executor.submit {
            request.use {
                val changedHostKey = AtomicReference<ChangedHostKey>()
                try {
                    val connection = MinaSshConnection.connect(
                        endpoint = request.endpoint,
                        authentication = request.authentication,
                        knownHostsStore = knownHostsStore,
                        unknownHostVerifier = HostKeyVerifier { confirmHostKey(owner, it) },
                        changedHostKeyHandler = ChangedHostKeyHandler(changedHostKey::set),
                    )
                    val terminal = connection.openTerminal()
                    SwingUtilities.invokeLater {
                        if (owner.isDisplayable && !closed.get()) {
                            owner.attachTerminal(terminal)
                        } else {
                            terminal.close()
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
        dismissKeyboardInteractiveDialog()
        connectionTask?.cancel(true)
        executor.shutdownNow()
    }

    private fun showConnectionDialog(owner: EyeShellWindow, preset: HostConnectionPreset?): ConnectionRequest? {
        val hostField = JTextField(preset?.endpoint?.host.orEmpty(), 24)
        val portSpinner = JSpinner(SpinnerNumberModel(preset?.endpoint?.port ?: 22, 1, 65535, 1))
        val usernameField = JTextField(preset?.endpoint?.username.orEmpty(), 24)
        val authenticationField = JComboBox(ConnectionAuthenticationMethod.entries.toTypedArray()).apply {
            selectedItem = preset?.authenticationMethod ?: ConnectionAuthenticationMethod.PASSWORD
        }
        val passwordField = JPasswordField(24)
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

        fun updateAuthenticationFields() {
            val usePassword = authenticationField.selectedItem == ConnectionAuthenticationMethod.PASSWORD
            val usePublicKey = authenticationField.selectedItem == ConnectionAuthenticationMethod.PUBLIC_KEY
            passwordField.isEnabled = usePassword
            keyFileField.isEnabled = usePublicKey
            keyFileButton.isEnabled = usePublicKey
            passphraseField.isEnabled = usePublicKey
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

        if (JOptionPane.showConfirmDialog(
                owner,
                panel,
                "Connect with SSH",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            ) != JOptionPane.OK_OPTION
        ) {
            Arrays.fill(passwordField.password, '\u0000')
            Arrays.fill(passphraseField.password, '\u0000')
            return null
        }

        val password = passwordField.password
        val passphrase = passphraseField.password
        return try {
            val endpoint = SshEndpoint(
                host = hostField.text.trim(),
                port = portSpinner.value as Int,
                username = usernameField.text.trim(),
            )
            val authentication = when (authenticationField.selectedItem as ConnectionAuthenticationMethod) {
                ConnectionAuthenticationMethod.PASSWORD -> SshAuthentication.Password(password)
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
            ConnectionRequest(
                endpoint = endpoint,
                authentication = authentication,
            )
        } catch (failure: IllegalArgumentException) {
            JOptionPane.showMessageDialog(owner, failure.message, "Invalid SSH connection", JOptionPane.ERROR_MESSAGE)
            null
        } finally {
            Arrays.fill(password, '\u0000')
            Arrays.fill(passphrase, '\u0000')
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

    private class ConnectionRequest(
        val endpoint: SshEndpoint,
        val authentication: SshAuthentication,
    ) : AutoCloseable {
        override fun close() {
            authentication.close()
        }
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
}

internal data class HostConnectionPreset(
    val endpoint: SshEndpoint,
    val authenticationMethod: ConnectionAuthenticationMethod,
)

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
