package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.PresentedHostKey
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class SshConnectionController : AutoCloseable {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val closed = AtomicBoolean()
    private var connectionTask: Future<*>? = null

    fun connect(owner: EyeShellWindow) {
        check(SwingUtilities.isEventDispatchThread()) { "The SSH connection dialog must open on the Swing EDT" }
        if (closed.get() || connectionTask?.isDone == false) return
        val request = showConnectionDialog(owner) ?: return

        owner.setConnectionState("Connecting to ${request.endpoint.displayName}...", true)
        connectionTask = executor.submit {
            request.use {
                try {
                    val connection = MinaSshConnection.connect(
                        endpoint = request.endpoint,
                        password = request.password,
                        hostKeyVerifier = HostKeyVerifier { confirmHostKey(owner, it) },
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
                            JOptionPane.showMessageDialog(
                                owner,
                                failure.message ?: failure.javaClass.simpleName,
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

                Accept this key for this session only?
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
        connectionTask?.cancel(true)
        executor.shutdownNow()
    }

    private fun showConnectionDialog(owner: EyeShellWindow): ConnectionRequest? {
        val hostField = JTextField(24)
        val portSpinner = JSpinner(SpinnerNumberModel(22, 1, 65535, 1))
        val usernameField = JTextField(24)
        val passwordField = JPasswordField(24)
        val panel = JPanel(GridBagLayout())
        addField(panel, 0, "Host", hostField)
        addField(panel, 1, "Port", portSpinner)
        addField(panel, 2, "Username", usernameField)
        addField(panel, 3, "Password", passwordField)

        if (JOptionPane.showConfirmDialog(
                owner,
                panel,
                "Connect with SSH",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
            ) != JOptionPane.OK_OPTION
        ) {
            Arrays.fill(passwordField.password, '\u0000')
            return null
        }

        val password = passwordField.password
        return try {
            ConnectionRequest(
                endpoint = SshEndpoint(
                    host = hostField.text.trim(),
                    port = portSpinner.value as Int,
                    username = usernameField.text.trim(),
                ),
                password = password,
            ).also {
                require(password.isNotEmpty()) { "SSH password must not be empty" }
            }
        } catch (failure: IllegalArgumentException) {
            Arrays.fill(password, '\u0000')
            JOptionPane.showMessageDialog(owner, failure.message, "Invalid SSH connection", JOptionPane.ERROR_MESSAGE)
            null
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
        val password: CharArray,
    ) : AutoCloseable {
        override fun close() {
            Arrays.fill(password, '\u0000')
        }
    }
}
