package io.github.sawaichi9527.eyeshell.terminal.jediterm

import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A program that exits while a scroll region is active leaves the terminal drawing every new line on
 * that region's bottom row. Reusing the widget for a reconnect inherited the state, so the tab stayed
 * "frozen" even after a fresh SSH session.
 *
 * DECSTBM also requires the top margin to be strictly above the bottom one; xterm, Ghostty, and
 * Windows Terminal ignore a one-line region, and so does the patched JediTerm.
 */
class ReattachScrollRegionTest {
    @Test
    fun `a one-line scroll region is ignored like xterm`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setScrollRegionForTest(top = 10, bottom = 10) }

            assertTrue(
                view.terminalState().contains("scrollTop=1"),
                "a region that never scrolls must not be applied: ${view.terminalState()}",
            )
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `reattaching restores a full-height scroll region`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setScrollRegionForTest(top = 5, bottom = 10) }
            assertTrue(view.terminalState().contains("scrollTop=5"), view.terminalState())

            onEdt { view.resetForReattach() }

            assertTrue(
                view.terminalState().contains("scrollTop=1"),
                "a fresh session must start with the full height: ${view.terminalState()}",
            )
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `resetting the terminal keeps the cursor column`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setCursorForTest(x = 7, y = 4) }
            val before = cursorColumn(view)

            onEdt { view.resetForReattach() }
            assertTrue(
                cursorColumn(view) == before,
                "the reset must not shift the cursor: $before -> ${cursorColumn(view)}",
            )

            onEdt { view.resetForReattach() }
            assertTrue(
                cursorColumn(view) == before,
                "repeated resets must not accumulate a shift: $before -> ${cursorColumn(view)}",
            )
        } finally {
            onEdt { view.close() }
        }
    }

    private fun cursorColumn(view: JediTermTerminalView): String =
        view.terminalState().substringAfter("cursor=").substringBefore(",")

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
