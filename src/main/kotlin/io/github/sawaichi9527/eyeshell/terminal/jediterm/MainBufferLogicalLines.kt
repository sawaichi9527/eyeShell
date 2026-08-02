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
): Unit = forEachLogicalLine(0, historyLines.size + screenLines.size - 1, consume)

internal fun MainBufferSnapshot.forEachLogicalLineInRows(
    startRow: Int,
    endRow: Int,
    consume: (text: String, cells: List<MainBufferCell>) -> Unit,
) {
    if (startRow > endRow) return
    val firstAvailableRow = -historyLines.size
    val lastAvailableRow = screenLines.lastIndex
    if (endRow < firstAvailableRow || startRow > lastAvailableRow) return
    var start = historyLines.size + maxOf(startRow, firstAvailableRow)
    var end = historyLines.size + minOf(endRow, lastAvailableRow)
    while (start > 0 && lineAt(start - 1).isWrapped) start--
    val lastIndex = historyLines.size + screenLines.size - 1
    while (end < lastIndex && lineAt(end).isWrapped) end++
    forEachLogicalLine(start, end, consume)
}

private fun MainBufferSnapshot.forEachLogicalLine(
    start: Int,
    end: Int,
    consume: (text: String, cells: List<MainBufferCell>) -> Unit,
) {
    val text = StringBuilder()
    val cells = mutableListOf<MainBufferCell>()

    fun flush() {
        if (text.isNotEmpty()) consume(text.toString(), cells.toList())
        text.setLength(0)
        cells.clear()
    }

    for (index in start..end) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val row = index - historyLines.size
        val line = lineAt(index)
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

private fun MainBufferSnapshot.lineAt(index: Int): TerminalLineSnapshot =
    if (index < historyLines.size) historyLines[index] else screenLines[index - historyLines.size]

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
