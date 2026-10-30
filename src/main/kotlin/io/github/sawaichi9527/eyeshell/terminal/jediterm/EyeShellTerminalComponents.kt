package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalFallbackFonts
import io.github.sawaichi9527.eyeshell.terminal.TerminalFontFallback
import java.awt.AlphaComposite
import java.awt.Font
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * JediTerm widget whose panel resolves a fallback font for glyphs the primary terminal font does
 * not contain. Extending the stock widget keeps the patch inside eyeShell instead of the pinned
 * JediTerm fork.
 */
internal class EyeShellTerminalWidget(
    columns: Int,
    rows: Int,
    private val settings: EyeShellTerminalSettings,
) : JediTermWidget(columns, rows, settings) {
    init {
        applyColorScheme(settings.colorScheme)
    }

    override fun createTerminalPanel(
        settingsProvider: SettingsProvider,
        styleState: StyleState,
        terminalTextBuffer: TerminalTextBuffer,
    ): TerminalPanel = EyeShellTerminalPanel(settingsProvider, terminalTextBuffer, styleState)

    /**
     * Supplies the `StyleState` deliberately: the stock one pins the default style to indexed
     * black/white, and every SGR reset copies that style into the cells being written, which would
     * freeze the old scheme into the scrollback. This state keeps the default colors dynamic and
     * resets to an empty (color-inheriting) style instead.
     */
    override fun createDefaultStyle(): StyleState =
        SchemeStyleState(mySettingsProvider as EyeShellTerminalSettings)

    /**
     * Recolors the live widget: the terminal panel reads the palette on every paint, and the
     * default foreground/background are resolved from the current scheme, so a repaint is enough.
     */
    internal fun applyColorScheme(scheme: TerminalColorScheme) {
        settings.colorScheme = scheme
        myTerminalPanel.repaint()
    }

    /** Paints (or clears) the optional background image behind the terminal grid. */
    internal fun applyBackgroundImage(image: TerminalBackgroundImage?, loadSynchronously: Boolean = false) {
        (myTerminalPanel as EyeShellTerminalPanel).setBackgroundImage(image, loadSynchronously)
    }
}

/**
 * `StyleState` whose default colors follow the live scheme instead of a copy taken at startup.
 *
 * Cells written with no explicit SGR color store null and are resolved through
 * [getDefaultForeground]/[getDefaultBackground] while painting, which is what makes an existing
 * scrollback recolor when the user picks another scheme.
 */
private class SchemeStyleState(
    private val settings: EyeShellTerminalSettings,
) : StyleState() {
    override fun getDefaultForeground(): TerminalColor = settings.getDefaultForeground()

    override fun getDefaultBackground(): TerminalColor = settings.getDefaultBackground()

    /** SGR reset clears attributes; leaving the colors unset keeps them scheme-resolved. */
    override fun reset() = setCurrent(TextStyle.EMPTY)
}

/**
 * Chooses the font per drawn run so CJK text, emoji, and Nerd Font icons render even when the
 * resolved terminal font only covers Latin. Cell metrics still come from the primary font.
 *
 * Also owns the optional background image: it paints behind the grid through the fork's
 * `paintTerminalBackground` hook and suppresses the per-cell default background fill so the image
 * shows through default-colored cells. Explicitly colored cells stay opaque.
 */
internal class EyeShellTerminalPanel(
    settingsProvider: SettingsProvider,
    terminalTextBuffer: TerminalTextBuffer,
    internal val styleState: StyleState,
) : TerminalPanel(settingsProvider, terminalTextBuffer, styleState) {
    private val eyeSettings = settingsProvider as EyeShellTerminalSettings
    private var fallback = TerminalFontFallback(
        TerminalFallbackFonts.fallbackFamiliesWith(eyeSettings.cjkFontFamily),
    )

    private var backgroundSelection: TerminalBackgroundImage? = null
    private var backgroundSource: BufferedImage? = null
    private var backgroundScaled: BufferedImage? = null
    private var backgroundScaledKey: Triple<Int, Int, Int>? = null
    private var backgroundGeneration = 0

    /** Re-reads the terminal font from the settings provider and re-flows the cell grid. */
    internal fun refreshFont() = reinitFontAndResize()

    /** Rebuilds the per-glyph fallback chain, for example after the CJK family changed. */
    internal fun refreshFontFallback() {
        fallback = TerminalFontFallback(TerminalFallbackFonts.fallbackFamiliesWith(eyeSettings.cjkFontFamily))
        repaint()
    }

    /**
     * Selects (or clears) the background image. The image is decoded off the EDT unless
     * [loadSynchronously] is set, which tests use to avoid pumping the event queue.
     */
    internal fun setBackgroundImage(image: TerminalBackgroundImage?, loadSynchronously: Boolean = false) {
        backgroundGeneration += 1
        val generation = backgroundGeneration
        backgroundSelection = image
        backgroundSource = null
        backgroundScaled = null
        backgroundScaledKey = null
        if (image == null) {
            repaint()
            return
        }
        if (loadSynchronously) {
            backgroundSource = BackgroundImageLoader.load(image.file)
            repaint()
            return
        }
        Thread.ofVirtual().name("eyeshell-background-image").start {
            val loaded = BackgroundImageLoader.load(image.file)
            SwingUtilities.invokeLater {
                if (generation == backgroundGeneration) {
                    backgroundSource = loaded
                    repaint()
                }
            }
        }
        repaint()
    }

    /** True when a decodable image is selected, i.e. the default cell background is not painted. */
    internal val hasBackgroundImage: Boolean
        get() = backgroundSelection != null && backgroundSource != null

    /** Read-only view of the paint contract for tests and diagnostics. */
    internal val paintsDefaultBackground: Boolean
        get() = isDefaultBackgroundPainted()

    override fun getFontToDisplay(text: CharArray, start: Int, end: Int, style: TextStyle): Font =
        fallback.resolve(super.getFontToDisplay(text, start, end, style), text, start, end)

    override fun paintTerminalBackground(gfx: Graphics2D) {
        val selection = backgroundSelection
        val source = backgroundSource
        if (selection == null || source == null || width <= 0 || height <= 0) {
            super.paintTerminalBackground(gfx)
            return
        }
        gfx.color = background
        gfx.fillRect(0, 0, width, height)
        val scaled = scaledFor(source, width, height)
        val previous = gfx.composite
        gfx.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, selection.opacityPercent / 100f)
        try {
            gfx.drawImage(scaled, 0, 0, null)
        } finally {
            gfx.composite = previous
        }
    }

    override fun isDefaultBackgroundPainted(): Boolean = !hasBackgroundImage

    /** The cover-scaled image for the current panel size; recomputed only when the size changes. */
    private fun scaledFor(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val key = Triple(width, height, System.identityHashCode(source))
        val cached = backgroundScaled
        if (cached != null && backgroundScaledKey == key) return cached
        val scaled = BackgroundImageLoader.cover(source, width, height)
        backgroundScaled = scaled
        backgroundScaledKey = key
        return scaled
    }
}
