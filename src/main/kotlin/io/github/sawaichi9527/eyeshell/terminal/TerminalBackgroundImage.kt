package io.github.sawaichi9527.eyeshell.terminal

import java.nio.file.Path

/**
 * A terminal background image selection: the stored file plus how strongly it is blended over the
 * scheme background.
 *
 * Opacity is a percentage so the value survives `settings.properties` unchanged; drawing applies it
 * as an alpha composite, which keeps the scaled image cache independent of the opacity.
 */
data class TerminalBackgroundImage(
    val file: Path,
    val opacityPercent: Int = DEFAULT_OPACITY_PERCENT,
) {
    init {
        require(opacityPercent in MIN_OPACITY_PERCENT..MAX_OPACITY_PERCENT) { "Invalid background opacity" }
    }

    init {
        require(file.toString().isNotBlank()) { "Background image path must not be blank" }
    }

    companion object {
        const val MIN_OPACITY_PERCENT = 10
        const val MAX_OPACITY_PERCENT = 100
        const val DEFAULT_OPACITY_PERCENT = 100

        /**
         * Builds the selection from stored, non-secret settings. Returns `null` when the feature is
         * disabled, no image is stored, or the stored path cannot be parsed.
         */
        fun fromSettings(enabled: Boolean, storedPath: String, opacityPercent: Int): TerminalBackgroundImage? {
            if (!enabled) return null
            if (storedPath.isBlank()) return null
            val path = runCatching { Path.of(storedPath) }.getOrNull() ?: return null
            return TerminalBackgroundImage(path, opacityPercent)
        }
    }
}
