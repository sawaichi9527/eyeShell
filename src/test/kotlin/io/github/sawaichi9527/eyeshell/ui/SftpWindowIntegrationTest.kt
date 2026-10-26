package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.KnownHostsStore
import io.github.sawaichi9527.eyeshell.ssh.MinaSshConnection
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Exercises the workbench-to-SFTP wiring (tab selection, asynchronous subsystem opening, panel
 * binding) against a real SSH server with an SFTP subsystem, rather than a fake controller.
 */
class SftpWindowIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var server: SshServer
    private lateinit var password: CharArray
    private lateinit var remoteRoot: Path

    @BeforeEach
    fun startServer() {
        password = UUID.randomUUID().toString().toCharArray()
        remoteRoot = Files.createTempDirectory("sftp-window-root")
        Files.writeString(remoteRoot.resolve("readme.txt"), "hello")
        Files.createDirectory(remoteRoot.resolve("logs"))
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(temporaryDirectory.resolve("host-key.ser")).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { username, candidate, _ ->
                username == USERNAME && password.contentEquals(candidate.toCharArray())
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(remoteRoot)
            start()
        }
    }

    @AfterEach
    fun stopServer() {
        server.stop(true)
        password.fill('\u0000')
        remoteRoot.toFile().deleteRecursively()
    }

    @Test
    fun `selecting a session lists its remote files in the SFTP panel`() {
        val hostSession = SshAuthentication.Password(password).use { authentication ->
            MinaSshConnection.connect(
                endpoint = SshEndpoint("127.0.0.1", server.port, USERNAME),
                authentication = authentication,
                knownHostsStore = KnownHostsStore(temporaryDirectory.resolve("known_hosts")),
                unknownHostVerifier = HostKeyVerifier { true },
                changedHostKeyHandler = ChangedHostKeyHandler { },
            )
        }
        lateinit var window: EyeShellWindow

        try {
            onEdt {
                window = EyeShellWindow(
                    terminalViewFactory = { TestTerminalView() },
                    connectAction = {},
                    closeAction = {},
                )
                window.attachTerminal(hostSession, hostSession.openTerminal(), null, null)
                (window.findByName("toolDockToggle") as JButton).doClick()
            }

            assertTrue(
                await(10, TimeUnit.SECONDS) { onEdt { window.sftpRows() > 0 } },
                "The SFTP panel never listed the remote directory",
            )
            onEdt {
                val table = window.findByName("sftpTable") as JTable
                val names = (0 until table.rowCount).map { table.getValueAt(it, 0)?.toString() }
                assertTrue(names.contains("logs/"), names.toString())
                assertTrue(names.contains("readme.txt"), names.toString())
                assertEquals("SFTP", (window.findByName("toolDockContent") as JTabbedPane).getTitleAt(0))
            }
        } finally {
            onEdt { window.dispose() }
            hostSession.close()
        }
    }

    private fun EyeShellWindow.sftpRows(): Int = (findByName("sftpTable") as? JTable)?.rowCount ?: -1

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(timeout: Long, unit: TimeUnit, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun Component.findByName(componentName: String): Component? {
        if (name == componentName) return this
        if (this is Container) {
            components.forEach { child -> child.findByName(componentName)?.let { return it } }
        }
        return null
    }

    private class TestTerminalView : TerminalView {
        override val component: JComponent = JPanel()
        override fun attach(session: TerminalSession) = Unit
        override fun captureAllOutput(): TerminalOutputSnapshot = TerminalOutputSnapshot {}
        override fun setContextActions(actions: TerminalContextActions) = Unit
        override fun selectVisible() = Unit
        override fun selectAllOutput() = Unit
        override fun showSearch() = Unit
        override fun setHighlightRules(rules: List<TerminalHighlightRule>) = Unit
        override fun clearScrollback() = Unit
        override fun close() = Unit
    }

    companion object {
        private const val USERNAME = "sftp-user"
    }
}
