package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalLineSnapshot
import com.jediterm.terminal.util.CharUtils

internal data class MainBufferCell(
    val row: Int,
    val startCell: Int,
    var endCell: Int,
)

internal data class MainBufferCellSpan(
    val row: Int,
    val startCell: Int,
    val endCell: Int,
)

internal fun MainBufferSnapshot.forEachLogicalLine(
    consume: (text: String, cells: List<MainBufferCell>) -> Unit,
): Unit = forEachLogicalLine(physicalLines(), consume)

internal fun MainBufferSnapshot.forEachLogicalLineInRows(
    startRow: Int,
    endRow: Int,
    consume: (text: String, cells: List<MainBufferCell>) -> Unit,
) {
    if (startRow > endRow) return
    val lines = physicalLines()
    var start = lines.indexOfFirst { it.first >= startRow }
    if (start < 0) return
    var end = lines.indexOfLast { it.first <= endRow }
    if (end < start) return
    while (start > 0 && lines[start - 1].second.isWrapped) start--
    while (end < lines.lastIndex && lines[end].second.isWrapped) end++
    forEachLogicalLine(lines.subList(start, end + 1), consume)
}

private fun MainBufferSnapshot.physicalLines(): List<Pair<Int, TerminalLineSnapshot>> =
    historyLines.mapIndexed { index, line -> (-historyLines.size + index) to line } +
        screenLines.mapIndexed { index, line -> index to line }

private fun forEachLogicalLine(
    lines: List<Pair<Int, TerminalLineSnapshot>>,
    consume: (text: String, cells: List<MainBufferCell>) -> Unit,
) {
    val text = StringBuilder()
    val cells = mutableListOf<MainBufferCell>()

    fun flush() {
        if (text.isNotEmpty()) consume(text.toString(), cells.toList())
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
                cells += MainBufferCell(row, cell, cell + 1)
            }
        }
        if (!line.isWrapped) flush()
    }
    flush()
}

internal fun List<MainBufferCell>.spans(startOffset: Int, endOffset: Int): List<MainBufferCellSpan> {
    if (startOffset >= endOffset) return emptyList()
    val spans = mutableListOf<MainBufferCellSpan>()
    subList(startOffset, endOffset).forEach { cell ->
        val last = spans.lastOrNull()
        if (last != null && last.row == cell.row && last.endCell == cell.startCell) {
            spans[spans.lastIndex] = MainBufferCellSpan(last.row, last.startCell, cell.endCell)
        } else {
            spans += MainBufferCellSpan(cell.row, cell.startCell, cell.endCell)
        }
    }
    return spans
}
