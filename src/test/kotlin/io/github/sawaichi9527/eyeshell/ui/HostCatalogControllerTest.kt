package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.SshEndpoint
import io.github.sawaichi9527.eyeshell.storage.HostCatalog
import io.github.sawaichi9527.eyeshell.storage.HostDraft
import io.github.sawaichi9527.eyeshell.storage.SavedAuthenticationMethod
import io.github.sawaichi9527.eyeshell.storage.SavedHost
import java.util.concurrent.CountDownLatch
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostCatalogControllerTest {
    @Test
    fun `loads hosts off the EDT and publishes on the EDT`() {
        val host = savedHost()
        val catalog = TestCatalog(listOf(host))
        val controller = HostCatalogController(catalog) { _, _ -> }
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
        val controller = HostCatalogController(catalog) { _, _ -> }
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
        assertEquals(ConnectionAuthenticationMethod.PUBLIC_KEY, preset.authenticationMethod)
        assertEquals(
            setOf("endpoint", "authenticationMethod"),
            HostConnectionPreset::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    private fun savedHost() = SavedHost(
        1,
        HostDraft(
            name = "Lab host",
            endpoint = SshEndpoint("example.test", 22, "operator"),
            authenticationMethod = SavedAuthenticationMethod.PASSWORD,
        ),
    )

    private class TestCatalog(
        private val hosts: List<SavedHost>,
        private val release: CountDownLatch? = null,
    ) : HostCatalog {
        val listStarted = CountDownLatch(1)
        val listCalledOnEdt = AtomicBoolean()
        val closed = AtomicBoolean()
        val closeCalled = CountDownLatch(1)

        override fun listHosts(): List<SavedHost> {
            listCalledOnEdt.set(SwingUtilities.isEventDispatchThread())
            listStarted.countDown()
            release?.await()
            return hosts
        }

        override fun createHost(host: HostDraft): SavedHost = error("Not used")
        override fun updateHost(id: Long, host: HostDraft): SavedHost = error("Not used")
        override fun deleteHost(id: Long) = error("Not used")
        override fun close() {
            closed.set(true)
            closeCalled.countDown()
        }
    }
}
