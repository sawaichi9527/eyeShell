package io.github.sawaichi9527.eyeshell.terminal.jediterm

import com.jediterm.core.Color as TerminalRgb
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.model.MainBufferSnapshot
import com.jediterm.terminal.model.TerminalLineSnapshot
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.TerminalActionProvider
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.jediterm.terminal.util.CharUtils
import io.github.sawaichi9527.eyeshell.platform.Diagnostics
import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import io.github.sawaichi9527.eyeshell.terminal.TerminalContextActions
import io.github.sawaichi9527.eyeshell.terminal.TerminalHighlightRule
import io.github.sawaichi9527.eyeshell.terminal.TerminalOutputSnapshot
import io.github.sawaichi9527.eyeshell.terminal.EmulatorStallDetector
import io.github.sawaichi9527.eyeshell.terminal.EmulatorWatchdog
import io.github.sawaichi9527.eyeshell.terminal.RepaintSample
import io.github.sawaichi9527.eyeshell.terminal.RepaintWatchdog
import io.github.sawaichi9527.eyeshell.terminal.TerminalReadStall
import io.github.sawaichi9527.eyeshell.terminal.TerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalTypography
import io.github.sawaichi9527.eyeshell.terminal.TerminalView
import java.io.Writer
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** Shared by the view and its connector; a packaged build has no console, only the log file. */
private val logger: java.util.logging.Logger =
    java.util.logging.Logger.getLogger("io.github.sawaichi9527.eyeshell.terminal")

class JediTermTerminalView(
    columns: Int = TerminalTypography.INITIAL_COLUMNS,
    rows: Int = TerminalTypography.INITIAL_ROWS,
    scrollbackLines: Int = EyeShellTerminalSettings.MAX_SCROLLBACK_LINES,
    maxRefreshRate: Int = EyeShellTerminalSettings.MAX_REFRESH_RATE,
    font: java.awt.Font = TerminalTypography.defaultFont(),
    colorScheme: TerminalColorScheme = TerminalColorSchemes.DEFAULT,
    cjkFontFamily: String? = null,
    /** Where a stall writes its thread dump; null keeps diagnostics out of tests and other embeddings. */
    private val diagnosticsDirectory: java.nio.file.Path? = null,
    /** How often the watchdog samples the emulator thread; tests shorten the 5 s default. */
    private val emulatorStallSampleMillis: Long = 5_000,
    /** Consecutive identical blocked samples that count as a stall (3 x 5 s = 15 s by default). */
    private val emulatorStallSamples: Int = 3,
) : TerminalView {
    private val settings = EyeShellTerminalSettings(scrollbackLines, maxRefreshRate, font, colorScheme, cjkFontFamily)
    private val widget = EyeShellTerminalWidget(columns, rows, settings)
    private val searchController = JediTermSearchController(widget)
    private val highlightController = JediTermHighlightController(widget)
    private val contextActionProvider = ContextActionProvider(widget, searchController::show)
    @Volatile
    private var activeConnector: SessionTtyConnector? = null

    @Volatile
    private var emulatorStopHandler: ((unexpected: Boolean) -> Unit)? = null
    private var watchdog: EmulatorWatchdog? = null
    private var repaintWatchdog: RepaintWatchdog? = null

    init {
        widget.terminalPanel.setPopupMenuActionProvider(contextActionProvider)
        widget.terminalPanel.setRetainedMainCopyHandler {
            contextActionProvider.actions?.copyAllOutput?.invoke()
        }
        widget.setFindActionHandler(::showSearch)
        // Fires on the emulator pool thread after the widget closed its session; the callback only
        // reports whether the end was expected, and the caller marshals it onto its own thread.
        widget.addListener { handleEmulatorStopped() }
    }

    override val component: JComponent
        get() = widget

    override fun attach(session: TerminalSession) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal sessions must be attached on the Swing EDT" }
        check(widget.canOpenSession()) { "A terminal session is already attached" }
        contextActionProvider.hasSession = true
        val connector = SessionTtyConnector(session) {
            ", paints=${terminalPanel.paintCount}, lastPaint=${terminalPanel.millisSinceLastPaint}ms"
        }
        activeConnector = connector
        widget.setTtyConnector(connector)
        widget.start()
        startWatchdog()
    }

    override fun canAttach(): Boolean = widget.canOpenSession()

    /**
     * Stops the emulator thread without closing the session, so [attach] can replay output into the
     * same widget. The connector polls, so the emulator thread observes the detach within a few
     * milliseconds; a session that does not yield inside [DETACH_TIMEOUT] is resumed instead.
     */
    override fun detachSession(): Boolean {
        val connector = activeConnector ?: return false
        connector.detach()
        val deadline = System.nanoTime() + DETACH_TIMEOUT.toNanos()
        while (widget.isSessionRunning) {
            if (System.nanoTime() >= deadline) {
                connector.resume()
                return false
            }
            Thread.sleep(2)
        }
        activeConnector = null
        contextActionProvider.hasSession = false
        stopWatchdog()
        return true
    }

    override fun setEmulatorStopHandler(handler: ((unexpected: Boolean) -> Unit)?) {
        emulatorStopHandler = handler
    }

    /**
     * Watches the emulator thread from outside. A thread that is blocked cannot report its own
     * stall, and the tab would simply stop updating while the SSH channel stays open — including
     * the input path, which is why a frozen tab must be treated as unsafe to type into.
     */
    private fun startWatchdog() {
        stopWatchdog()
        watchdog = EmulatorWatchdog(
            thread = { activeConnector?.emulatorThread },
            progress = { activeConnector?.consumedCharacters ?: 0 },
            intervalMillis = emulatorStallSampleMillis,
            detector = EmulatorStallDetector(emulatorStallSamples),
            onStall = ::handleEmulatorStall,
        ).also { it.start() }
        repaintWatchdog = RepaintWatchdog(
            sample = {
                activeConnector?.let { connector ->
                    RepaintSample(connector.consumedCharacters, terminalPanel.paintCount)
                }
            },
            intervalMillis = emulatorStallSampleMillis,
            onStuck = ::handleRepaintStuck,
        ).also { it.start() }
    }

    private fun stopWatchdog() {
        watchdog?.stop()
        watchdog = null
        repaintWatchdog?.stop()
        repaintWatchdog = null
    }

    /**
     * Output keeps arriving but nothing is painted. That is a rendering failure, not a transport
     * problem: the tab still says Connected, the session is healthy, and only the screen is stale.
     */
    private fun handleRepaintStuck(sample: RepaintSample) {
        val connector = activeConnector ?: return
        logger.severe(
            "Terminal output is being consumed but the panel has not repainted " +
                "(consumed=${sample.consumedCharacters}, paints=${sample.paints}) on ${connector.sessionName}",
        )
        diagnosticsDirectory?.let { directory ->
            Diagnostics.write(
                directory = directory,
                reason = "terminal-repaint-${connector.sessionName}",
                sessions = listOf(
                    "session=${connector.sessionName} state=not-repainting" +
                        " consumed=${sample.consumedCharacters} paints=${sample.paints}" +
                        " lastPaint=${terminalPanel.millisSinceLastPaint}ms" +
                        connector.flowForDiagnostics(),
                ),
            )
        }
    }

    private fun handleEmulatorStall(frames: List<String>) {
        val connector = activeConnector ?: return
        connector.markStallReported()
        logger.severe(
            buildString {
                append("Terminal emulator stalled on ${connector.sessionName}; the tab stopped updating")
                frames.forEach { frame -> append("\n    at ").append(frame) }
            },
        )
        diagnosticsDirectory?.let { directory ->
            Diagnostics.write(
                directory = directory,
                reason = "terminal-stall-${connector.sessionName}",
                sessions = listOf(
                    "session=${connector.sessionName} state=stalled" +
                        connector.flowForDiagnostics(),
                ),
            )
        }
        // The page closes the session and shows the failure; closing the channel is what makes a
        // keystroke in a frozen tab harmless instead of a command the user cannot see.
        emulatorStopHandler?.invoke(true)
    }

    /**
     * The emulator ends for two very different reasons: the remote shell closed (or the tab was
     * closed) and the connector saw the end of the stream, or the emulator died on an exception.
     * Only the second case leaves a tab that looks connected but never updates again, so it is
     * reported and, from there, surfaced to the user and the log.
     */
    private fun handleEmulatorStopped() {
        stopWatchdog()
        val connector = activeConnector
        if (connector != null && !connector.expectedEnd) {
            if (!connector.stallReported) {
                logger.warning(
                    "Terminal emulator stopped while ${connector.sessionName} reported the session still open",
                )
            }
            emulatorStopHandler?.invoke(true)
            return
        }
        emulatorStopHandler?.invoke(false)
    }

    override fun resetForReattach() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal reattach preparation must run on the Swing EDT" }
        if (widget.terminalTextBuffer.isUsingAlternateBuffer) {
            widget.terminalTextBuffer.useAlternateBuffer(false)
        }
    }

    override fun captureAllOutput(): TerminalOutputSnapshot {
        check(!SwingUtilities.isEventDispatchThread()) { "Terminal output must be exported off the Swing EDT" }
        val snapshot = widget.terminalTextBuffer.getMainBufferSnapshot()
        return TerminalOutputSnapshot(snapshot::writeLogicalLines)
    }

    override fun setContextActions(actions: TerminalContextActions) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal context actions must be configured on the Swing EDT" }
        contextActionProvider.actions = actions
    }

    override fun selectVisible() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal selection must run on the Swing EDT" }
        widget.terminalPanel.selectVisible()
    }

    override fun selectAllOutput() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal selection must run on the Swing EDT" }
        widget.terminalPanel.selectAllMainOutput()
    }

    override fun showSearch() {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal search must open on the Swing EDT" }
        searchController.show()
    }

    override fun setHighlightRules(rules: List<TerminalHighlightRule>) {
        highlightController.setRules(rules)
    }

    override fun clearScrollback() {
        check(SwingUtilities.isEventDispatchThread()) { "Scrollback must be cleared on the Swing EDT" }
        check(!widget.terminalTextBuffer.isUsingAlternateBuffer) {
            "Main scrollback cannot be cleared while the alternate screen is active"
        }
        widget.terminalTextBuffer.clearHistory()
    }

    internal val isSessionRunning: Boolean
        get() = widget.isSessionRunning

    internal val isUsingAlternateBuffer: Boolean
        get() = widget.terminalTextBuffer.isUsingAlternateBuffer

    internal val colorScheme: TerminalColorScheme
        get() = settings.colorScheme

    internal val defaultBackgroundRgb: Int
        get() = settings.colorScheme.backgroundRgb

    internal val defaultForegroundRgb: Int
        get() = settings.colorScheme.foregroundRgb

    internal val ansiPaletteRgb: List<Int>
        get() = settings.terminalColorPaletteRgb()

    internal val historyLineCount: Int
        get() = widget.terminalTextBuffer.historyLinesCount

    internal val contextActions: List<TerminalAction>
        get() = contextActionProvider.getActions()

    internal val searchComponent: JComponent
        get() = searchController.component

    internal val selection
        get() = widget.terminalPanel.selection

    internal val fontSize: Float
        get() = settings.font.size2D

    internal val isRetainedMainSelection: Boolean
        get() = widget.terminalPanel.isRetainedMainSelection

    internal val coordinateSearchResult
        get() = widget.terminalPanel.coordinateFindResult

    internal val coordinateHighlightResult
        get() = widget.terminalPanel.coordinateHighlightResult

    internal val mainBufferRevision: Long
        get() = widget.terminalTextBuffer.getMainBufferRevision()

    internal fun awaitHighlightTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
        highlightController.awaitTermination(timeout, unit)

    override fun setFontSize(size: Float) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal font changes must happen on the Swing EDT" }
        require(size > 0f) { "Terminal font size must be positive" }
        settings.font = settings.font.deriveFont(size)
        (widget.terminalPanel as EyeShellTerminalPanel).refreshFont()
    }

    override fun setFontFamilies(latin: String?, cjk: String?) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal font changes must happen on the Swing EDT" }
        val size = settings.font.size2D
        settings.font = TerminalTypography.resolveFontFor(latin, size)
        settings.cjkFontFamily = cjk?.trim().orEmpty().ifEmpty { null }
        (widget.terminalPanel as EyeShellTerminalPanel).apply {
            refreshFont()
            refreshFontFallback()
        }
    }

    override fun setColorScheme(scheme: TerminalColorScheme) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal color changes must happen on the Swing EDT" }
        widget.applyColorScheme(scheme)
    }

    override fun setBackgroundImage(image: TerminalBackgroundImage?) {
        check(SwingUtilities.isEventDispatchThread()) { "Terminal background changes must happen on the Swing EDT" }
        widget.applyBackgroundImage(image)
    }

    /** Test hook: loads the image inline instead of on a background thread. */
    internal fun setBackgroundImageForTest(image: TerminalBackgroundImage?) {
        widget.applyBackgroundImage(image, loadSynchronously = true)
    }

    internal val terminalPanel: EyeShellTerminalPanel
        get() = widget.terminalPanel as EyeShellTerminalPanel

    override fun terminalState(): String = runCatching {
        val terminal = widget.terminal
        val buffer = widget.terminalTextBuffer
        buildString {
            append("size=").append(terminal.terminalWidth).append('x').append(terminal.terminalHeight)
            append(" cursor=").append(terminal.cursorX).append(',').append(terminal.cursorY)
            append(" scrollTop=").append((terminal as? com.jediterm.terminal.model.JediTerminal)?.scrollRegionTop ?: -1)
            append(" visibleRows=").append(widget.terminalPanel.visibleStartRow)
                .append("..").append(widget.terminalPanel.visibleEndRow)
            append(" screenLines=").append(buffer.screenLinesCount)
            append(" historyLines=").append(buffer.historyLinesCount)
            append(" alternate=").append(buffer.isUsingAlternateBuffer)
            append(" paints=").append(terminalPanel.paintCount)
            append(" lastPaint=").append(terminalPanel.millisSinceLastPaint).append("ms")
            append(" consumed=").append(activeConnector?.consumedCharacters ?: 0)
        }
    }.getOrDefault("")

    override fun close() {
        stopWatchdog()
        // A tab the user closes stops its emulator on purpose; do not report that as a failure.
        activeConnector?.markExpectedEnd()
        highlightController.close()
        searchController.close()
        widget.close()
    }

    private companion object {
        /** How long a detach may take before the view gives up and keeps the session attached. */
        val DETACH_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(2)
    }
}

private class ContextActionProvider(
    private val widget: JediTermWidget,
    private val showSearch: () -> Unit,
) : TerminalActionProvider {
    var actions: TerminalContextActions? = null
    var hasSession: Boolean = false
    private var nextProvider: TerminalActionProvider? = null

    override fun getActions(): List<TerminalAction> = listOf(
        action(I18n.t("terminal.copy"), enabled = widget.terminalPanel::canCopyCurrentSelection) {
            widget.terminalPanel.copyCurrentSelection()
        },
        action(I18n.t("terminal.paste"), enabled = { hasSession && widget.isSessionRunning }) {
            if (widget.isSessionRunning) widget.terminalPanel.pasteClipboard()
        },
        action(I18n.t("terminal.selectVisible"), separated = true, enabled = { hasSession }) {
            widget.terminalPanel.selectVisible()
        },
        action(I18n.t("terminal.selectAllOutput"), enabled = { hasSession }) {
            widget.terminalPanel.selectAllMainOutput()
        },
        action(I18n.t("terminal.copyAllOutput"), enabled = { hasSession && actions != null }) {
            actions?.copyAllOutput?.invoke()
        },
        action(I18n.t("terminal.saveAllOutput"), enabled = { hasSession && actions != null }) {
            actions?.saveAllOutput?.invoke()
        },
        action(I18n.t("terminal.search"), separated = true, enabled = { hasSession }) {
            showSearch()
        },
        action(I18n.t("terminal.addHighlight"), enabled = { hasSession && actions?.addHighlightRule != null }) {
            actions?.addHighlightRule?.invoke()
        },
        action(I18n.t("terminal.manageHighlights"), enabled = { hasSession && actions?.manageHighlightRules != null }) {
            actions?.manageHighlightRules?.invoke()
        },
        action(I18n.t("terminal.clearBuffer"), separated = true, enabled = {
            hasSession && !widget.terminalTextBuffer.isUsingAlternateBuffer
        }) {
            if (!widget.terminalTextBuffer.isUsingAlternateBuffer) widget.terminalPanel.clearBuffer()
        },
    )

    private fun action(
        name: String,
        separated: Boolean = false,
        enabled: () -> Boolean,
        run: () -> Unit,
    ): TerminalAction = TerminalAction(TerminalActionPresentation(name, emptyList())) {
        run()
        true
    }.withEnabledSupplier(enabled).separatorBefore(separated)

    override fun getNextProvider(): TerminalActionProvider? = nextProvider

    override fun setNextProvider(provider: TerminalActionProvider?) {
        nextProvider = provider
    }
}

internal class SessionTtyConnector(
    private val session: TerminalSession,
    /** Extra render diagnostics appended to the silence warning; the view owns the paint counter. */
    private val renderDiagnostics: () -> String = { "" },
) : TtyConnector {
    @Volatile
    private var detached = false

    /**
     * Set when this connector delivered an end of stream, or was detached on purpose. When it is
     * still false after the emulator thread stops, the emulator ended on its own (an exception)
     * while the session was open.
     */
    @Volatile
    var expectedEnd = false
        private set

    val sessionName: String
        get() = session.name

    /** Appends the channel window sizes to a diagnostic line when the transport can report them. */
    fun flowForDiagnostics(): String = session.flowControl()?.let { flow ->
        " localWindow=${flow.localWindow}/${flow.localWindowMax}" +
            " remoteWindow=${flow.remoteWindow}/${flow.remoteWindowMax}"
    }.orEmpty()

    /** The emulator thread, captured from the first read so the watchdog can sample it. */
    @Volatile
    var emulatorThread: Thread? = null
        private set

    /** Set when the watchdog already reported this stall, so the later stop is not logged twice. */
    @Volatile
    var stallReported = false
        private set

    fun markStallReported() {
        stallReported = true
    }

    /** Marks a deliberate end (tab closed, widget stopped) so it is not reported as a failure. */
    fun markExpectedEnd() {
        expectedEnd = true
    }

    /** Characters handed to the emulator, next to the panel's paint counter in the silence warning. */
    @Volatile
    var consumedCharacters = 0L
        private set

    private val stall = TerminalReadStall()

    fun detach() {
        expectedEnd = true
        detached = true
    }

    fun resume() {
        detached = false
        expectedEnd = false
    }

    /**
     * Polls instead of blocking so [detach] can stop the emulator thread without closing the SSH
     * session; a terminal that would otherwise wait forever in a read must be interruptible for the
     * encoding replay to work.
     *
     * An exception raised while the session is still open is logged here, because the emulator
     * thread reports it through JediTerm's `LOG.error` and a packaged build has no console to show
     * it. The polling loop also records a stall so a connected-but-silent terminal leaves a
     * timestamped line instead of looking like a dead tab.
     */
    override fun read(buffer: CharArray, offset: Int, length: Int): Int = try {
        readLoop(buffer, offset, length)
    } catch (failure: InterruptedException) {
        throw failure
    } catch (failure: Exception) {
        if (session.isOpen) {
            logger.log(java.util.logging.Level.SEVERE, "Terminal read failed on ${session.name}", failure)
        }
        throw failure
    }

    private fun readLoop(buffer: CharArray, offset: Int, length: Int): Int {
        if (emulatorThread == null) emulatorThread = Thread.currentThread()
        while (true) {
            if (detached) return -1
            // Drain buffered output before reporting the end of a closed session, so the last chunk
            // is not lost when a stream reports closed while its reader still has characters.
            if (session.ready()) {
                val count = session.read(buffer, offset, length)
                if (count > 0) {
                    stall.progress()
                    consumedCharacters += count
                }
                return count
            }
            if (!session.isOpen) {
                expectedEnd = true
                return -1
            }
            if (stall.shouldReport()) {
                val flow = session.flowControl()
                val transport = if (flow == null) {
                    ""
                } else {
                    "localWindow=${flow.localWindow}/${flow.localWindowMax}, " +
                        "remoteWindow=${flow.remoteWindow}/${flow.remoteWindowMax}, "
                }
                logger.warning(
                    "No terminal output for ${stall.silenceMillis() / 1000}s on ${session.name}; " +
                        "the session is still open and the emulator is waiting " +
                        "(${transport}consumed=$consumedCharacters${renderDiagnostics()})",
                )
            }
            Thread.sleep(READ_POLL_MILLIS)
        }
    }

    /**
     * Input is dropped once the session is over. A frozen tab must not pass keystrokes to a remote
     * shell the user cannot see, and a closed channel would only raise an IOException inside
     * JediTerm's writer anyway.
     */
    override fun write(bytes: ByteArray) {
        if (!session.isOpen) return
        session.write(bytes)
    }

    override fun write(string: String) {
        if (!session.isOpen) return
        session.write(string)
    }

    override fun isConnected(): Boolean = session.isOpen

    override fun resize(termSize: TermSize) = session.resize(termSize.columns, termSize.rows)

    override fun waitFor(): Int = session.awaitExit()

    override fun ready(): Boolean = session.ready()

    override fun getName(): String = session.name

    /** A detached connector must not end the connection the tab still owns. */
    override fun close() {
        if (!detached) session.close()
    }

    private companion object {
        const val READ_POLL_MILLIS = 5L
    }
}

internal class EyeShellTerminalSettings(
    private val scrollbackLines: Int = MAX_SCROLLBACK_LINES,
    private val refreshRate: Int = MAX_REFRESH_RATE,
    font: java.awt.Font = TerminalTypography.defaultFont(),
    colorScheme: TerminalColorScheme = TerminalColorSchemes.DEFAULT,
    cjkFontFamily: String? = null,
) : DefaultSettingsProvider() {
    /** Mutable so the Settings dialog can change the size of a live terminal. */
    var font: java.awt.Font = font

    /** Mutable so the Settings dialog can recolor a live terminal. */
    var colorScheme: TerminalColorScheme = colorScheme

    /** Preferred family for CJK glyphs the primary font lacks; null keeps the built-in chain. */
    var cjkFontFamily: String? = cjkFontFamily

    override fun audibleBell(): Boolean = false

    override fun getTerminalFont(): java.awt.Font = font

    override fun getTerminalFontSize(): Float = font.size2D

    override fun getBufferMaxLinesCount(): Int = scrollbackLines

    override fun maxRefreshRate(): Int = refreshRate

    /**
     * The palette is re-read on every paint by [TerminalPanel], so returning a fresh instance for
     * the current scheme is what makes a live color change take effect.
     */
    override fun getTerminalColorPalette(): ColorPalette = SchemeColorPalette(colorScheme)

    override fun getDefaultForeground(): TerminalColor = TerminalColor(TerminalRgb(colorScheme.foregroundRgb))

    override fun getDefaultBackground(): TerminalColor = TerminalColor(TerminalRgb(colorScheme.backgroundRgb))

    /** Default style for SGR-default cells; [EyeShellTerminalWidget] pushes it into the live `StyleState`. */
    fun defaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())

    internal fun terminalColorPaletteRgb(): List<Int> = colorScheme.ansiRgb

    companion object {
        internal const val MAX_SCROLLBACK_LINES = 100_000
        internal const val MAX_REFRESH_RATE = 50
    }
}

/** Maps a bundled scheme's 16 ANSI colors onto JediTerm's palette adapter. */
private class SchemeColorPalette(scheme: TerminalColorScheme) : ColorPalette() {
    private val colors = scheme.ansiRgb.map(::TerminalRgb)

    override fun getForegroundByColorIndex(colorIndex: Int): TerminalRgb = colors[colorIndex]

    override fun getBackgroundByColorIndex(colorIndex: Int): TerminalRgb = colors[colorIndex]
}

private fun MainBufferSnapshot.writeLogicalLines(writer: Writer) {
    fun writeLine(line: TerminalLineSnapshot) {
        line.text.forEach { character ->
            if (character != CharUtils.DWC) writer.write(character.code)
        }
        if (!line.isWrapped) writer.write("\n")
    }

    historyLines.forEach(::writeLine)
    val lastScreenLine = (screenLines.size - 1 downTo 0)
        .firstOrNull { !screenLines[it].isNulOrEmpty }
    if (lastScreenLine != null) {
        for (index in 0..lastScreenLine) writeLine(screenLines[index])
    }
}
