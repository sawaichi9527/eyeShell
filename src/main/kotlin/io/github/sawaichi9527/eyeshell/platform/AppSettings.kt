package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * User preferences that survive a restart, stored as a small properties file in the application's
 * config directory. Only preferences the application actually implements live here; anything the
 * Settings dialog shows as "not available yet" has no key.
 *
 * Writes are atomic (temporary file plus move) so an interrupted save cannot truncate the file, and
 * a failed save keeps the value in memory for the running session instead of crashing the UI.
 */
class AppSettings private constructor(
    private val file: Path?,
    private val properties: Properties,
) {
    /** Terminal font size in points, clamped to [MIN_TERMINAL_FONT_SIZE]..[MAX_TERMINAL_FONT_SIZE]. */
    var terminalFontSize: Int
        get() = properties.getProperty(KEY_TERMINAL_FONT_SIZE)
            ?.toIntOrNull()
            ?.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)
            ?: DEFAULT_TERMINAL_FONT_SIZE
        set(value) {
            properties.setProperty(
                KEY_TERMINAL_FONT_SIZE,
                value.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE).toString(),
            )
            save()
        }

    /**
     * Id of the selected terminal color scheme, or an empty string when nothing is stored. The
     * terminal package resolves the id, so an unknown or removed id falls back to its default
     * scheme instead of breaking startup.
     */
    var terminalColorSchemeId: String
        get() = properties.getProperty(KEY_TERMINAL_COLOR_SCHEME).orEmpty()
        set(value) {
            properties.setProperty(KEY_TERMINAL_COLOR_SCHEME, value)
            save()
        }

    private fun save() {
        val target = file ?: return
        try {
            target.parent?.let(Files::createDirectories)
            val temporary = target.resolveSibling("${target.fileName}.tmp")
            Files.newBufferedWriter(temporary).use { writer -> properties.store(writer, null) }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // Session-only fallback: the value stays applied until the application closes.
        }
    }

    companion object {
        const val DEFAULT_TERMINAL_FONT_SIZE = 16
        const val MIN_TERMINAL_FONT_SIZE = 10
        const val MAX_TERMINAL_FONT_SIZE = 28

        private const val KEY_TERMINAL_FONT_SIZE = "terminal.font.size"
        private const val KEY_TERMINAL_COLOR_SCHEME = "terminal.color.scheme"

        fun load(file: Path): AppSettings {
            val properties = Properties()
            try {
                if (Files.isRegularFile(file)) {
                    Files.newBufferedReader(file).use(properties::load)
                }
            } catch (_: Exception) {
                // A damaged or unreadable file falls back to defaults rather than blocking startup.
            }
            return AppSettings(file, properties)
        }

        internal fun inMemory(): AppSettings = AppSettings(null, Properties())
    }
}
