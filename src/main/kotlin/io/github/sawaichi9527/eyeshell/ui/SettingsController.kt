package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.AboutComponents
import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.platform.BackgroundImages
import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.platform.OpenSourceComponent
import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalCharset
import io.github.sawaichi9527.eyeshell.terminal.TerminalCharsets
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ItemEvent
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel

/**
 * Settings entry point reachable from the gear button on the Monitor sidebar.
 *
 * The layout follows the left-category / right-content shape common to terminal tools: the list on
 * the left selects a page, and only the selected page is shown on the right. Every visible string
 * comes from [I18n]; the language row switches the bundle and rebuilds this dialog immediately,
 * while the main window picks the new language up after a restart.
 *
 * Terminal font size, color scheme, encoding, and background image are applied to every open
 * session immediately and persisted to `settings.properties`.
 */
internal class SettingsController(
    private val settings: AppSettings,
    private val applyFontSize: (Float) -> Unit,
    private val applyColorScheme: (TerminalColorScheme) -> Unit = {},
    private val applyCharset: (Charset) -> Unit = {},
    private val applyBackgroundImage: (TerminalBackgroundImage?) -> Unit = {},
    private val applyLocale: () -> Unit = {},
    private val chooseBackgroundImage: (Component) -> Path? = ::chooseBackgroundImageFile,
    private val backgroundDirectory: Path = BackgroundImages.defaultDirectory(),
) {
    private val activeDialog = AtomicReference<JDialog>()
    private var owner: Frame? = null

    fun open(owner: Frame) {
        this.owner = owner
        activeDialog.get()?.takeIf(JDialog::isDisplayable)?.let { dialog ->
            dialog.toFront()
            dialog.requestFocus()
            return
        }
        val dialog = createDialog(owner)
        activeDialog.set(dialog)
        dialog.isVisible = true
    }

    fun close() {
        activeDialog.getAndSet(null)?.dispose()
    }

    /**
     * Rebuilds the dialog after a language change so every label uses the new bundle. The caller
     * keeps the same owner frame, so the dialog reappears in the same place.
     */
    private fun reopen() {
        val frame = owner ?: return
        activeDialog.getAndSet(null)?.dispose()
        open(frame)
    }

    internal fun createDialog(owner: Frame): JDialog {
        val dialog = JDialog(owner, I18n.t("settings.title"), false).apply { name = "settingsDialog" }
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val cards = JPanel(CardLayout()).apply { name = "settingsBody" }
        val categories = categoryList(cards)
        cards.add(generalPage(), CATEGORY_GENERAL)
        cards.add(terminalPage(), CATEGORY_TERMINAL)
        cards.add(colorsPage(), CATEGORY_COLORS)
        cards.add(backgroundPage(dialog), CATEGORY_BACKGROUND)
        cards.add(aboutPage(), CATEGORY_ABOUT)
        (cards.layout as CardLayout).show(cards, CATEGORY_GENERAL)

        val content = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 6, 16)
            add(categories, BorderLayout.WEST)
            add(cards, BorderLayout.CENTER)
        }

        val close = JButton(I18n.t("settings.close")).apply {
            name = "settingsClose"
            addActionListener { dialog.dispose() }
        }
        val footer = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 16, 12, 16)
            add(close, BorderLayout.EAST)
        }

        dialog.contentPane.add(content, BorderLayout.CENTER)
        dialog.contentPane.add(footer, BorderLayout.SOUTH)
        dialog.pack()
        dialog.size = Dimension(maxOf(dialog.width, 680), maxOf(dialog.height, 460))
        dialog.minimumSize = Dimension(600, 380)
        dialog.setLocationRelativeTo(owner)
        return dialog
    }

    /**
     * Builds the left category list. Selection is the only thing that drives the card layout; the
     * list keeps focus so the arrow keys/page-down navigation works the way a category list should.
     */
    private fun categoryList(cards: JPanel): JScrollPane {
        val list = JList(CATEGORIES.toTypedArray()).apply {
            name = "settingsCategories"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellWidth = 150
            visibleRowCount = 6
            selectedIndex = 0
            cellRenderer = javax.swing.DefaultListCellRenderer().let { base ->
                javax.swing.ListCellRenderer<SettingsCategory> { owner, value, index, isSelected, cellHasFocus ->
                    val label = base.getListCellRendererComponent(owner, value, index, isSelected, cellHasFocus) as JLabel
                    label.text = if (value == null) "" else I18n.t(value.labelKey)
                    label.border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                    label
                }
            }
        }
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selected = list.selectedValue ?: return@addListSelectionListener
                (cards.layout as CardLayout).show(cards, selected.key)
            }
        }
        return JScrollPane(list).apply {
            name = "settingsCategoriesScroll"
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, mutedColor())
            preferredSize = Dimension(160, 340)
            viewportBorder = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        }
    }

    private fun generalPage(): JPanel = page().apply {
        addHeading(this, I18n.t("settings.category.general"))

        val options = LANGUAGE_OPTIONS.toTypedArray()
        val language = JComboBox(options).apply {
            name = "settingsLanguage"
            selectedItem = options.first { it.id == settings.localeId }
            preferredSize = Dimension(200, preferredSize.height)
            renderer = javax.swing.DefaultListCellRenderer().let { base ->
                javax.swing.ListCellRenderer<LanguageOption> { owner, value, index, isSelected, cellHasFocus ->
                    val label = base.getListCellRendererComponent(owner, value, index, isSelected, cellHasFocus) as JLabel
                    label.text = if (value == null) "" else I18n.t(value.labelKey)
                    label
                }
            }
            addActionListener {
                val chosen = selectedItem as? LanguageOption ?: return@addActionListener
                if (chosen.id == settings.localeId) return@addActionListener
                settings.localeId = chosen.id
                I18n.setLocaleId(chosen.id)
                applyLocale()
                reopen()
            }
        }
        addRow(this, I18n.t("settings.general.language"), language, I18n.t("settings.general.language.hint"))
    }

    private fun terminalPage(): JPanel = page().apply {
        addHeading(this, I18n.t("settings.category.terminal"))
        val fontSpinner = JSpinner(
            SpinnerNumberModel(
                settings.terminalFontSize,
                AppSettings.MIN_TERMINAL_FONT_SIZE,
                AppSettings.MAX_TERMINAL_FONT_SIZE,
                1,
            ),
        ).apply {
            name = "settingsFontSize"
            toolTipText = I18n.t("settings.terminal.fontSize")
            addChangeListener {
                val size = (value as Number).toInt()
                if (size != settings.terminalFontSize) {
                    settings.terminalFontSize = size
                    applyFontSize(size.toFloat())
                }
            }
        }
        addRow(this, I18n.t("settings.terminal.fontSize"), fontSpinner, I18n.t("settings.terminal.fontSize.hint"))

        val encoding = JComboBox(TerminalCharsets.ALL.toTypedArray()).apply {
            name = "settingsCharset"
            toolTipText = I18n.t("settings.terminal.encoding")
            selectedItem = TerminalCharsets.fromId(settings.terminalCharsetId)
            preferredSize = Dimension(200, preferredSize.height)
            addActionListener {
                val selected = selectedItem as? TerminalCharset ?: return@addActionListener
                if (selected.id != settings.terminalCharsetId) {
                    settings.terminalCharsetId = selected.id
                    applyCharset(selected.charset)
                }
            }
        }
        addRow(this, I18n.t("settings.terminal.encoding"), encoding, I18n.t("settings.terminal.encoding.hint"))
    }

    private fun colorsPage(): JPanel = page().apply {
        addHeading(this, I18n.t("settings.category.colors"))
        val preview = ColorSchemePreview().apply {
            name = "settingsColorSchemePreview"
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val contrast = JLabel().apply {
            name = "settingsColorContrast"
            foreground = mutedColor()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        fun show(scheme: TerminalColorScheme) {
            preview.scheme = scheme
            val ratio = String.format(Locale.ROOT, "%.1f", scheme.contrastRatio)
            contrast.text = I18n.t("settings.colors.contrast", ratio)
        }

        val combo = JComboBox(TerminalColorSchemes.ALL.toTypedArray()).apply {
            name = "settingsColorScheme"
            toolTipText = I18n.t("settings.colors.scheme")
            selectedItem = TerminalColorSchemes.fromId(settings.terminalColorSchemeId)
            renderer = schemeRenderer()
            preferredSize = Dimension(200, preferredSize.height)
            addActionListener {
                val scheme = selectedItem as? TerminalColorScheme ?: return@addActionListener
                show(scheme)
                if (scheme.id != settings.terminalColorSchemeId) {
                    settings.terminalColorSchemeId = scheme.id
                    applyColorScheme(scheme)
                }
            }
        }
        addRow(this, I18n.t("settings.colors.scheme"), combo, I18n.t("settings.colors.scheme.hint"))
        add(Box.createVerticalStrut(10).apply { setAlignmentX(Component.LEFT_ALIGNMENT) })
        add(preview)
        add(Box.createVerticalStrut(6).apply { setAlignmentX(Component.LEFT_ALIGNMENT) })
        add(contrast)
        show(combo.selectedItem as TerminalColorScheme)
    }

    /**
     * Background image page: enable switch, image list (bundled `bg1`–`bg5` first, then imported
     * files), choose/clear actions, and opacity. Every change applies to open sessions at once,
     * matching the Colors page. The list is populated even before a selection exists, because the
     * bundled presets are materialised when the page opens.
     */
    private fun backgroundPage(dialog: JDialog): JPanel = page().apply {
        addHeading(this, I18n.t("settings.category.background"))

        BackgroundImages.materializePresets(backgroundDirectory)

        var updating = false

        val enabled = JCheckBox(I18n.t("settings.background.enabled"), settings.terminalBackgroundEnabled).apply {
            name = "settingsBackgroundEnabled"
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(enabled)

        val current = JLabel().apply {
            name = "settingsBackgroundCurrent"
            foreground = mutedColor()
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 6, 0)
        }
        add(current)

        val choose = JButton(I18n.t("settings.background.choose")).apply { name = "settingsBackgroundChoose" }
        val clear = JButton(I18n.t("settings.background.clear")).apply { name = "settingsBackgroundClear" }
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(choose)
                add(clear)
            },
        )

        val opacity = JSpinner(
            SpinnerNumberModel(
                settings.terminalBackgroundOpacity,
                AppSettings.MIN_BACKGROUND_OPACITY,
                AppSettings.MAX_BACKGROUND_OPACITY,
                5,
            ),
        ).apply {
            name = "settingsBackgroundOpacity"
            toolTipText = I18n.t("settings.background.opacity")
        }
        add(Box.createVerticalStrut(6).apply { setAlignmentX(Component.LEFT_ALIGNMENT) })
        addRow(this, I18n.t("settings.background.opacity"), opacity, I18n.t("settings.background.opacity.hint"))

        val images = JList<Path>().apply {
            name = "settingsBackgroundImages"
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 5
            cellRenderer = ImagePathRenderer()
        }
        add(
            JLabel(I18n.t("settings.background.images")).apply {
                foreground = mutedColor()
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(10, 0, 4, 0)
            },
        )
        add(
            JScrollPane(images).apply {
                name = "settingsBackgroundImagesScroll"
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(340, 130)
                maximumSize = Dimension(Int.MAX_VALUE, 150)
            },
        )
        add(
            JLabel(I18n.t("settings.background.hint")).apply {
                foreground = mutedColor()
                font = font.deriveFont(font.size2D - 1f)
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            },
        )

        fun refresh() {
            updating = true
            try {
                val selection = currentBackgroundImage()
                enabled.isSelected = settings.terminalBackgroundEnabled
                enabled.isEnabled = selection != null || settings.terminalBackgroundImage.isNotBlank()
                clear.isEnabled = enabled.isEnabled
                current.text = selection?.let { selected ->
                    val missing = if (Files.isRegularFile(selected.file)) "" else " " + I18n.t("settings.background.missing")
                    I18n.t("settings.background.current", "${selected.file.fileName}$missing")
                } ?: I18n.t("settings.background.currentNone")

                val available = BackgroundImages.listWithPresets(backgroundDirectory)
                images.setListData(available.toTypedArray())
                val stored = selection?.file?.toAbsolutePath()?.normalize()
                images.setSelectedValue(
                    available.firstOrNull { it.toAbsolutePath().normalize() == stored },
                    true,
                )
            } finally {
                updating = false
            }
        }

        fun applySelection() {
            applyBackgroundImage(currentBackgroundImage())
            refresh()
        }

        enabled.addItemListener { event ->
            if (updating) return@addItemListener
            settings.terminalBackgroundEnabled = event.stateChange == ItemEvent.SELECTED
            applySelection()
        }
        clear.addActionListener {
            settings.terminalBackgroundEnabled = false
            settings.terminalBackgroundImage = ""
            applySelection()
        }
        choose.addActionListener {
            val picked = chooseBackgroundImage(dialog) ?: return@addActionListener
            val stored = runCatching { BackgroundImages.import(picked, backgroundDirectory) }.getOrNull()
                ?: return@addActionListener
            settings.terminalBackgroundImage = stored.toString()
            settings.terminalBackgroundEnabled = true
            applySelection()
        }
        opacity.addChangeListener {
            val value = (opacity.value as Number).toInt()
            if (value == settings.terminalBackgroundOpacity) return@addChangeListener
            settings.terminalBackgroundOpacity = value
            if (settings.terminalBackgroundEnabled) applySelection()
        }
        images.addListSelectionListener { event ->
            if (updating || event.valueIsAdjusting) return@addListSelectionListener
            val picked = images.selectedValue ?: return@addListSelectionListener
            settings.terminalBackgroundImage = picked.toString()
            settings.terminalBackgroundEnabled = true
            applySelection()
        }

        refresh()
    }

    private fun currentBackgroundImage(): TerminalBackgroundImage? = TerminalBackgroundImage.fromSettings(
        enabled = settings.terminalBackgroundEnabled,
        storedPath = settings.terminalBackgroundImage,
        opacityPercent = settings.terminalBackgroundOpacity,
    )

    /**
     * About page: build/runtime facts plus the open-source components generated from the resolved
     * runtime classpath, and the bundled third-party notices.
     */
    private fun aboutPage(): JPanel = page().apply {
        addHeading(this, I18n.t("settings.category.about"))
        addRow(this, I18n.t("settings.about.version"), JLabel(AppInfo.windowTitle), null)
        addRow(
            this,
            I18n.t("settings.about.java"),
            JLabel("${System.getProperty("java.vendor").orEmpty()} ${System.getProperty("java.version").orEmpty()}"),
            null,
        )
        addRow(
            this,
            I18n.t("settings.about.system"),
            JLabel("${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.arch").orEmpty()}"),
            null,
        )

        addHeading(this, I18n.t("settings.about.components"))
        val components = AboutComponents.load()
        val table = JTable(AboutTableModel(components)).apply {
            name = "settingsAboutComponents"
            autoCreateRowSorter = true
            rowHeight = 24
            fillsViewportHeight = true
            columnModel.apply {
                getColumn(0).preferredWidth = 240
                getColumn(1).preferredWidth = 80
                getColumn(2).preferredWidth = 120
                getColumn(3).preferredWidth = 80
            }
        }
        add(
            JScrollPane(table).apply {
                name = "settingsAboutComponentsScroll"
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(560, 210)
                maximumSize = Dimension(Int.MAX_VALUE, 260)
            },
        )
        val notices = JButton(I18n.t("settings.about.notices")).apply {
            name = "settingsAboutNotices"
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }
        add(Box.createVerticalStrut(8).apply { setAlignmentX(Component.LEFT_ALIGNMENT) })
        add(notices)
        val page = this
        notices.addActionListener { showNotices(page) }
    }

    private fun showNotices(parent: Component) {
        val dialog = JDialog(
            SwingUtilities.getWindowAncestor(parent) as? Frame,
            I18n.t("settings.about.noticesTitle"),
            true,
        )
        val text = JTextArea(AboutComponents.thirdPartyNotices()).apply {
            name = "settingsAboutNoticesText"
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            caretPosition = 0
        }
        val close = JButton(I18n.t("settings.close")).apply {
            addActionListener { dialog.dispose() }
        }
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(close) }
        dialog.contentPane.add(JScrollPane(text), BorderLayout.CENTER)
        dialog.contentPane.add(footer, BorderLayout.SOUTH)
        dialog.setSize(720, 560)
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
    }

    private fun page(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(2, 4, 6, 4)
    }

    private fun addHeading(panel: JPanel, text: String): JPanel {
        panel.add(
            JLabel(text).apply {
                font = font.deriveFont(Font.BOLD)
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(10, 0, 8, 0)
            },
        )
        return panel
    }

    private fun addRow(
        panel: JPanel,
        label: String,
        field: JComponent,
        hint: String?,
    ): JPanel {
        val row = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }
        row.add(
            JLabel(label, SwingConstants.LEFT).apply {
                foreground = mutedColor()
                preferredSize = Dimension(96, preferredSize.height)
            },
            BorderLayout.WEST,
        )
        val holder = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(field, BorderLayout.WEST)
        }
        if (hint != null) {
            holder.add(
                JLabel(hint).apply {
                    foreground = mutedColor()
                    font = font.deriveFont(font.size2D - 1f)
                    border = BorderFactory.createEmptyBorder(0, 10, 0, 0)
                },
                BorderLayout.CENTER,
            )
        }
        row.add(holder, BorderLayout.CENTER)
        panel.add(row)
        // BoxLayout honours maximumSize, so a row must clamp it or the vertical stack stretches the
        // row (and with it the combo box) to the remaining page height.
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return panel
    }

    private fun schemeRenderer(): javax.swing.ListCellRenderer<Any> {
        val label = JLabel()
        return javax.swing.ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            label.apply {
                val scheme = value as TerminalColorScheme
                text = scheme.displayName
                border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
                isOpaque = true
                background = if (isSelected) {
                    list.selectionBackground ?: UIManager.getColor("List.selectionBackground")
                } else {
                    list.background
                }
                foreground = if (isSelected) {
                    list.selectionForeground ?: UIManager.getColor("List.selectionForeground")
                } else {
                    list.foreground
                }
            }
        }
    }

    private fun mutedColor(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

    /** Badge-free sample of the selected scheme: background, default text, and three ANSI colors. */
    private class ColorSchemePreview : JPanel() {
        var scheme: TerminalColorScheme = TerminalColorSchemes.DEFAULT
            set(value) {
                field = value
                repaint()
            }

        init {
            preferredSize = Dimension(420, 132)
            maximumSize = Dimension(Int.MAX_VALUE, 132)
            border = BorderFactory.createLineBorder(muted(), 1)
        }

        override fun paintComponent(g: Graphics) {
            val gfx = g.create() as Graphics2D
            try {
                gfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                gfx.color = Color(scheme.backgroundRgb)
                gfx.fillRect(0, 0, width, height)
                gfx.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
                val left = 12
                var baseline = 24
                val lineHeight = 22
                gfx.color = Color(scheme.foregroundRgb)
                gfx.drawString("user@host:~$ ls -la", left, baseline)
                baseline += lineHeight
                gfx.color = Color(scheme.ansiRgb[12])
                gfx.drawString("drwxr-xr-x  3 user user  workspace", left, baseline)
                baseline += lineHeight
                gfx.color = Color(scheme.ansiRgb[2])
                gfx.drawString("[  OK  ] service started", left, baseline)
                baseline += lineHeight
                gfx.color = Color(scheme.ansiRgb[1])
                gfx.drawString("error: connection refused", left, baseline)
            } finally {
                gfx.dispose()
            }
        }

        private companion object {
            fun muted(): Color = UIManager.getColor("Component.borderColor") ?: Color.GRAY
        }
    }

    /** Read-only table model; headers are resolved once so a language change rebuilds the dialog. */
    private class AboutTableModel(
        private val components: List<OpenSourceComponent>,
    ) : AbstractTableModel() {
        private val headers = arrayOf(
            I18n.t("settings.about.component"),
            I18n.t("settings.about.versionColumn"),
            I18n.t("settings.about.licenseColumn"),
            I18n.t("settings.about.scopeColumn"),
        )

        override fun getRowCount(): Int = components.size

        override fun getColumnCount(): Int = headers.size

        override fun getColumnName(column: Int): String = headers[column]

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val component = components[rowIndex]
            return when (columnIndex) {
                0 -> component.name
                1 -> component.version
                2 -> component.license
                else -> I18n.t(
                    when (component.scope) {
                        OpenSourceComponent.Scope.TEST -> "settings.about.scope.test"
                        OpenSourceComponent.Scope.ASSET -> "settings.about.scope.asset"
                        OpenSourceComponent.Scope.RUNTIME -> "settings.about.scope.runtime"
                    },
                )
            }
        }
    }

    internal companion object {
        const val CATEGORY_GENERAL = "general"
        const val CATEGORY_TERMINAL = "terminal"
        const val CATEGORY_COLORS = "colors"
        const val CATEGORY_BACKGROUND = "background"
        const val CATEGORY_ABOUT = "about"

        internal val CATEGORIES = listOf(
            SettingsCategory(CATEGORY_GENERAL, "settings.category.general"),
            SettingsCategory(CATEGORY_TERMINAL, "settings.category.terminal"),
            SettingsCategory(CATEGORY_COLORS, "settings.category.colors"),
            SettingsCategory(CATEGORY_BACKGROUND, "settings.category.background"),
            SettingsCategory(CATEGORY_ABOUT, "settings.category.about"),
        )

        internal val LANGUAGE_OPTIONS = listOf(
            LanguageOption(I18n.SYSTEM_ID, "settings.language.system"),
            LanguageOption("en", "settings.language.en"),
            LanguageOption("zh-TW", "settings.language.zh_TW"),
            LanguageOption("zh-CN", "settings.language.zh_CN"),
            LanguageOption("ja", "settings.language.ja"),
        )
    }
}

internal data class SettingsCategory(val key: String, val labelKey: String)

internal data class LanguageOption(val id: String, val labelKey: String)

/** File names only, so the list shows the imported image rather than its absolute path. */
private class ImagePathRenderer : JLabel(), javax.swing.ListCellRenderer<Path> {
    override fun getListCellRendererComponent(
        list: JList<out Path>,
        value: Path?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val name = value?.fileName?.toString().orEmpty()
        text = if (BackgroundImages.presetNameOf(value) != null) {
            "$name   ${I18n.t("settings.background.preset")}"
        } else {
            name
        }
        toolTipText = value?.toString()
        isOpaque = true
        background = if (isSelected) {
            list.selectionBackground ?: UIManager.getColor("List.selectionBackground")
        } else {
            list.background
        }
        foreground = if (isSelected) {
            list.selectionForeground ?: UIManager.getColor("List.selectionForeground")
        } else {
            list.foreground
        }
        border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
        return this
    }
}

/** Default picker used by the Background page; tests inject their own chooser. */
private fun chooseBackgroundImageFile(parent: Component): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = I18n.t("settings.background.choose")
        fileSelectionMode = JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = false
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "Images (${BackgroundImages.SUPPORTED_EXTENSIONS.joinToString(", ")})",
            *BackgroundImages.SUPPORTED_EXTENSIONS.toTypedArray(),
        )
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()
    } else {
        null
    }
}
