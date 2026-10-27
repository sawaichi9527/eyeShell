package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorScheme
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSpinner
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
    fun `language is visible but explicitly unavailable`() {
        val controller = SettingsController(AppSettings.inMemory(), applyFontSize = {})
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val language = onEdt { findComponent(dialog, "settingsLanguage") as JComboBox<*> }
            assertFalse(onEdt { language.isEnabled })
            assertEquals("English", onEdt { language.selectedItem })
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
            val pages = onEdt { (0 until categories.model.size).map { categories.model.getElementAt(it) } }
            assertEquals(listOf("General", "Terminal", "Colors", "About"), pages)
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
    fun `renders the settings dialog for visual inspection`() {
        com.formdev.flatlaf.FlatDarkLaf.setup()
        val controller = SettingsController(
            AppSettings.inMemory().apply { terminalColorSchemeId = "dracula" },
            applyFontSize = {},
        )
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val categories = onEdt { findComponent(dialog, "settingsCategories") as JList<*> }
            onEdt { categories.selectedIndex = 2 }
            onEdt { writeDialogRender(dialog, "settings-dialog.png") }
            onEdt { categories.selectedIndex = 1 }
            onEdt { writeDialogRender(dialog, "settings-terminal.png") }
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
