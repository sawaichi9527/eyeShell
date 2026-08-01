package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.google.re2j.Pattern
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalHighlightResult
import io.github.sawaichi9527.eyeshell.terminal.HighlightMergeMode
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

internal class JediTermHighlightController(
    private val widget: JediTermWidget,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("terminal-highlight-", 0).factory(),
    )
    private val generation = AtomicLong()
    private val observedRevision = AtomicLong(widget.terminalTextBuffer.getMainBufferRevision())
    private val closed = AtomicBoolean()
    private val refreshPosted = AtomicBoolean()
    private var pending: Future<*>? = null
    private val matchCache = HighlightMatchCache()
    @Volatile
    private var compiledRules: List<CompiledHighlightRule> = emptyList()
    private val modelListener = TerminalModelListener {
        val revision = widget.terminalTextBuffer.getMainBufferRevision()
        if (observedRevision.getAndSet(revision) != revision && refreshPosted.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                refreshPosted.set(false)
                if (!closed.get() && compiledRules.isNotEmpty() && pending?.isDone != false) schedule()
            }
        }
    }

    init {
        widget.terminalTextBuffer.addModelListener(modelListener)
    }

    fun setRules(updatedRules: List<TerminalHighlightRule>) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal highlight rules must be configured on the Swing EDT" }
        if (closed.get()) return
        val replacement = compileHighlightRules(updatedRules)
        compiledRules = replacement
        widget.terminalPanel.setCoordinateHighlightResult(null)
        schedule()
    }

    private fun schedule() {
        check(SwingUtilities.isEventDispatchThread())
        val request = generation.incrementAndGet()
        pending?.cancel(true)
        val requestedRules = compiledRules
        if (requestedRules.isEmpty()) {
            widget.terminalPanel.setCoordinateHighlightResult(null)
            try {
                executor.execute(matchCache::clear)
            } catch (_: RejectedExecutionException) {
                // The controller is already closing.
            }
            return
        }
        val visibleStartRow = widget.terminalPanel.visibleStartRow
        val visibleEndRow = widget.terminalPanel.visibleEndRow
        pending = try {
            executor.schedule({
                try {
                    val snapshot = widget.terminalTextBuffer.getMainBufferSnapshot()
                    matchCache.beginScan()
                    val visibleResult = renderHighlights(
                        snapshot,
                        requestedRules,
                        visibleStartRow..visibleEndRow,
                        matchCache,
                    ) { widget.terminalTextBuffer.getMainBufferRevision() != snapshot.revision }
                    publish(request, visibleResult)
                    val result = renderHighlights(snapshot, requestedRules, matchCache) {
                        widget.terminalTextBuffer.getMainBufferRevision() != snapshot.revision
                    }
                    matchCache.finishScan()
                    publish(request, result)
                } catch (_: StaleHighlightSnapshotException) {
                    matchCache.abortScan()
                    SwingUtilities.invokeLater {
                        if (!closed.get() && generation.get() == request) schedule()
                    }
                } catch (_: InterruptedException) {
                    matchCache.abortScan()
                    Thread.currentThread().interrupt()
                }
            }, HIGHLIGHT_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun publish(request: Long, result: TerminalHighlightResult) {
        SwingUtilities.invokeLater {
            if (closed.get() || generation.get() != request) return@invokeLater
            if (widget.terminalTextBuffer.getMainBufferRevision() != result.revision) {
                schedule()
                return@invokeLater
            }
            observedRevision.set(result.revision)
            widget.terminalPanel.setCoordinateHighlightResult(result)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        pending?.cancel(true)
        widget.terminalTextBuffer.removeModelListener(modelListener)
        executor.shutdownNow()
    }

    companion object {
        private const val HIGHLIGHT_DEBOUNCE_MILLIS = 150L
    }
}

internal fun highlightMainBuffer(
    snapshot: MainBufferSnapshot,
    rules: List<TerminalHighlightRule>,
): TerminalHighlightResult {
    val cache = HighlightMatchCache()
    cache.beginScan()
    return renderHighlights(snapshot, compileHighlightRules(rules), cache).also { cache.finishScan() }
}

private fun compileHighlightRules(rules: List<TerminalHighlightRule>): List<CompiledHighlightRule> =
    rules.withIndex()
        .filter { it.value.enabled }
        .sortedWith(compareBy<IndexedValue<TerminalHighlightRule>> { it.value.priority }.thenBy { it.index })
        .map { indexed ->
            val flags = if (indexed.value.matchCase) 0 else Pattern.CASE_INSENSITIVE
            CompiledHighlightRule(indexed.value, Pattern.compile(indexed.value.pattern, flags))
        }

private fun renderHighlights(
    snapshot: MainBufferSnapshot,
    compiledRules: List<CompiledHighlightRule>,
    cache: HighlightMatchCache,
    stale: () -> Boolean = { false },
): TerminalHighlightResult = renderHighlights(snapshot, compiledRules, null, cache, stale)

private fun renderHighlights(
    snapshot: MainBufferSnapshot,
    compiledRules: List<CompiledHighlightRule>,
    rows: IntRange?,
    cache: HighlightMatchCache,
    stale: () -> Boolean,
): TerminalHighlightResult {
    val cellsByRow = mutableMapOf<Int, MutableList<HighlightAttributes?>>()

    val scanLine: (String, List<MainBufferCell>) -> Unit = { text, cells ->
        if (stale()) throw StaleHighlightSnapshotException()
        compiledRules.forEach { compiled ->
            cache.matches(compiled, text).forEach { match ->
                cells.spans(match.start, match.end).forEach { span ->
                    val rowCells = cellsByRow.getOrPut(span.row) { mutableListOf() }
                    while (rowCells.size < span.endCell) rowCells += null
                    for (cell in span.startCell until span.endCell) {
                        rowCells[cell] = rowCells[cell].apply(compiled.rule)
                    }
                }
            }
        }
    }
    if (rows == null) {
        snapshot.forEachLogicalLine(scanLine)
    } else {
        snapshot.forEachLogicalLineInRows(rows.first, rows.last, scanLine)
    }

    val spans = buildList {
        cellsByRow.toSortedMap().forEach { (row, cells) ->
            var start = 0
            while (start < cells.size) {
                val attributes = cells[start]
                if (attributes == null) {
                    start++
                    continue
                }
                var end = start + 1
                while (end < cells.size && cells[end] == attributes) end++
                add(TerminalHighlightResult.Span(
                    row,
                    start,
                    end,
                    attributes.toTextStyle(),
                    attributes.overrideTerminalStyle,
                ))
                start = end
            }
        }
    }
    return TerminalHighlightResult(snapshot.revision, spans)
}

private class StaleHighlightSnapshotException : RuntimeException()

private class HighlightMatchCache {
    private var retained = emptyMap<HighlightCacheKey, List<HighlightMatch>>()
    private var current: MutableMap<HighlightCacheKey, List<HighlightMatch>>? = null

    fun beginScan() {
        check(current == null)
        current = mutableMapOf()
    }

    fun matches(rule: CompiledHighlightRule, text: String): List<HighlightMatch> {
        val key = HighlightCacheKey(rule.rule.pattern, rule.rule.matchCase, text)
        val scan = checkNotNull(current)
        scan[key]?.let { return it }
        val matches = retained[key] ?: buildList {
            val matcher = rule.pattern.matcher(text)
            while (matcher.find()) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (matcher.start() != matcher.end()) add(HighlightMatch(matcher.start(), matcher.end()))
            }
        }
        scan[key] = matches
        return matches
    }

    fun finishScan() {
        retained = checkNotNull(current).toMap()
        current = null
    }

    fun abortScan() {
        current = null
    }

    fun clear() {
        retained = emptyMap()
        current = null
    }
}

private data class HighlightCacheKey(
    val pattern: String,
    val matchCase: Boolean,
    val text: String,
)

private data class HighlightMatch(
    val start: Int,
    val end: Int,
)

private data class CompiledHighlightRule(
    val rule: TerminalHighlightRule,
    val pattern: Pattern,
)

private data class HighlightAttributes(
    val foregroundRgb: Int? = null,
    val backgroundRgb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val overrideTerminalStyle: Boolean = false,
) {
    fun toTextStyle(): TextStyle = TextStyle.Builder()
        .setForeground(foregroundRgb?.toTerminalColor())
        .setBackground(backgroundRgb?.toTerminalColor())
        .setOption(TextStyle.Option.BOLD, bold)
        .setOption(TextStyle.Option.ITALIC, italic)
        .setOption(TextStyle.Option.UNDERLINED, underline)
        .build()
}

private fun HighlightAttributes?.apply(rule: TerminalHighlightRule): HighlightAttributes {
    val current = if (rule.mergeMode == HighlightMergeMode.OVERRIDE) {
        HighlightAttributes(overrideTerminalStyle = true)
    } else {
        this ?: HighlightAttributes()
    }
    return current.copy(
        foregroundRgb = rule.foregroundRgb ?: current.foregroundRgb,
        backgroundRgb = rule.backgroundRgb ?: current.backgroundRgb,
        bold = current.bold || rule.bold,
        italic = current.italic || rule.italic,
        underline = current.underline || rule.underline,
    )
}

private fun Int.toTerminalColor(): TerminalColor = TerminalColor.rgb(
    this shr 16 and 0xFF,
    this shr 8 and 0xFF,
    this and 0xFF,
)
