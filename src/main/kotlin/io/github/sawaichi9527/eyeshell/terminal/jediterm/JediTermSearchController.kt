package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalSearchResult
import com.jediterm.terminal.util.CharUtils
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class JediTermSearchController(
    private val widget: JediTermWidget,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("terminal-search-", 0).factory())
    private val generation = AtomicLong()
    private val observedRevision = AtomicLong(widget.terminalTextBuffer.getMainBufferRevision())
    private val closed = AtomicBoolean()
    private val query = JTextField(24).apply { name = "terminalSearchQuery" }
    private val matchCase = JCheckBox("Match case")
    private val status = JLabel(" ").apply { name = "terminalSearchStatus" }
    private val panel = JPanel(BorderLayout(8, 0)).apply {
        name = "terminalSearch"
        add(query, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
            add(matchCase)
            add(JButton("Previous").apply { addActionListener { widget.terminalPanel.selectPreviousCoordinateFindResult() } })
            add(JButton("Next").apply { addActionListener { widget.terminalPanel.selectNextCoordinateFindResult() } })
            add(status)
            add(JButton("Close").apply { addActionListener { hideSearch() } })
        }, BorderLayout.EAST)
    }
    private var pending: Future<*>? = null
    private var visible = false
    private val modelListener = TerminalModelListener {
        val revision = widget.terminalTextBuffer.getMainBufferRevision()
        if (observedRevision.getAndSet(revision) != revision) {
            SwingUtilities.invokeLater {
                if (!closed.get() && visible && query.text.isNotEmpty()) scheduleSearch()
            }
        }
    }

    init {
        query.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = scheduleSearch()
            override fun removeUpdate(event: DocumentEvent) = scheduleSearch()
            override fun changedUpdate(event: DocumentEvent) = scheduleSearch()
        })
        query.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when (event.keyCode) {
                    KeyEvent.VK_ESCAPE -> hideSearch()
                    KeyEvent.VK_ENTER, KeyEvent.VK_DOWN -> widget.terminalPanel.selectNextCoordinateFindResult()
                    KeyEvent.VK_UP -> widget.terminalPanel.selectPreviousCoordinateFindResult()
                }
            }
        })
        matchCase.addActionListener { scheduleSearch() }
        widget.terminalTextBuffer.addModelListener(modelListener)
    }

    fun show() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal search must open on the Swing EDT" }
        if (!visible) {
            visible = true
            widget.add(panel, BorderLayout.NORTH)
            widget.revalidate()
            widget.repaint()
        }
        query.requestFocusInWindow()
        query.selectAll()
    }

    private fun hideSearch() {
        check(SwingUtilities.isEventDispatchThread())
        if (!visible) return
        visible = false
        generation.incrementAndGet()
        pending?.cancel(true)
        widget.terminalPanel.setCoordinateFindResult(null)
        widget.remove(panel)
        widget.revalidate()
        widget.repaint()
        widget.terminalPanel.requestFocusInWindow()
    }

    private fun scheduleSearch() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal search changes must run on the Swing EDT" }
        if (closed.get()) return
        val request = generation.incrementAndGet()
        pending?.cancel(true)
        widget.terminalPanel.setCoordinateFindResult(null)
        val pattern = query.text
        if (pattern.isEmpty()) {
            status.text = " "
            return
        }
        val ignoreCase = !matchCase.isSelected
        status.text = "Searching..."
        pending = try {
            executor.schedule({
                try {
                    val snapshot = widget.terminalTextBuffer.getMainBufferSnapshot()
                    val result = searchMainBuffer(snapshot, pattern, ignoreCase)
                    SwingUtilities.invokeLater {
                        if (closed.get() || !visible || generation.get() != request) return@invokeLater
                        if (widget.terminalTextBuffer.getMainBufferRevision() != result.revision) {
                            scheduleSearch()
                            return@invokeLater
                        }
                        observedRevision.set(result.revision)
                        widget.terminalPanel.setCoordinateFindResult(result)
                        status.text = "${result.matches.size} matches"
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, SEARCH_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        visible = false
        generation.incrementAndGet()
        pending?.cancel(true)
        widget.terminalTextBuffer.removeModelListener(modelListener)
        executor.shutdownNow()
    }

    internal val component: JPanel
        get() = panel

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 150L
    }
}

internal fun searchMainBuffer(
    snapshot: MainBufferSnapshot,
    pattern: String,
    ignoreCase: Boolean,
): TerminalSearchResult {
    if (pattern.isEmpty()) return TerminalSearchResult(snapshot.revision, emptyList())
    val matches = mutableListOf<TerminalSearchResult.Match>()
    val text = StringBuilder()
    val cells = mutableListOf<SearchCell>()
    val lines = snapshot.historyLines.mapIndexed { index, line -> (-snapshot.historyLines.size + index) to line } +
        snapshot.screenLines.mapIndexed { index, line -> index to line }

    fun searchLogicalLine() {
        var offset = 0
        while (offset <= text.length - pattern.length) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (text.regionMatches(offset, pattern, 0, pattern.length, ignoreCase)) {
                val matchedCells = cells.subList(offset, offset + pattern.length)
                val spans = mutableListOf<TerminalSearchResult.Span>()
                matchedCells.forEach { cell ->
                    val last = spans.lastOrNull()
                    if (last != null && last.row == cell.row && last.endCell == cell.startCell) {
                        spans[spans.lastIndex] = TerminalSearchResult.Span(last.row, last.startCell, cell.endCell)
                    } else {
                        spans += TerminalSearchResult.Span(cell.row, cell.startCell, cell.endCell)
                    }
                }
                matches += TerminalSearchResult.Match(spans)
                offset += pattern.length
            } else {
                offset++
            }
        }
        text.setLength(0)
        cells.clear()
    }

    lines.forEach { (row, line) ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        line.text.forEachIndexed { cell, character ->
            if (character == CharUtils.DWC) {
                val previous = cells.lastOrNull()
                if (previous != null && previous.row == row) previous.endCell = cell + 1
            } else {
                text.append(character)
                cells += SearchCell(row, cell, cell + 1)
            }
        }
        if (!line.isWrapped) searchLogicalLine()
    }
    if (text.isNotEmpty()) searchLogicalLine()
    return TerminalSearchResult(snapshot.revision, matches)
}

private data class SearchCell(
    val row: Int,
    val startCell: Int,
    var endCell: Int,
)
