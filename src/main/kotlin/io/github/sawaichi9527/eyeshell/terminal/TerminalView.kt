package io.github.sawaichi9527.eyeshell.terminal

import java.io.Writer
import javax.swing.JComponent

interface TerminalView : AutoCloseable {
    val component: JComponent

    fun attach(session: TerminalSession)

    /**
     * Whether a new session may be attached to this view without losing the retained scrollback.
     * A view that is still running a session reports `false`; callers then open a separate tab.
     */
    fun canAttach(): Boolean = true

    /**
     * Prepares the view for a session that replaces the previous one inside the same tab.
     * The default implementation does nothing; implementations may restore a primary buffer
     * left behind by an application that exited from its alternate screen.
     */
    fun resetForReattach() = Unit

    fun captureAllOutput(): TerminalOutputSnapshot

    fun writeAllOutput(writer: Writer) = captureAllOutput().writeTo(writer)

    fun setContextActions(actions: TerminalContextActions)

    fun selectVisible()

    fun selectAllOutput()

    fun showSearch()

    fun setHighlightRules(rules: List<TerminalHighlightRule>)

    fun clearScrollback()

    /**
     * Applies a new terminal font size in points. Views that cannot re-flow their cell grid keep
     * their current size; the JediTerm view reinitialises its metrics and resizes the PTY.
     */
    fun setFontSize(size: Float) = Unit
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
