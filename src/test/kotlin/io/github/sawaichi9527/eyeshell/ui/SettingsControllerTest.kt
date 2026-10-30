package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.platform.BackgroundImages
import io.github.sawaichi9527.eyeshell.platform.I18n
import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalCharsets
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import java.awt.Component
import java.awt.Container
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsControllerTest {
    @Test
    fun `font size edits apply immediately and are remembered`() {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<Float>()
        val controller = SettingsController(settings, applyFontSize = { size -> applied += size })
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            assertEquals("Settings", onEdt { dialog.title })
            val spinner = onEdt { findComponent(dialog, "settingsFontSize") as JSpinner }
            assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SIZE, onEdt { spinner.value })

            onEdt { spinner.value = 20 }

            assertEquals(20, settings.terminalFontSize)
            assertEquals(listOf(20f), applied)
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the terminal encoding is persisted and applied to open sessions`() {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<Charset>()
        val controller = SettingsController(
            settings,
            applyFontSize = {},
            applyCharset = { charset -> applied += charset },
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val combo = onEdt { findComponent(dialog, "settingsCharset") as JComboBox<*> }
            assertEquals(TerminalCharsets.fromId(""), onEdt { combo.selectedItem })

            onEdt { combo.selectedItem = TerminalCharsets.fromId("GBK") }

            assertEquals("GBK", settings.terminalCharsetId)
            assertEquals(listOf(Charset.forName("GBK")), applied)
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `choosing a background image imports it, persists it, and applies it`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<TerminalBackgroundImage?>()
        val picked = directory.resolve("wallpaper.png")
        java.nio.file.Files.writeString(picked, "image")
        val backgrounds = directory.resolve("backgrounds")
        val controller = SettingsController(
            settings,
            applyFontSize = {},
            applyBackgroundImage = { image -> applied += image },
            chooseBackgroundImage = { picked },
            backgroundDirectory = backgrounds,
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val choose = onEdt { findComponent(dialog, "settingsBackgroundChoose") as JButton }
            onEdt { choose.doClick() }

            val stored = backgrounds.toAbsolutePath().normalize().resolve("wallpaper.png")
            assertTrue(settings.terminalBackgroundEnabled)
            assertEquals(stored.toString(), settings.terminalBackgroundImage)
            assertTrue(java.nio.file.Files.exists(stored))
            assertEquals(TerminalBackgroundImage(stored), applied.last())

            val current = onEdt { findComponent(dialog, "settingsBackgroundCurrent") as JLabel }
            assertTrue(onEdt { current.text }.contains("wallpaper.png"), onEdt { current.text })
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `clearing the background image disables it again`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<TerminalBackgroundImage?>()
        val picked = directory.resolve("wallpaper.png")
        java.nio.file.Files.writeString(picked, "image")
        val controller = SettingsController(
            settings,
            applyFontSize = {},
            applyBackgroundImage = { image -> applied += image },
            chooseBackgroundImage = { picked },
            backgroundDirectory = directory.resolve("backgrounds"),
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            onEdt { (findComponent(dialog, "settingsBackgroundChoose") as JButton).doClick() }
            onEdt { (findComponent(dialog, "settingsBackgroundClear") as JButton).doClick() }

            assertFalse(settings.terminalBackgroundEnabled)
            assertEquals("", settings.terminalBackgroundImage)
            assertEquals(null, applied.last())
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `background opacity is persisted and re-applied to the current image`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val settings = AppSettings.inMemory()
        settings.terminalBackgroundEnabled = true
        val picked = directory.resolve("wallpaper.png")
        java.nio.file.Files.writeString(picked, "image")
        settings.terminalBackgroundImage = picked.toString()
        val applied = mutableListOf<TerminalBackgroundImage?>()
        val controller = SettingsController(
            settings,
            applyFontSize = {},
            applyBackgroundImage = { image -> applied += image },
            backgroundDirectory = directory.resolve("backgrounds"),
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val opacity = onEdt { findComponent(dialog, "settingsBackgroundOpacity") as JSpinner }
            onEdt { opacity.value = 40 }

            assertEquals(40, settings.terminalBackgroundOpacity)
            assertEquals(40, applied.last()?.opacityPercent)
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the bundled presets are listed and selecting one applies it`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<TerminalBackgroundImage?>()
        val backgrounds = directory.resolve("backgrounds")
        val controller = SettingsController(
            settings,
            applyFontSize = {},
            applyBackgroundImage = { image -> applied += image },
            backgroundDirectory = backgrounds,
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val images = onEdt { findComponent(dialog, "settingsBackgroundImages") as JList<*> }
            val names = onEdt {
                (0 until images.model.size).map { (images.model.getElementAt(it) as java.nio.file.Path).fileName.toString() }
            }
            assertEquals(
                listOf("bg1.jpg", "bg2.jpg", "bg3.jpg", "bg4.jpg", "bg5.jpg"),
                names.take(5),
            )
            assertEquals("bg1.jpg", BackgroundImages.DEFAULT_PRESET)

            onEdt { images.selectedIndex = 1 }

            val stored = backgrounds.toAbsolutePath().normalize().resolve("bg2.jpg")
            assertTrue(settings.terminalBackgroundEnabled)
            assertEquals(stored.toString(), settings.terminalBackgroundImage)
            assertTrue(java.nio.file.Files.exists(stored))
            assertEquals(TerminalBackgroundImage(stored), applied.last())
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the preview falls back when the chosen CJK family has no CJK glyphs`() {
        val settings = AppSettings.inMemory().apply { terminalFontCjkFamily = "Arial" }
        val controller = SettingsController(settings, applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val preview = onEdt {
                findComponent(dialog, "settingsFontPreview") as SettingsController.FontPreview
            }
            assertTrue(
                onEdt { preview.resolvedCjkFont("中文範例") }.canDisplay('中'),
                "A Latin-only CJK choice must fall back to a CJK-capable font, like the terminal does",
            )
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `font family lists are shown and selecting one applies it`() {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<Pair<String, String>>()
        val controller = SettingsController(
            settings,
            applyFontSize = {},
            applyFontFamilies = { latin, cjk -> applied += latin to cjk },
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val latin = onEdt { findComponent(dialog, "settingsFontFamily") as JList<*> }
            val cjk = onEdt { findComponent(dialog, "settingsCjkFontFamily") as JList<*> }
            val families = onEdt {
                (1 until latin.model.size).map { latin.model.getElementAt(it) as String }
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(families.isNotEmpty(), "No installed fonts")

            onEdt { latin.selectedIndex = latin.model.size - 1 }
            val chosen = families.last()
            assertEquals(chosen, settings.terminalFontFamily)
            assertEquals(chosen, applied.last().first)

            onEdt { cjk.selectedIndex = 1 }
            assertEquals(families.first(), settings.terminalFontCjkFamily)
            assertEquals(families.first(), applied.last().second)
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the terminal page shows a live font preview`() {
        val controller = SettingsController(AppSettings.inMemory(), applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val preview = onEdt { findComponent(dialog, "settingsFontPreview") as JComponent }
            val image = onEdt {
                preview.setSize(520, 132)
                val rendered = java.awt.image.BufferedImage(520, 132, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val graphics = rendered.createGraphics()
                try {
                    preview.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                rendered
            }
            val background = image.getRGB(519, 131) and 0xFFFFFF
            var textPixels = 0
            for (y in 0 until image.height step 2) {
                for (x in 0 until image.width step 2) {
                    if ((image.getRGB(x, y) and 0xFFFFFF) != background) textPixels++
                }
            }
            assertTrue(textPixels > 100, "Expected the preview to draw sample text, got $textPixels pixels")
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `language switches the bundle and is remembered`() {
        val settings = AppSettings.inMemory()
        val controller = SettingsController(settings, applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val language = onEdt { findComponent(dialog, "settingsLanguage") as JComboBox<*> }
            assertTrue(onEdt { language.isEnabled })
            assertEquals(5, onEdt { language.itemCount })

            val traditional = onEdt {
                (0 until language.itemCount)
                    .map { language.getItemAt(it) as LanguageOption }
                    .single { it.id == "zh-TW" }
            }
            onEdt { language.selectedItem = traditional }

            assertEquals("zh-TW", settings.localeId)
            assertEquals("zh-TW", I18n.localeId())
            assertEquals("設定", I18n.t("settings.title"))
        } finally {
            I18n.setLocaleId("en")
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the about page lists the open-source components`() {
        val controller = SettingsController(AppSettings.inMemory(), applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val table = onEdt { findComponent(dialog, "settingsAboutComponents") as JTable }
            val names = onEdt {
                (0 until table.model.rowCount).map { table.model.getValueAt(it, 0).toString() }
            }
            assertTrue(names.contains("FlatLaf"), names.toString())
            assertTrue(names.contains("Apache MINA SSHD core"), names.toString())
            assertTrue(onEdt { table.model.columnCount } == 4)
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the dialog reports the build version`() {
        val controller = SettingsController(AppSettings.inMemory(), applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val texts = onEdt { collectLabels(dialog).map(JLabel::getText) }
            assertTrue(texts.contains(AppInfo.windowTitle), texts.toString())
            assertTrue(texts.contains("Font size"), texts.toString())
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `the left categories drive the right-hand page`() {
        val controller = SettingsController(AppSettings.inMemory(), applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val categories = onEdt { findComponent(dialog, "settingsCategories") as JList<*> }
            val pages = onEdt {
                (0 until categories.model.size).map {
                    (categories.model.getElementAt(it) as SettingsCategory).key
                }
            }
            assertEquals(listOf("general", "terminal", "colors", "background", "about"), pages)
            assertEquals(0, onEdt { categories.selectedIndex })

            val generalPage = onEdt { pageOf(findComponent(dialog, "settingsLanguage")) }
            val colorsPage = onEdt { pageOf(findComponent(dialog, "settingsColorScheme")) }
            assertTrue(onEdt { generalPage.isVisible })
            assertFalse(onEdt { colorsPage.isVisible })

            onEdt { categories.selectedIndex = 2 }

            assertFalse(onEdt { generalPage.isVisible })
            assertTrue(onEdt { colorsPage.isVisible })
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `color scheme selection applies immediately and is remembered`() {
        val settings = AppSettings.inMemory()
        val applied = mutableListOf<TerminalColorScheme>()
        val controller = SettingsController(settings, applyFontSize = {}, applyColorScheme = applied::add)
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val combo = onEdt { findComponent(dialog, "settingsColorScheme") as JComboBox<*> }
            assertEquals(TerminalColorSchemes.ALL.size, onEdt { combo.itemCount })
            assertEquals(TerminalColorSchemes.DEFAULT, onEdt { combo.selectedItem })
            assertTrue(applied.isEmpty(), "opening Settings must not re-apply the current scheme")

            onEdt { combo.selectedItem = TerminalColorSchemes.NORD }

            assertEquals(listOf(TerminalColorSchemes.NORD), applied)
            assertEquals("nord", settings.terminalColorSchemeId)
            val contrast = onEdt { findComponent(dialog, "settingsColorContrast") as JLabel }
            assertTrue(onEdt { contrast.text }.contains("Contrast"), onEdt { contrast.text })
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    fun `a stored scheme is preselected without applying it`() {
        val settings = AppSettings.inMemory().apply { terminalColorSchemeId = "solarized-light" }
        val applied = mutableListOf<TerminalColorScheme>()
        val controller = SettingsController(settings, applyFontSize = {}, applyColorScheme = applied::add)
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val combo = onEdt { findComponent(dialog, "settingsColorScheme") as JComboBox<*> }
            assertEquals(TerminalColorSchemes.SOLARIZED_LIGHT, onEdt { combo.selectedItem })
            assertTrue(applied.isEmpty())
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "EYESHELL_TEST_SETTINGS_RENDER",
        matches = "1",
    )
    fun `renders the settings dialog for visual inspection`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) {
        com.formdev.flatlaf.FlatDarkLaf.setup()
        val backgrounds = directory.resolve("backgrounds")
        val controller = SettingsController(
            AppSettings.inMemory().apply { terminalColorSchemeId = "dracula" },
            applyFontSize = {},
            backgroundDirectory = backgrounds,
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val categories = onEdt { findComponent(dialog, "settingsCategories") as JList<*> }
            onEdt { categories.selectedIndex = 2 }
            onEdt { writeDialogRender(dialog, "settings-dialog.png") }
            onEdt { categories.selectedIndex = 1 }
            onEdt { writeDialogRender(dialog, "settings-terminal.png") }
            onEdt { categories.selectedIndex = 3 }
            onEdt {
                val images = findComponent(dialog, "settingsBackgroundImages") as JList<*>
                images.selectedIndex = 0
            }
            onEdt { writeDialogRender(dialog, "settings-background.png") }
            onEdt { categories.selectedIndex = 4 }
            onEdt { writeDialogRender(dialog, "settings-about.png") }

            // Show the CJK fallback path: Arial has no CJK glyphs, so the preview must fall back.
            onEdt { categories.selectedIndex = 1 }
            onEdt {
                val cjkList = findComponent(dialog, "settingsCjkFontFamily") as JList<*>
                val model = cjkList.model
                val index = (0 until model.size).firstOrNull { (model.getElementAt(it) as String) == "Arial" }
                if (index != null) cjkList.selectedIndex = index
            }
            onEdt { writeDialogRender(dialog, "settings-terminal-cjk-fallback.png") }

            // Same dialog, rebuilt in Traditional Chinese, to verify the bundle and the language row.
            onEdt { dialog.dispose() }
            onEdt { I18n.setLocaleId("zh-TW") }
            val localized = onEdt { controller.createDialog(JFrame()) }
            try {
                onEdt { writeDialogRender(localized, "settings-zh-TW.png") }
                onEdt {
                    val categories = findComponent(localized, "settingsCategories") as JList<*>
                    categories.selectedIndex = 4
                }
                onEdt { writeDialogRender(localized, "settings-about-zh-TW.png") }
            } finally {
                onEdt { localized.dispose() }
                onEdt { I18n.setLocaleId("en") }
            }
        } finally {
            onEdt { dialog.dispose() }
        }
    }

    private fun writeDialogRender(dialog: JDialog, fileName: String) {
        val pane = dialog.contentPane
        pane.setSize(dialog.width, dialog.height)
        layoutTree(pane)
        val image = java.awt.image.BufferedImage(
            pane.width,
            pane.height,
            java.awt.image.BufferedImage.TYPE_INT_RGB,
        )
        val graphics = image.createGraphics()
        try {
            pane.printAll(graphics)
        } finally {
            graphics.dispose()
        }
        val output = java.nio.file.Path.of("build", "reports", fileName)
        java.nio.file.Files.createDirectories(output.parent)
        javax.imageio.ImageIO.write(image, "png", output.toFile())
    }

    private fun layoutTree(component: Component) {
        component.doLayout()
        if (component is Container) component.components.forEach(::layoutTree)
    }

    private fun pageOf(component: Component): Component {
        var current: Component = component
        var parent: Container? = current.parent
        while (parent != null) {
            if (parent.name == "settingsBody") return current
            current = parent
            parent = current.parent
        }
        throw AssertionError("Component is not inside the settings card container")
    }

    private fun collectLabels(root: Container): List<JLabel> {
        val labels = mutableListOf<JLabel>()
        for (child in root.components) {
            if (child is JLabel) labels += child
            if (child is Container) labels += collectLabels(child)
        }
        return labels
    }

    private fun findComponent(root: Container, name: String): Component =
        findOrNull(root, name) ?: throw AssertionError("Component '$name' was not found")

    private fun findOrNull(root: Container, name: String): Component? {
        for (child in root.components) {
            if (child.name == name) return child
            if (child is Container) {
                findOrNull(child, name)?.let { return it }
            }
        }
        return null
    }

    private fun <T> onEdt(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        SwingUtilities.invokeAndWait {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }
}
