package io.github.sawaichi9527.eyeshell.terminal.jediterm

import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A program that exits while a scroll region is active leaves the terminal drawing every new line on
 * that region's bottom row. Reusing the widget for a reconnect inherited the state, so the tab stayed
 * "frozen" even after a fresh SSH session.
 */
class ReattachScrollRegionTest {
    @Test
    fun `reattaching restores a full-height scroll region`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setScrollRegionForTest(top = 10, bottom = 10) }
            assertTrue(view.terminalState().contains("scrollTop=10"), view.terminalState())

            onEdt { view.resetForReattach() }

            assertTrue(
                view.terminalState().contains("scrollTop=1"),
                "a fresh session must start with the full height: ${view.terminalState()}",
            )
        } finally {
            onEdt { view.close() }
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
