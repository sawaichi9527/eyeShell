package io.github.sawaichi9527.eyeshell.terminal.jediterm

import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reported "semi freeze" after `apt`/`autoremove`: a program that owns a scroll region (for
 * example a status line on the last row) exits without restoring the margins. The shell then writes
 * on the real last row, which is below the bottom margin, so a line feed there cannot scroll: every
 * new line overwrites the last one and `Enter` appears to do nothing. `Reset Terminal` fixed it by
 * hand; the fork now repairs the margins at the moment the line feed proves them stale.
 */
class LineFeedBelowScrollRegionTest {
    @Test
    fun `a line feed below a stale region restores the full height`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            // A program that used rows 1..9 and left the last row for its status line.
            onEdt { view.setScrollRegionForTest(top = 1, bottom = 9) }
            onEdt { view.setCursorForTest(x = 1, y = 10) }
            assertTrue(state(view).contains("cursorBelowRegion=true"), state(view))
            assertEquals(0L, view.staleRegionRepairCount)

            // The shell prints its next line: without the repair this would overwrite row 10 forever.
            onEdt { view.lineFeedForTest() }

            assertTrue(
                state(view).contains("scrollTop=1 scrollBottom=10"),
                "the margins must be restored so the next line feeds scroll: ${state(view)}",
            )
            assertEquals(1L, view.staleRegionRepairCount, "one repair per stale region")
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `the repaired terminal scrolls on the following line feeds`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setScrollRegionForTest(top = 1, bottom = 9) }
            onEdt { view.setCursorForTest(x = 1, y = 10) }
            onEdt { view.lineFeedForTest() }

            repeat(3) { onEdt { view.lineFeedForTest() } }

            assertTrue(
                state(view).contains("scrollTop=1 scrollBottom=10"),
                "a healthy terminal must stay full height: ${state(view)}",
            )
            assertEquals(1L, view.staleRegionRepairCount, "only the first line feed found the stale state")
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `a program that still owns its region keeps it`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setScrollRegionForTest(top = 1, bottom = 5) }
            // The cursor stays inside the region: a progress display scrolling its own area.
            onEdt { view.setCursorForTest(x = 1, y = 5) }

            onEdt { view.lineFeedForTest() }

            assertTrue(
                state(view).contains("scrollTop=1 scrollBottom=5"),
                "a region the program owns must not be touched: ${state(view)}",
            )
            assertEquals(0L, view.staleRegionRepairCount)
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `a full-height terminal is never repaired`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setCursorForTest(x = 1, y = 10) }

            onEdt { view.lineFeedForTest() }

            assertEquals(0L, view.staleRegionRepairCount, "no region was left behind")
            assertTrue(state(view).contains("scrollTop=1 scrollBottom=10"), state(view))
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `the diagnostics name the stale state`() {
        val view = onEdt { JediTermTerminalView(columns = 80, rows = 10) }
        try {
            onEdt { view.setScrollRegionForTest(top = 1, bottom = 9) }
            onEdt { view.setCursorForTest(x = 3, y = 10) }

            val before = state(view)
            assertTrue(before.contains("scrollBottom=9"), before)
            assertTrue(before.contains("cursorBelowRegion=true"), before)
            assertTrue(before.contains("originMode=false"), before)
            assertTrue(before.contains("repairs=0"), before)
        } finally {
            onEdt { view.close() }
        }
    }

    private fun state(view: JediTermTerminalView): String = view.terminalState()

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
