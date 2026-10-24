package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.storage.CommandSnippet
import io.github.sawaichi9527.eyeshell.storage.CommandSnippetCatalog
import io.github.sawaichi9527.eyeshell.storage.CommandSnippetDraft
import java.awt.Container
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsPanelTest {
    @Test
    fun `renders saved commands and sends the selected one to the session`() {
        val catalog = FakeCommandCatalog(
            listOf(
                CommandSnippet(1, "Disk", "df -h"),
                CommandSnippet(2, "Kernel", "uname -a"),
            ),
        )
        val controller = CommandsController(catalog)
        val sent = mutableListOf<String>()
        lateinit var panel: CommandsPanel

        SwingUtilities.invokeAndWait {
            panel = CommandsPanel()
            panel.bind(controller)
            panel.bindSession { sent += it }
        }

        try {
            assertTrue(await(5) { onEdt { panel.snippetNames() == listOf("Disk", "Kernel") } })
            SwingUtilities.invokeAndWait {
                val list = panel.findByName("commandSnippetList") as JList<*>
                list.selectedIndex = 1
                assertTrue(panel.runSelected())
            }
            assertEquals(listOf("uname -a"), sent)
            assertTrue(onEdt { panel.findByName("commandSnippetPreview")!!.let { (it as JTextArea).text } == "uname -a" })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `run is rejected when no session is bound`() {
        val catalog = FakeCommandCatalog(listOf(CommandSnippet(1, "Disk", "df -h")))
        val controller = CommandsController(catalog)
        lateinit var panel: CommandsPanel

        SwingUtilities.invokeAndWait {
            panel = CommandsPanel()
            panel.bind(controller)
        }

        try {
            assertTrue(await(5) { onEdt { panel.snippetNames() == listOf("Disk") } })
            SwingUtilities.invokeAndWait {
                val list = panel.findByName("commandSnippetList") as JList<*>
                list.selectedIndex = 0
                assertFalse(panel.runSelected())
                assertEquals(
                    "Connect a session to run a command.",
                    (panel.findByName("commandSnippetStatus") as JLabel).text,
                )
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `controller mutates snippets off the EDT and publishes on the EDT`() {
        val catalog = FakeCommandCatalog()
        val controller = CommandsController(catalog)
        val createdLatch = CountDownLatch(1)
        val listedLatch = CountDownLatch(1)
        val callbackOnEdt = AtomicBoolean()
        var created: CommandSnippet? = null

        try {
            controller.create(CommandSnippetDraft("  Tail log  ", "tail -n 100 /var/log/syslog")) { result ->
                callbackOnEdt.set(SwingUtilities.isEventDispatchThread())
                created = result.getOrThrow()
                createdLatch.countDown()
            }
            assertTrue(createdLatch.await(5, TimeUnit.SECONDS))
            assertTrue(callbackOnEdt.get())
            assertEquals("Tail log", created!!.name)
            assertFalse(catalog.calledOnEdt.get())

            controller.load { result ->
                assertEquals(listOf("Tail log"), result.getOrThrow().map { it.name })
                listedLatch.countDown()
            }
            assertTrue(listedLatch.await(5, TimeUnit.SECONDS))
        } finally {
            controller.close()
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }

    private fun await(timeoutSeconds: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun Container.findByName(name: String): java.awt.Component? {
        components.forEach { component ->
            if (component.name == name) return component
            if (component is Container) component.findByName(name)?.let { return it }
        }
        return null
    }

    private class FakeCommandCatalog(
        initial: List<CommandSnippet> = emptyList(),
    ) : CommandSnippetCatalog {
        private val snippets = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1
        val calledOnEdt = AtomicBoolean()

        override fun listCommandSnippets(): List<CommandSnippet> {
            calledOnEdt.set(SwingUtilities.isEventDispatchThread())
            return snippets.sortedBy { it.name }
        }

        override fun createCommandSnippet(draft: CommandSnippetDraft): CommandSnippet {
            val snippet = CommandSnippet(nextId++, draft.normalizedForTest().name, draft.normalizedForTest().command)
            snippets += snippet
            return snippet
        }

        override fun updateCommandSnippet(id: Long, draft: CommandSnippetDraft): CommandSnippet {
            val index = snippets.indexOfFirst { it.id == id }
            val normalized = draft.normalizedForTest()
            val updated = CommandSnippet(id, normalized.name, normalized.command)
            snippets[index] = updated
            return updated
        }

        override fun deleteCommandSnippet(id: Long) {
            snippets.removeIf { it.id == id }
        }
    }
}

private fun CommandSnippetDraft.normalizedForTest(): CommandSnippetDraft =
    CommandSnippetDraft(name.trim(), command.trim())
