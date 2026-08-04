package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreException
import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreStatus
import io.github.sawaichi9527.eyeshell.secrets.PasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.ProfileCredentialGuard
import io.github.sawaichi9527.eyeshell.secrets.StoredPassword
import io.github.sawaichi9527.eyeshell.ssh.ChangedHostKeyHandler
import io.github.sawaichi9527.eyeshell.ssh.ExecResult
import io.github.sawaichi9527.eyeshell.ssh.HostKeyVerifier
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.ssh.SshAuthentication
import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import java.io.IOException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SshConnectionControllerTest {
    @Test
    fun `saves a remembered password only after terminal opening succeeds`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000051")
        val store = RecordingPasswordStore()
        val terminal = FakeTerminalSession()
        val connector = SshTerminalConnector { _, _, _, _, _ ->
            assertTrue(store.saved.isEmpty(), "Password was saved before the terminal opened")
            FakeHostSession(terminal)
        }
        val controller = SshConnectionController(store, connector)
        val request = passwordRequest(profileId, remember = true)

        try {
            val opened = controller.openTerminal(request, HostKeyVerifier { true }, ChangedHostKeyHandler { })

            assertSame(terminal, opened.terminal)
            assertFalse(opened.passwordSaveFailed)
            assertEquals(listOf(profileId), store.saved.map { it.first })
            assertArrayEquals(charArrayOf('s', 'a', 'v', 'e', 'd'), store.saved.single().second)
        } finally {
            request.close()
            store.close()
            controller.close()
        }
    }

    @Test
    fun `does not save a password when terminal opening fails`() {
        val store = RecordingPasswordStore()
        val controller = SshConnectionController(store, SshTerminalConnector { _, _, _, _, _ ->
            throw IOException("terminal failed")
        })
        val request = passwordRequest(UUID.randomUUID(), remember = true)

        try {
            assertThrows(IOException::class.java) {
                controller.openTerminal(request, HostKeyVerifier { true }, ChangedHostKeyHandler { })
            }
            assertTrue(store.saved.isEmpty())
        } finally {
            request.close()
            controller.close()
        }
    }

    @Test
    fun `keeps an opened terminal when credential persistence fails`() {
        val store = RecordingPasswordStore(failSave = true)
        val terminal = FakeTerminalSession()
        val controller = SshConnectionController(store, SshTerminalConnector { _, _, _, _, _ -> FakeHostSession(terminal) })
        val request = passwordRequest(UUID.randomUUID(), remember = true)

        try {
            val opened = controller.openTerminal(request, HostKeyVerifier { true }, ChangedHostKeyHandler { })

            assertSame(terminal, opened.terminal)
            assertTrue(opened.passwordSaveFailed)
            assertTrue(terminal.isOpen)
        } finally {
            request.close()
            controller.close()
        }
    }

    @Test
    fun `does not recreate a credential after the profile changes`() {
        val profileId = UUID.randomUUID()
        val store = RecordingPasswordStore()
        val guard = ProfileCredentialGuard()
        val controller = SshConnectionController(
            store,
            SshTerminalConnector { _, _, _, _, _ -> FakeHostSession(FakeTerminalSession()) },
            guard,
        )
        val request = passwordRequest(profileId, remember = true)

        try {
            guard.invalidate(profileId) { Unit }
            val opened = controller.openTerminal(request, HostKeyVerifier { true }, ChangedHostKeyHandler { })

            assertTrue(opened.passwordSaveFailed)
            assertTrue(store.saved.isEmpty())
            val stalePreset = HostConnectionPreset(
                profileId,
                SshEndpoint("example.test", 22, "operator"),
                ConnectionAuthenticationMethod.PASSWORD,
            )
            val loaded = controller.loadStoredPassword(stalePreset)
            assertEquals(CredentialStoreStatus.UNAVAILABLE, loaded.first)
            assertNull(loaded.second)
            assertTrue(store.retrievedProfiles.isEmpty())
        } finally {
            request.close()
            controller.close()
        }
    }

    @Test
    fun `loads credentials only for an available password profile`() {
        val profileId = UUID.fromString("00000000-0000-0000-0000-000000000052")
        val store = RecordingPasswordStore(retrieved = charArrayOf('f', 'o', 'u', 'n', 'd'))
        val controller = SshConnectionController(store)
        val passwordPreset = HostConnectionPreset(
            profileId,
            SshEndpoint("example.test", 22, "operator"),
            ConnectionAuthenticationMethod.PASSWORD,
        )

        try {
            val loaded = controller.loadStoredPassword(passwordPreset)
            loaded.second.use { password ->
                val actual = password?.copyValue()
                try {
                    assertEquals(CredentialStoreStatus.AVAILABLE, loaded.first)
                    assertArrayEquals(charArrayOf('f', 'o', 'u', 'n', 'd'), actual)
                } finally {
                    actual?.fill('\u0000')
                }
            }
            assertEquals(listOf(profileId), store.retrievedProfiles)

            val keyPreset = passwordPreset.copy(authenticationMethod = ConnectionAuthenticationMethod.PUBLIC_KEY)
            val notLoaded = controller.loadStoredPassword(keyPreset)
            assertEquals(CredentialStoreStatus.UNAVAILABLE, notLoaded.first)
            assertNull(notLoaded.second)
            assertEquals(listOf(profileId), store.retrievedProfiles)
        } finally {
            controller.close()
        }
    }

    private fun passwordRequest(profileId: UUID, remember: Boolean) = SshConnectionController.ConnectionRequest(
        endpoint = SshEndpoint("example.test", 22, "operator"),
        authentication = SshAuthentication.Password(charArrayOf('s', 'a', 'v', 'e', 'd')),
        passwordProfileId = profileId,
        rememberPassword = remember,
        credentialRevision = 0,
    )

    private class RecordingPasswordStore(
        private val failSave: Boolean = false,
        private val retrieved: CharArray? = null,
    ) : PasswordCredentialStore {
        val saved = mutableListOf<Pair<UUID, CharArray>>()
        val retrievedProfiles = mutableListOf<UUID>()

        override fun status() = CredentialStoreStatus.AVAILABLE

        override fun retrieve(profileId: UUID): StoredPassword? {
            retrievedProfiles += profileId
            return retrieved?.let(::StoredPassword)
        }

        override fun save(profileId: UUID, password: CharArray) {
            if (failSave) throw CredentialStoreException("save failed")
            saved += profileId to password.copyOf()
        }

        override fun forget(profileId: UUID) = Unit

        override fun close() {
            saved.forEach { it.second.fill('\u0000') }
            retrieved?.fill('\u0000')
        }
    }

    private class FakeTerminalSession : TerminalSession {
        override val name = "test"
        override var isOpen = true
            private set

        override fun read(buffer: CharArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray) = Unit
        override fun write(text: String) = Unit
        override fun resize(columns: Int, rows: Int) = Unit
        override fun ready(): Boolean = false
        override fun awaitExit(): Int = 0
        override fun close() { isOpen = false }
    }

    private class FakeHostSession(
        private val terminal: TerminalSession,
        override val endpoint: SshEndpoint = SshEndpoint("example.test", 22, "operator"),
    ) : HostSession {
        private var closed = false

        override fun openTerminal(columns: Int, rows: Int): TerminalSession = terminal

        override fun execute(command: String): ExecResult = ExecResult(0, "")

        override fun sftp(): io.github.sawaichi9527.eyeshell.sftp.SftpClient =
            throw UnsupportedOperationException()

        override fun isOpen(): Boolean = !closed

        override fun close() { closed = true }
    }
}
