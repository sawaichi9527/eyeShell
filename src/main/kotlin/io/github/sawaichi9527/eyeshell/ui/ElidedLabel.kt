package io.github.sawaichi9527.eyeshell.ui

import java.awt.Font
import javax.swing.JLabel

/**
 * A label that shortens its text with an ellipsis instead of widening the surrounding layout.
 *
 * Session tabs carry a Saved Host name or an `user@host:port` endpoint, and a single long title
 * must not push the tab strip into wrapping. The untruncated text stays available as the tooltip.
 */
internal class ElidedLabel(text: String = "", private val maxWidth: Int) : JLabel() {
    private var fullText: String = text

    /** Extra text appended to the tooltip, for example the real endpoint behind a Saved Host name. */
    var toolTipDetail: String? = null
        set(value) {
            field = value
            refreshTooltip()
        }

    init {
        updateText()
    }

    override fun setText(text: String?) {
        fullText = text.orEmpty()
        updateText()
    }

    override fun setFont(font: Font?) {
        super.setFont(font)
        updateText()
    }

    private fun updateText() {
        val metrics = font?.let { current -> getFontMetrics(current) }
        val shortened = if (metrics == null) {
            fullText
        } else {
            elide(fullText, maxWidth) { candidate -> metrics.stringWidth(candidate) }
        }
        super.setText(shortened)
        refreshTooltip()
    }

    private fun refreshTooltip() {
        val detail = toolTipDetail
        toolTipText = when {
            !detail.isNullOrEmpty() -> "$fullText \u2014 $detail"
            super.getText() != fullText -> fullText
            else -> null
        }
    }
}

/** Truncates [text] to [maxWidth] pixels, appending a single ellipsis character when it does not fit. */
internal fun elide(text: String, maxWidth: Int, widthOf: (String) -> Int): String {
    if (maxWidth <= 0 || text.isEmpty() || widthOf(text) <= maxWidth) return text
    val ellipsis = "\u2026"
    if (widthOf(ellipsis) > maxWidth) return ""
    var end = text.length
    while (end > 0 && widthOf(text.substring(0, end) + ellipsis) > maxWidth) end--
    return text.substring(0, end) + ellipsis
}
