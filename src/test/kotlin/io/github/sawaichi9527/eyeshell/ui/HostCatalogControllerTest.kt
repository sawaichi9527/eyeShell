package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreStatus
import io.github.sawaichi9527.eyeshell.secrets.PasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.SessionPasswordCache
import io.github.sawaichi9527.eyeshell.secrets.StoredPassword
import io.github.sawaichi9527.eyeshell.secrets.UnavailablePasswordCredentialStore
import io.github.sawaichi9527.eyeshell.storage.HostCatalog
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.util.concurrent.CountDownLatch
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostCatalogControllerTest {
    @Test
    fun `loads hosts off the EDT and publishes on the EDT`() {
        val host = savedHost()
        val catalog = TestCatalog(listOf(host))
        val controller = HostCatalogController(catalog, UnavailablePasswordCredentialStore()) { _, _ -> true }
        val loaded = CountDownLatch(1)
        val result = AtomicReference<List<SavedHost>>()
        val callbackOnEdt = AtomicBoolean()

        try {
            SwingUtilities.invokeAndWait {
                controller.loadHosts {
                    result.set(it)
                    callbackOnEdt.set(SwingUtilities.isEventDispatchThread())
                    loaded.countDown()
                }
            }

            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            assertFalse(catalog.listCalledOnEdt.get())
            assertTrue(callbackOnEdt.get())
            assertEquals(listOf(host), result.get())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `slow catalog load does not block the EDT and close suppresses publication`() {
        val release = CountDownLatch(1)
        val catalog = TestCatalog(listOf(savedHost()), release)
        val controller = HostCatalogController(catalog, UnavailablePasswordCredentialStore()) { _, _ -> true }
        val callbackCalled = AtomicBoolean()
        val edtServiced = CountDownLatch(1)

        SwingUtilities.invokeAndWait { controller.loadHosts { callbackCalled.set(true) } }
        assertTrue(catalog.listStarted.await(5, TimeUnit.SECONDS))
        SwingUtilities.invokeLater(edtServiced::countDown)
        assertTrue(edtServiced.await(2, TimeUnit.SECONDS), "SQLite work blocked the Swing EDT")

        controller.close()
        release.countDown()
        SwingUtilities.invokeAndWait { }
        assertFalse(callbackCalled.get())
        assertTrue(catalog.closeCalled.await(5, TimeUnit.SECONDS))
        assertTrue(catalog.closed.get())
    }

    @Test
    fun `saved host preset contains endpoint and method but no authentication secret`() {
        val preset = savedHost().copy(
            draft = savedHost().draft.copy(authenticationMethod = SavedAuthenticationMethod.PUBLIC_KEY),
        ).toPreset()

        assertEquals(SshEndpoint("example.test", 22, "operator"), preset.endpoint)
        assertEquals(savedHost().profileId, preset.profileId)
        assertEquals(ConnectionAuthenticationMethod.PUBLIC_KEY, preset.authenticationMethod)
        assertEquals(
            setOf("profileId", "endpoint", "authenticationMethod", "jumpHost"),
            HostConnectionPreset::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `saved host preset carries the jump host profile without a secret`() {
        val jump = savedHost().copy(
            id = 2,
            profileId = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
            draft = savedHost().draft.copy(name = "Bastion", authenticationMethod = SavedAuthenticationMethod.SSH_AGENT),
        )
        val target = savedHost().copy(
            draft = savedHost().draft.copy(jumpHostProfileId = jump.profileId),
        )

        val preset = target.toPreset(jump)

        assertEquals(jump.profileId, preset.jumpHost?.profileId)
        assertEquals(jump.draft.endpoint, preset.jumpHost?.endpoint)
        assertEquals(ConnectionAuthenticationMethod.SSH_AGENT, preset.jumpHost?.authenticationMethod)
        assertEquals(
            setOf("profileId", "endpoint", "authenticationMethod"),
            JumpHostPreset::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `removes saved password when profile leaves password authentication`() {
        val host = savedHost()
        val catalog = TestCatalog(listOf(host))
        val passwordStore = RecordingPasswordStore()
        val controller = HostCatalogController(catalog, passwordStore) { _, _ -> true }

        try {
            val updated = controller.updateHost(
                host,
                host.draft.copy(authenticationMethod = SavedAuthenticationMethod.PUBLIC_KEY),
            )

            assertEquals(listOf(host.profileId), passwordStore.forgotten)
            assertEquals(SavedAuthenticationMethod.PUBLIC_KEY, updated.draft.authenticationMethod)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `removes saved password when password profile is deleted`() {
        val host = savedHost()
        val catalog = TestCatalog(listOf(host))
        val passwordStore = RecordingPasswordStore()
        val controller = HostCatalogController(catalog, passwordStore) { _, _ -> true }

        try {
            controller.deleteHost(host)

            assertEquals(listOf(host.profileId), passwordStore.forgotten)
            assertEquals(listOf(host.id), catalog.deleted)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `restores saved password when catalog mutation fails`() {
        val host = savedHost()
        val catalog = TestCatalog(listOf(host), failUpdate = true)
        val passwordStore = RecordingPasswordStore(charArrayOf('k', 'e', 'e', 'p'))
        val controller = HostCatalogController(catalog, passwordStore) { _, _ -> true }

        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                controller.updateHost(
                    host,
                    host.draft.copy(authenticationMethod = SavedAuthenticationMethod.PUBLIC_KEY),
                )
            }

            assertEquals(listOf(host.profileId), passwordStore.forgotten)
            assertArrayEquals(charArrayOf('k', 'e', 'e', 'p'), passwordStore.saved.single())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `clears retrieved password when credential deletion fails`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore(charArrayOf('c', 'l', 'e', 'a', 'r'), failForget = true)
        val controller = HostCatalogController(TestCatalog(listOf(host)), passwordStore) { _, _ -> true }

        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                controller.deleteHost(host)
            }
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                passwordStore.lastRetrieved?.copyValue()
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `reports when catalog failure password restoration also fails`() {
        val host = savedHost()
        val catalog = TestCatalog(listOf(host), failUpdate = true)
        val passwordStore = RecordingPasswordStore(charArrayOf('k', 'e', 'e', 'p'), failSave = true)
        val controller = HostCatalogController(catalog, passwordStore) { _, _ -> true }

        try {
            val failure = org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) {
                controller.updateHost(
                    host,
                    host.draft.copy(authenticationMethod = SavedAuthenticationMethod.PUBLIC_KEY),
                )
            }
            assertEquals(
                "The profile was unchanged, but its saved password could not be restored.",
                failure.message,
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun `blank editor password keeps the saved password`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore(charArrayOf('o', 'l', 'd'))
        val controller = HostCatalogController(TestCatalog(listOf(host)), passwordStore) { _, _ -> true }

        try {
            val action = controller.applyEditorCredentials(host, editor(host, null, clear = false))

            assertEquals(CredentialAction.NONE, action)
            assertTrue(passwordStore.saved.isEmpty())
            assertTrue(passwordStore.forgotten.isEmpty())
            val kept = passwordStore.retrieve(host.profileId)?.copyValue()
            assertArrayEquals(charArrayOf('o', 'l', 'd'), kept)
            kept?.fill('\u0000')
        } finally {
            controller.close()
        }
    }

    @Test
    fun `entered editor password is saved and the buffer is zeroed`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore()
        val controller = HostCatalogController(TestCatalog(listOf(host)), passwordStore) { _, _ -> true }
        val secret = charArrayOf('n', 'e', 'w')

        try {
            val action = controller.applyEditorCredentials(host, editor(host, secret, clear = false))

            assertEquals(CredentialAction.SAVED, action)
            assertArrayEquals(charArrayOf('n', 'e', 'w'), passwordStore.saved.single())
            assertArrayEquals(charArrayOf('\u0000', '\u0000', '\u0000'), secret)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `clear request forgets the saved password`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore(charArrayOf('o', 'l', 'd'))
        val controller = HostCatalogController(TestCatalog(listOf(host)), passwordStore) { _, _ -> true }

        try {
            val action = controller.applyEditorCredentials(host, editor(host, null, clear = true))

            assertEquals(CredentialAction.CLEARED, action)
            assertEquals(listOf(host.profileId), passwordStore.forgotten)
            assertTrue(passwordStore.saved.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `clear request forgets the saved password and the session password`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore(charArrayOf('o', 'l', 'd'))
        val sessionPasswords = SessionPasswordCache()
        val controller = HostCatalogController(
            TestCatalog(listOf(host)),
            passwordStore,
            sessionPasswords = sessionPasswords,
        ) { _, _ -> true }

        try {
            sessionPasswords.put(host.profileId, charArrayOf('s', 'e', 's', 's', 'i', 'o', 'n'))
            val action = controller.applyEditorCredentials(host, editor(host, null, clear = true))

            assertEquals(CredentialAction.CLEARED, action)
            assertEquals(listOf(host.profileId), passwordStore.forgotten)
            assertFalse(sessionPasswords.contains(host.profileId))
        } finally {
            sessionPasswords.close()
            controller.close()
        }
    }

    @Test
    fun `editor password is kept in memory when it cannot be saved`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore()
        val sessionPasswords = SessionPasswordCache()
        val controller = HostCatalogController(
            TestCatalog(listOf(host)),
            passwordStore,
            sessionPasswords = sessionPasswords,
        ) { _, _ -> true }
        val secret = charArrayOf('s', 'e', 's', 's', 'i', 'o', 'n')

        try {
            val action = controller.applyEditorCredentials(
                host,
                editor(host, null, clear = false, sessionPassword = secret),
            )

            assertEquals(CredentialAction.SESSION, action)
            assertTrue(passwordStore.saved.isEmpty())
            assertArrayEquals(charArrayOf('\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000', '\u0000'), secret)
            val cached = sessionPasswords.copyOf(host.profileId)
            assertArrayEquals(charArrayOf('s', 'e', 's', 's', 'i', 'o', 'n'), cached)
            cached?.fill('\u0000')
        } finally {
            sessionPasswords.close()
            controller.close()
        }
    }

    @Test
    fun `saving to the os store drops any stale session password`() {
        val host = savedHost()
        val passwordStore = RecordingPasswordStore()
        val sessionPasswords = SessionPasswordCache()
        val controller = HostCatalogController(
            TestCatalog(listOf(host)),
            passwordStore,
            sessionPasswords = sessionPasswords,
        ) { _, _ -> true }

        try {
            sessionPasswords.put(host.profileId, charArrayOf('o', 'l', 'd'))
            val action = controller.applyEditorCredentials(host, editor(host, charArrayOf('n', 'e', 'w'), clear = false))

            assertEquals(CredentialAction.SAVED, action)
            assertFalse(sessionPasswords.contains(host.profileId))
        } finally {
            sessionPasswords.close()
            controller.close()
        }
    }

    @Test
    fun `deleting a host drops its session password`() {
        val host = savedHost()
        val sessionPasswords = SessionPasswordCache()
        val controller = HostCatalogController(
            TestCatalog(listOf(host)),
            RecordingPasswordStore(),
            sessionPasswords = sessionPasswords,
        ) { _, _ -> true }

        try {
            sessionPasswords.put(host.profileId, charArrayOf('s', 'e', 's', 's', 'i', 'o', 'n'))
            controller.deleteHost(host)

            assertFalse(sessionPasswords.contains(host.profileId))
        } finally {
            sessionPasswords.close()
            controller.close()
        }
    }

    @Test
    fun `the password field shows a mask only when a password is actually stored`() {
        assertEquals(
            UiText.PASSWORD_PLACEHOLDER,
            UiText.passwordPlaceholder(passwordAuthentication = true, savedPassword = true),
        )
        assertNull(UiText.passwordPlaceholder(passwordAuthentication = true, savedPassword = false))
        assertNull(UiText.passwordPlaceholder(passwordAuthentication = false, savedPassword = true))
    }

    @Test
    fun `credential fields stay editable when the credential store is locked`() {
        val locked = hostEditorCredentialState(
            passwordAuthentication = true,
            storeStatus = CredentialStoreStatus.LOCKED,
            savedPassword = true,
            rememberSelected = false,
            clearRequested = false,
        )

        assertTrue(locked.passwordEnabled, "A locked keyring must not disable password entry")
        assertTrue(locked.rememberEnabled, "Remember stays available because saving can unlock the keyring")
        assertFalse(locked.clearEnabled)
        assertEquals(UiText.STORE_LOCKED, locked.hint)
    }

    @Test
    fun `credential fields explain an unavailable store`() {
        val unavailable = hostEditorCredentialState(
            passwordAuthentication = true,
            storeStatus = CredentialStoreStatus.UNAVAILABLE,
            savedPassword = false,
            rememberSelected = false,
            clearRequested = false,
        )

        assertTrue(unavailable.passwordEnabled)
        assertFalse(unavailable.rememberEnabled)
        assertFalse(unavailable.clearEnabled)
        assertEquals(UiText.STORE_UNAVAILABLE, unavailable.hint)
    }

    @Test
    fun `credential fields explain saving and session-only use for an available store`() {
        val saving = hostEditorCredentialState(
            passwordAuthentication = true,
            storeStatus = CredentialStoreStatus.AVAILABLE,
            savedPassword = false,
            rememberSelected = true,
            clearRequested = false,
        )
        assertEquals(UiText.STORE_WILL_SAVE, saving.hint)
        assertTrue(saving.passwordEnabled)
        assertTrue(saving.rememberEnabled)
        assertFalse(saving.clearEnabled)

        val editing = hostEditorCredentialState(
            passwordAuthentication = true,
            storeStatus = CredentialStoreStatus.AVAILABLE,
            savedPassword = true,
            rememberSelected = false,
            clearRequested = false,
        )
        assertEquals(UiText.STORE_KEEP_OR_SESSION, editing.hint)
        assertTrue(editing.clearEnabled)

        assertEquals(
            UiText.STORE_SESSION_ONLY,
            hostEditorCredentialState(
                passwordAuthentication = true,
                storeStatus = CredentialStoreStatus.AVAILABLE,
                savedPassword = false,
                rememberSelected = false,
                clearRequested = false,
            ).hint,
        )

        val clearing = hostEditorCredentialState(
            passwordAuthentication = true,
            storeStatus = CredentialStoreStatus.AVAILABLE,
            savedPassword = true,
            rememberSelected = true,
            clearRequested = true,
        )
        assertEquals(UiText.CLEAR_PENDING, clearing.hint)
        assertFalse(clearing.clearEnabled)

        val otherAuthentication = hostEditorCredentialState(
            passwordAuthentication = false,
            storeStatus = CredentialStoreStatus.AVAILABLE,
            savedPassword = true,
            rememberSelected = true,
            clearRequested = false,
        )
        assertFalse(otherAuthentication.passwordEnabled)
        assertFalse(otherAuthentication.rememberEnabled)
        assertEquals("", otherAuthentication.hint)
    }

    @Test
    fun `the saved hosts dialog shows hosts in a grouped tree table and enables host actions only for a host row`() {
        val catalog = TestCatalog(
            listOf(
                host(1, "direct"),
                host(2, "alpha", "Lab"),
                host(3, "beta", "Lab"),
                host(4, "solo", "Zeta"),
            ),
        )
        val window = AtomicReference<EyeShellWindow>()
        val controller = AtomicReference<HostCatalogController>()
        try {
            SwingUtilities.invokeAndWait {
                val owner = EyeShellWindow(terminalViewFactory = { NoopTerminalView() })
                val created = HostCatalogController(catalog, UnavailablePasswordCredentialStore()) { _, _ -> true }
                window.set(owner)
                controller.set(created)
                created.open(owner)
            }

            val dialog = awaitCatalogDialog()
            val table = findComponent(dialog, "hostCatalogTable") as JTable

            SwingUtilities.invokeAndWait {
                assertEquals(
                    listOf("direct", "Lab (2)", "alpha", "beta", "Zeta (1)", "solo"),
                    columnValues(table, HostTableModel.NAME_COLUMN),
                )
                assertEquals(
                    listOf("host1.test", "", "host2.test", "host3.test", "", "host4.test"),
                    columnValues(table, HostTableModel.HOST_COLUMN),
                )
                assertEquals(
                    listOf("operator", "", "operator", "operator", "", "operator"),
                    columnValues(table, HostTableModel.USER_COLUMN),
                )
                assertEquals(
                    listOf("Password", "", "Password", "Password", "", "Password"),
                    columnValues(table, HostTableModel.AUTH_COLUMN),
                )

                val labRow = rowOfGroup(table, "Lab")
                table.setRowSelectionInterval(labRow, labRow)
                assertFalse(findButton(dialog, "Connect").isEnabled)
                assertFalse(findButton(dialog, "Edit...").isEnabled)
                assertFalse(findButton(dialog, "Delete").isEnabled)
                assertTrue(findButton(dialog, "Add...").isEnabled)

                table.setRowSelectionInterval(0, 0)
                assertTrue(findButton(dialog, "Connect").isEnabled)
                assertTrue(findButton(dialog, "Edit...").isEnabled)
                assertTrue(findButton(dialog, "Delete").isEnabled)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                controller.get()?.close()
                window.get()?.dispose()
            }
        }
    }

    @Test
    fun `the saved hosts filter keeps matching hosts under their folder`() {
        val catalog = TestCatalog(
            listOf(
                host(1, "direct"),
                host(2, "alpha", "Lab"),
                host(3, "beta", "Lab"),
                host(4, "solo", "Zeta"),
            ),
        )
        val window = AtomicReference<EyeShellWindow>()
        val controller = AtomicReference<HostCatalogController>()
        try {
            SwingUtilities.invokeAndWait {
                val owner = EyeShellWindow(terminalViewFactory = { NoopTerminalView() })
                val created = HostCatalogController(catalog, UnavailablePasswordCredentialStore()) { _, _ -> true }
                window.set(owner)
                controller.set(created)
                created.open(owner)
            }

            val dialog = awaitCatalogDialog()
            val table = findComponent(dialog, "hostCatalogTable") as JTable
            val search = findComponent(dialog, "hostCatalogSearch") as JTextField

            SwingUtilities.invokeAndWait {
                search.text = "zeta"

                assertEquals(listOf("Zeta (1)", "solo"), columnValues(table, HostTableModel.NAME_COLUMN))
                assertTrue((findComponent(dialog, "hostCatalogStatus") as JLabel).text.contains("1 of 4"))
            }
        } finally {
            SwingUtilities.invokeAndWait {
                controller.get()?.close()
                window.get()?.dispose()
            }
        }
    }

    private fun awaitCatalogDialog(): JDialog {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var dialog: JDialog? = null
            SwingUtilities.invokeAndWait {
                dialog = Window.getWindows()
                    .filterIsInstance<JDialog>()
                    .firstOrNull { window ->
                        window.name == "hostCatalogDialog" &&
                            window.isDisplayable &&
                            window.isVisible &&
                            (findComponent(window, "hostCatalogTable") as? JTable)?.rowCount?.let { it > 0 } == true
                    }
            }
            dialog?.let { return it }
            Thread.sleep(25)
        }
        error("The saved hosts dialog did not finish loading")
    }

    private fun findComponent(root: Container, name: String): Component? {
        if (root.name == name) return root
        for (child in root.components) {
            if (child.name == name) return child
            if (child is Container) findComponent(child, name)?.let { return it }
        }
        return null
    }

    private fun findButton(root: Container, text: String): JButton {
        val found = mutableListOf<JButton>()
        collectButtons(root, text, found)
        return found.singleOrNull() ?: error("Expected exactly one '$text' button, found ${found.size}")
    }

    private fun collectButtons(root: Container, text: String, found: MutableList<JButton>) {
        for (child in root.components) {
            if (child is JButton && child.text == text) found += child
            if (child is Container) collectButtons(child, text, found)
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "EYESHELL_TEST_DIALOG_RENDER", matches = "1")
    fun `renders the saved hosts dialog for visual inspection`() {
        val catalog = TestCatalog(
            listOf(
                host(1, "Freelancer", "Lab"),
                host(2, "BoulderMesa", "Lab"),
                host(3, "Chronos", "Lab"),
                host(4, "gb10-1", "Spark"),
                host(5, "gb10-2", "Spark"),
                host(6, "spark-25d5", "Spark"),
                host(7, "Bastion"),
                host(8, "strix-halo"),
            ),
        )
        val window = AtomicReference<EyeShellWindow>()
        val controller = AtomicReference<HostCatalogController>()
        try {
            SwingUtilities.invokeAndWait {
                val owner = EyeShellWindow(terminalViewFactory = { NoopTerminalView() })
                val created = HostCatalogController(catalog, UnavailablePasswordCredentialStore()) { _, _ -> true }
                window.set(owner)
                controller.set(created)
                created.open(owner)
            }

            val dialog = awaitCatalogDialog()
            SwingUtilities.invokeAndWait {
                val pane = dialog.contentPane
                pane.setSize(dialog.width, dialog.height)
                layoutTree(pane)
                val image = java.awt.image.BufferedImage(
                    pane.width,
                    pane.height,
                    java.awt.image.BufferedImage.TYPE_INT_RGB,
                )
                val graphics = image.createGraphics()
                try {
                    pane.printAll(graphics)
                } finally {
                    graphics.dispose()
                }
                val output = java.nio.file.Path.of("build", "reports", "hosts-dialog.png")
                java.nio.file.Files.createDirectories(output.parent)
                javax.imageio.ImageIO.write(image, "png", output.toFile())
            }
        } finally {
            SwingUtilities.invokeAndWait {
                controller.get()?.close()
                window.get()?.dispose()
            }
        }
    }

    private fun layoutTree(component: java.awt.Component) {
        component.doLayout()
        if (component is java.awt.Container) component.components.forEach(::layoutTree)
    }

    private fun rowOfGroup(table: JTable, name: String): Int {
        for (row in 0 until table.rowCount) {
            val label = table.model.getValueAt(row, HostTableModel.NAME_COLUMN) as? String ?: continue
            if (label.startsWith("$name (")) return row
        }
        error("Group '$name' is not visible in the table")
    }

    private fun columnValues(table: JTable, column: Int): List<String> =
        (0 until table.rowCount).map { row -> table.model.getValueAt(row, column).toString() }

    private fun host(id: Long, name: String, group: String? = null) = SavedHost(
        id,
        UUID(id, 0),
        HostDraft(
            name = name,
            endpoint = SshEndpoint("host$id.test", 22, "operator"),
            authenticationMethod = SavedAuthenticationMethod.PASSWORD,
            group = group,
        ),
    )

    private class NoopTerminalView : TerminalView {
        override val component = JPanel()
        override fun attach(session: TerminalSession) {}
        override fun captureAllOutput() = TerminalOutputSnapshot { }
        override fun setContextActions(actions: TerminalContextActions) {}
        override fun selectVisible() {}
        override fun selectAllOutput() {}
        override fun showSearch() {}
        override fun setHighlightRules(rules: List<TerminalHighlightRule>) {}
        override fun clearScrollback() {}
        override fun close() {}
    }

    private fun editor(
        host: SavedHost,
        password: CharArray?,
        clear: Boolean,
        sessionPassword: CharArray? = null,
    ) = HostCatalogController.HostEditorResult(host.draft, password, clear, sessionPassword)

    private fun savedHost() = SavedHost(
        1,
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        HostDraft(
            name = "Lab host",
            endpoint = SshEndpoint("example.test", 22, "operator"),
            authenticationMethod = SavedAuthenticationMethod.PASSWORD,
        ),
    )

    private class TestCatalog(
        initialHosts: List<SavedHost>,
        private val release: CountDownLatch? = null,
        private val failUpdate: Boolean = false,
    ) : HostCatalog {
        private val hosts = initialHosts.toMutableList()
        val listStarted = CountDownLatch(1)
        val listCalledOnEdt = AtomicBoolean()
        val closed = AtomicBoolean()
        val closeCalled = CountDownLatch(1)
        val deleted = mutableListOf<Long>()

        override fun listHosts(): List<SavedHost> {
            listCalledOnEdt.set(SwingUtilities.isEventDispatchThread())
            listStarted.countDown()
            release?.await()
            return hosts
        }

        override fun createHost(host: HostDraft): SavedHost = error("Not used")
        override fun updateHost(id: Long, host: HostDraft): SavedHost {
            if (failUpdate) throw IllegalStateException("update failed")
            val index = hosts.indexOfFirst { it.id == id }
            return hosts[index].copy(draft = host).also { hosts[index] = it }
        }
        override fun deleteHost(id: Long) {
            deleted += id
            hosts.removeIf { it.id == id }
        }
        override fun close() {
            closed.set(true)
            closeCalled.countDown()
        }
    }

    private class RecordingPasswordStore(
        private var value: CharArray? = null,
        private val failForget: Boolean = false,
        private val failSave: Boolean = false,
    ) : PasswordCredentialStore {
        val forgotten = mutableListOf<UUID>()
        val saved = mutableListOf<CharArray>()
        var lastRetrieved: StoredPassword? = null

        override fun status() = CredentialStoreStatus.AVAILABLE
        override fun contains(profileId: UUID): Boolean = value != null
        override fun retrieve(profileId: UUID): StoredPassword? = value?.let(::StoredPassword).also {
            lastRetrieved = it
        }
        override fun save(profileId: UUID, password: CharArray) {
            if (failSave) throw IllegalStateException("save failed")
            saved += password.copyOf()
            value = password.copyOf()
        }
        override fun forget(profileId: UUID) {
            if (failForget) throw IllegalStateException("forget failed")
            forgotten += profileId
            value?.fill('\u0000')
            value = null
        }
    }
}
