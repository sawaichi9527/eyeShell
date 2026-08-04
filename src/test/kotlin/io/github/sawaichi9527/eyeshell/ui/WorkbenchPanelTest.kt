package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.ssh.ExecResult
import io.github.sawaichi9527.eyeshell.ssh.HostSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.io.Writer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class WorkbenchPanelTest {
    @Test
    fun `connect action stays available after multiple terminal tabs attach`() {
        SwingUtilities.invokeAndWait {
            val connectCount = AtomicInteger()
            val hostsCount = AtomicInteger()
            val panel = WorkbenchPanel(connectCount::incrementAndGet, hostsCount::incrementAndGet)
            val connectButton = panel.findByName("connectButton") as? JButton
            val hostsButton = panel.findByName("hostsButton") as? JButton
            val connectionStatus = panel.findByName("connectionStatus") as? javax.swing.JLabel
            val sessionTabs = panel.findByName("sessionTabs") as? JTabbedPane
            val firstView = TestTerminalView(JPanel().apply { name = "firstTerminal" })
            val secondView = TestTerminalView(JPanel().apply { name = "secondTerminal" })
            val firstSession = TestTerminalSession("first")
            val secondSession = TestTerminalSession("second")

            assertNotNull(connectButton)
            assertNotNull(connectionStatus)
            assertNotNull(hostsButton)
            assertTrue(connectButton!!.isEnabled)
            assertTrue(hostsButton!!.isEnabled)
            assertEquals("Not connected", connectionStatus!!.text)
            connectButton.doClick()
            assertEquals(1, connectCount.get())
            hostsButton.doClick()
            assertEquals(1, hostsCount.get())

            val firstPage = TerminalSessionPage(panel, firstView, TestHostSession(firstSession)).also { it.attach() }
            panel.addSession(firstSession.name, firstPage.component, firstPage::close)
            val secondPage = TerminalSessionPage(panel, secondView, TestHostSession(secondSession)).also { it.attach() }
            panel.addSession(secondSession.name, secondPage.component, secondPage::close)

            assertSame(firstSession, firstView.attachedSession)
            assertSame(secondSession, secondView.attachedSession)
            assertTrue(connectButton.isEnabled)
            assertTrue(hostsButton.isEnabled)
            assertEquals("Connected to second", connectionStatus.text)
            assertEquals(2, panel.sessionCount)
            assertEquals(2, sessionTabs!!.tabCount)
            assertSame(secondPage.component, sessionTabs.selectedComponent)

            sessionTabs.selectedComponent = firstPage.component
            assertEquals("Connected to first", connectionStatus.text)
            panel.closeSessions()
        }
    }

    @Test
    fun `terminal view is embedded without exposing its implementation to the workbench`() {
        SwingUtilities.invokeAndWait {
            val terminal = JPanel().apply { name = "testTerminal" }
            val panel = WorkbenchPanel()
            val view = TestTerminalView(terminal)
            val session = TestTerminalSession()
            val page = TerminalSessionPage(panel, view, TestHostSession(session)).also { it.attach() }
            panel.addSession(session.name, page.component, page::close)

            assertSame(terminal, panel.findByName("testTerminal"))
            assertSame(page.component, terminal.parent)
            panel.closeSessions()
        }
    }

    @Test
    fun `closing one terminal tab leaves the other session active`() {
        SwingUtilities.invokeAndWait {
            val panel = WorkbenchPanel(connectAction = {})
            val firstView = TestTerminalView(JPanel())
            val secondView = TestTerminalView(JPanel())
            val firstPage = TerminalSessionPage(panel, firstView, TestHostSession(TestTerminalSession("first"))).also { it.attach() }
            val secondPage = TerminalSessionPage(panel, secondView, TestHostSession(TestTerminalSession("second"))).also { it.attach() }
            panel.addSession("first", firstPage.component, firstPage::close)
            panel.addSession("second", secondPage.component, secondPage::close)
            val tabs = panel.findByName("sessionTabs") as JTabbedPane
            val status = panel.findByName("connectionStatus") as javax.swing.JLabel
            val connect = panel.findByName("connectButton") as JButton
            val firstClose = (tabs.getTabComponentAt(0) as Container).findByName("closeSessionButton") as JButton

            panel.setConnectionState("Connecting...", true)
            firstClose.doClick()

            assertEquals(1, firstView.closeCount)
            assertEquals(0, secondView.closeCount)
            assertEquals(1, panel.sessionCount)
            assertSame(secondPage.component, tabs.selectedComponent)
            assertEquals("Connecting...", status.text)
            assertFalse(connect.isEnabled)

            panel.setConnectionState("Connection failed", false)
            assertEquals("Connected to second", status.text)
            assertTrue(connect.isEnabled)

            panel.closeSessions()
            assertEquals(1, firstView.closeCount)
            assertEquals(1, secondView.closeCount)
            assertEquals(0, panel.sessionCount)
            assertEquals(1, tabs.tabCount)
            assertEquals("Start", tabs.getTitleAt(0))
        }
    }

    @Test
    fun `closing all tabs continues after one close failure`() {
        SwingUtilities.invokeAndWait {
            val panel = WorkbenchPanel()
            var secondClosed = false
            val sharedFailure = IllegalStateException("close failed")
            panel.addSession("first", JPanel(), { throw sharedFailure })
            panel.addSession("second", JPanel(), {
                secondClosed = true
                throw sharedFailure
            })

            val failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException::class.java,
                panel::closeSessions,
            )

            val tabs = panel.findByName("sessionTabs") as JTabbedPane
            assertSame(sharedFailure, failure)
            assertTrue(secondClosed)
            assertEquals(0, panel.sessionCount)
            assertEquals(1, tabs.tabCount)
            assertEquals("Start", tabs.getTitleAt(0))
        }
    }

    @Test
    fun `monitor stays visible while tool dock toggles`() {
        SwingUtilities.invokeAndWait {
            val panel = WorkbenchPanel()
            val monitor = panel.findByName("monitorPanel")
            val terminal = panel.findByName("terminalWorkspace")
            val toolDockContent = panel.findByName("toolDockContent")
            val toolDockToggle = panel.findByName("toolDockToggle") as? JButton
            val hostsButton = panel.findByName("hostsButton") as? JButton
            val commandBar = panel.findByName("commandBar") as? JPanel
            val workbenchSplit = panel.findByName("workbenchSplit") as? JSplitPane
            val sessionTabs = panel.findByName("sessionTabs") as? JTabbedPane
            val sessionWorkspace = panel.findByName("sessionWorkspace") as? JPanel

            assertNotNull(monitor)
            assertNotNull(terminal)
            assertNotNull(toolDockContent)
            assertNotNull(toolDockToggle)
            assertNotNull(hostsButton)
            assertNotNull(commandBar)
            assertNotNull(workbenchSplit)
            assertNotNull(sessionTabs)
            assertNotNull(sessionWorkspace)
            assertSame(monitor, workbenchSplit!!.leftComponent)
            assertSame(sessionWorkspace, workbenchSplit.rightComponent)
            assertTrue(sessionWorkspace!!.containsComponent(sessionTabs!!))
            assertFalse(sessionTabs.containsComponent(monitor!!))
            assertSame(commandBar, toolDockToggle!!.parent)
            assertSame(commandBar, hostsButton!!.parent)
            assertEquals("Show tools", toolDockToggle.text)

            panel.size = Dimension(1280, 800)
            panel.layoutTree()
            assertTrue(monitor.isVisible)
            assertTrue(terminal!!.isVisible)
            assertTrue(monitor.width > 0)
            assertTrue(terminal.width > monitor.width)
            assertFalse(panel.isToolDockExpanded)
            assertFalse(toolDockContent!!.isVisible)
            assertEquals("Show tools", toolDockToggle.text)

            toolDockToggle.doClick()
            panel.layoutTree()
            assertTrue(panel.isToolDockExpanded)
            assertTrue(toolDockContent.isVisible)
            assertTrue(toolDockContent.height > 0)
            assertEquals("Hide tools", toolDockToggle.text)
            assertTrue(monitor.isVisible)
            assertTrue(monitor.width > 0)

            toolDockToggle.doClick()
            panel.layoutTree()
            assertFalse(panel.isToolDockExpanded)
            assertFalse(toolDockContent.isVisible)
            assertEquals("Show tools", toolDockToggle.text)
            assertTrue(monitor.isVisible)
        }
    }

    @Test
    fun `natural exit marks the tab exited and preserves the view`() {
        val panel = onEdt { WorkbenchPanel() }
        val view = TestTerminalView(JPanel())
        val session = BlockingExitTerminalSession("exited-session")
        val page = TerminalSessionPage(panel, view, TestHostSession(session))
        onEdt {
            page.attach()
            panel.addSession(session.name, page.component, page::close)
            page.startExitMonitor { panel.updateSessionStatus(page.component, SessionStatus.EXITED) }
        }
        val tabs = onEdt { panel.findByName("sessionTabs") as JTabbedPane }
        val status = onEdt { panel.findByName("connectionStatus") as JLabel }
        val chip = onEdt { (tabs.getTabComponentAt(0) as Container).findByName("sessionStatusLabel") as JLabel }

        onEdt {
            assertEquals("Connected", chip.text)
            assertEquals("Connected to exited-session", status.text)
            assertEquals(0, view.closeCount)
            assertEquals(1, panel.sessionCount)
        }

        session.signalNaturalExit()
        assertTrue(session.awaitExitCompleted(5, TimeUnit.SECONDS))
        await(Duration.ofSeconds(5)) { onEdt { chip.text == "Exited" } }

        onEdt {
            assertEquals("Exited", chip.text)
            assertEquals("Exited: exited-session", status.text)
            assertEquals(0, view.closeCount)
            assertEquals(1, panel.sessionCount)
            assertEquals(1, tabs.tabCount)
        }

        onEdt {
            (tabs.getTabComponentAt(0) as Container).findByName("closeSessionButton") as JButton
        }.doClick()
        onEdt {
            assertEquals(1, view.closeCount)
            assertEquals(0, panel.sessionCount)
            assertEquals(1, tabs.tabCount)
            assertEquals("Start", tabs.getTitleAt(0))
        }
    }

    @Test
    fun `closing a tab suppresses the pending natural exit update`() {
        val panel = onEdt { WorkbenchPanel() }
        val view = TestTerminalView(JPanel())
        val session = BlockingExitTerminalSession("closed-session")
        val page = TerminalSessionPage(panel, view, TestHostSession(session))
        val updates = AtomicInteger()
        onEdt {
            page.attach()
            panel.addSession(session.name, page.component, page::close)
            page.startExitMonitor { updates.incrementAndGet() }
        }

        onEdt { panel.closeSessions() }
        session.signalNaturalExit()
        assertTrue(session.awaitExitCompleted(5, TimeUnit.SECONDS))
        await(Duration.ofSeconds(5)) { onEdt { session.exitObserved } }
        onEdt { assertEquals(0, updates.get()) }
    }

    @Test
    fun `one exited tab does not affect another live tab`() {
        val panel = onEdt { WorkbenchPanel() }
        val firstView = TestTerminalView(JPanel())
        val secondView = TestTerminalView(JPanel())
        val firstSession = BlockingExitTerminalSession("first")
        val secondSession = TestTerminalSession("second")
        val firstPage = TerminalSessionPage(panel, firstView, TestHostSession(firstSession))
        val secondPage = TerminalSessionPage(panel, secondView, TestHostSession(secondSession))
        onEdt {
            firstPage.attach()
            secondPage.attach()
            panel.addSession("first", firstPage.component, firstPage::close)
            panel.addSession("second", secondPage.component, secondPage::close)
            firstPage.startExitMonitor { panel.updateSessionStatus(firstPage.component, SessionStatus.EXITED) }
        }
        val tabs = onEdt { panel.findByName("sessionTabs") as JTabbedPane }
        val status = onEdt { panel.findByName("connectionStatus") as JLabel }
        val firstChip = onEdt { (tabs.getTabComponentAt(0) as Container).findByName("sessionStatusLabel") as JLabel }
        val secondChip = onEdt { (tabs.getTabComponentAt(1) as Container).findByName("sessionStatusLabel") as JLabel }

        firstSession.signalNaturalExit()
        assertTrue(firstSession.awaitExitCompleted(5, TimeUnit.SECONDS))
        await(Duration.ofSeconds(5)) { onEdt { firstChip.text == "Exited" } }

        onEdt {
            assertEquals("Exited", firstChip.text)
            assertEquals("Connected", secondChip.text)
            assertEquals("Connected to second", status.text)
            assertEquals(0, firstView.closeCount)
            assertEquals(0, secondView.closeCount)
        }

        onEdt { panel.closeSessions() }
        onEdt {
            assertEquals(1, secondView.closeCount)
            assertEquals(0, panel.sessionCount)
            assertEquals(1, tabs.tabCount)
            assertEquals("Start", tabs.getTitleAt(0))
        }
    }

    @Test
    fun `connection failure does not overwrite an exited selected session`() {
        val panel = onEdt { WorkbenchPanel(connectAction = {}) }
        val view = TestTerminalView(JPanel())
        val session = BlockingExitTerminalSession("exited-session")
        val page = TerminalSessionPage(panel, view, TestHostSession(session))
        onEdt {
            page.attach()
            panel.addSession(session.name, page.component, page::close)
            page.startExitMonitor { panel.updateSessionStatus(page.component, SessionStatus.EXITED) }
        }
        val status = onEdt { panel.findByName("connectionStatus") as JLabel }
        val connectButton = onEdt { panel.findByName("connectButton") as JButton }

        session.signalNaturalExit()
        assertTrue(session.awaitExitCompleted(5, TimeUnit.SECONDS))
        await(Duration.ofSeconds(5)) { onEdt { status.text == "Exited: exited-session" } }

        onEdt { panel.setConnectionState("Connection failed", false) }
        onEdt {
            assertEquals("Exited: exited-session", status.text)
            assertTrue(connectButton.isEnabled)
        }
        onEdt { panel.closeSessions() }
    }

    @Test
    fun `monitor selection hook fires when the selected tab changes`() {
        SwingUtilities.invokeAndWait {
            val selected = mutableListOf<String>()
            val panel = WorkbenchPanel(monitorSelectionChanged = { component ->
                selected += component.name ?: "unnamed"
            })
            val first = JPanel().apply { name = "firstComponent" }
            val second = JPanel().apply { name = "secondComponent" }
            panel.addSession("first", first, {})
            panel.addSession("second", second, {})
            val tabs = panel.findByName("sessionTabs") as JTabbedPane

            tabs.selectedComponent = first
            tabs.selectedComponent = second

            assertTrue(selected.contains("firstComponent"))
            assertTrue(selected.contains("secondComponent"))
            panel.closeSessions()
        }
    }

    private fun Container.findByName(componentName: String): Component? {
        components.forEach { component ->
            if (component.name == componentName) return component
            if (component is Container) {
                component.findByName(componentName)?.let { return it }
            }
        }
        return null
    }

    private fun await(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Workbench state did not settle within $timeout" }
            Thread.sleep(10)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }

    private fun Container.layoutTree() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.layoutTree() }
    }

    private fun Container.containsComponent(target: Component): Boolean =
        components.any { component ->
            component === target || (component is Container && component.containsComponent(target))
        }

    private class TestTerminalView(
        override val component: JComponent,
    ) : TerminalView {
        var attachedSession: TerminalSession? = null
        var closeCount = 0

        override fun attach(session: TerminalSession) {
            attachedSession = session
        }

        override fun captureAllOutput(): TerminalOutputSnapshot = TerminalOutputSnapshot {}

        override fun setContextActions(actions: TerminalContextActions) = Unit

        override fun selectVisible() = Unit

        override fun selectAllOutput() = Unit

        override fun showSearch() = Unit

        override fun setHighlightRules(rules: List<TerminalHighlightRule>) = Unit

        override fun clearScrollback() = Unit

        override fun close() { closeCount++ }
    }

    private class TestTerminalSession(
        override val name: String = "test-session",
    ) : TerminalSession {
        override val isOpen: Boolean = true

        override fun read(buffer: CharArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = false

        override fun awaitExit(): Int = 0

        override fun close() = Unit
    }

    private class TestHostSession(
        private val terminal: TerminalSession,
        override val endpoint: io.github.sawaichi9527.eyeshell.ssh.SshEndpoint =
            io.github.sawaichi9527.eyeshell.ssh.SshEndpoint("test.example", 22, "operator"),
    ) : HostSession {
        private var closed = false

        override fun openTerminal(columns: Int, rows: Int): TerminalSession = terminal

        override fun execute(command: String): ExecResult = ExecResult(0, "")

        override fun isOpen(): Boolean = !closed

        override fun close() { closed = true }
    }

    private class BlockingExitTerminalSession(
        override val name: String,
    ) : TerminalSession {
        private val exitSignal = CountDownLatch(1)
        private val exitObservedLatch = CountDownLatch(1)
        @Volatile
        override var isOpen: Boolean = true

        override fun read(buffer: CharArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray) = Unit

        override fun write(text: String) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun ready(): Boolean = false

        override fun awaitExit(): Int {
            exitSignal.await()
            exitObservedLatch.countDown()
            return 0
        }

        val exitObserved: Boolean
            get() = exitObservedLatch.count == 0L

        fun signalNaturalExit() {
            isOpen = false
            exitSignal.countDown()
        }

        fun awaitExitCompleted(timeout: Long, unit: TimeUnit): Boolean =
            exitObservedLatch.await(timeout, unit)

        override fun close() {
            isOpen = false
            exitSignal.countDown()
        }
    }
}
