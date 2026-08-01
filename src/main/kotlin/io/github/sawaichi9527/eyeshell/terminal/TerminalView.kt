package io.github.sawaichi9527.eyeshell.terminal

import java.io.Writer
import javax.swing.JComponent

interface TerminalView : AutoCloseable {
    val component: JComponent

    fun attach(session: TerminalSession)

    fun captureAllOutput(): TerminalOutputSnapshot

    fun writeAllOutput(writer: Writer) = captureAllOutput().writeTo(writer)

    fun setContextActions(actions: TerminalContextActions)

    fun selectVisible()

    fun selectAllOutput()

    fun showSearch()

    fun setHighlightRules(rules: List<TerminalHighlightRule>)

    fun clearScrollback()
}

fun interface TerminalOutputSnapshot {
    fun writeTo(writer: Writer)
}

data class TerminalContextActions(
    val copyAllOutput: () -> Unit,
    val saveAllOutput: () -> Unit,
    val addHighlightRule: (() -> Unit)? = null,
    val manageHighlightRules: (() -> Unit)? = null,
)

data class TerminalHighlightRule(
    val name: String,
    val pattern: String,
    val scope: HighlightScope = HighlightScope.CURRENT_SESSION,
    val matchCase: Boolean,
    val enabled: Boolean,
    val priority: Int,
    val foregroundRgb: Int?,
    val backgroundRgb: Int?,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val mergeMode: HighlightMergeMode,
) {
    init {
        require(name.isNotBlank()) { "Highlight rule name must not be blank" }
        require(pattern.isNotEmpty()) { "Highlight pattern must not be empty" }
        require(foregroundRgb == null || foregroundRgb in 0..0xFFFFFF) { "Invalid foreground color" }
        require(backgroundRgb == null || backgroundRgb in 0..0xFFFFFF) { "Invalid background color" }
    }
}

enum class HighlightMergeMode {
    MERGE,
    OVERRIDE,
}

enum class HighlightScope {
    CURRENT_SESSION,
}
