package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.secrets.CredentialStoreStatus
import io.github.sawaichi9527.eyeshell.secrets.PasswordCredentialStore
import io.github.sawaichi9527.eyeshell.secrets.StoredPassword
import io.github.sawaichi9527.eyeshell.secrets.UnavailablePasswordCredentialStore
import io.github.sawaichi9527.eyeshell.storage.HostCatalog
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.util.concurrent.CountDownLatch
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            setOf("profileId", "endpoint", "authenticationMethod"),
            HostConnectionPreset::class.java.declaredFields
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
