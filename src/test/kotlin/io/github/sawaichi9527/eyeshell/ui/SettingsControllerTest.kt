package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.AppInfo
import io.github.sawaichi9527.eyeshell.platform.AppSettings
import java.awt.Component
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
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
        val controller = SettingsController(settings) { size -> applied += size }
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
        val controller = SettingsController(AppSettings.inMemory()) { }
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
        val controller = SettingsController(AppSettings.inMemory()) { }
        val dialog = onEdt { controller.createDialog(JFrame()) }

        try {
            val texts = onEdt { collectLabels(dialog).map(JLabel::getText) }
            assertTrue(texts.contains(AppInfo.windowTitle), texts.toString())
            assertTrue(texts.contains("Font size"), texts.toString())
        } finally {
            onEdt { dialog.dispose() }
        }
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
