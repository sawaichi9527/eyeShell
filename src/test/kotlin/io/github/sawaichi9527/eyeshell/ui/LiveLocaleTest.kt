package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.I18n
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The main window must follow a language change immediately instead of waiting for a restart.
 */
class LiveLocaleTest {
    @Test
    fun `the workbench relocalizes its static texts`() {
        val panel = onEdt { WorkbenchPanel() }
        try {
            onEdt {
                I18n.setLocaleId("zh-TW")
                panel.relocalize()
            }

            assertEquals("連線…", onEdt { (findComponent(panel, "connectButton") as JButton).text })
            assertEquals("主機…", onEdt { (findComponent(panel, "hostsButton") as JButton).text })
            assertEquals("未連線", onEdt { (findComponent(panel, "connectionStatus") as JLabel).text })
            assertEquals("顯示工具", onEdt { (findComponent(panel, "toolDockToggle") as JButton).text })

            onEdt {
                I18n.setLocaleId("ja")
                panel.relocalize()
            }
            assertEquals("接続…", onEdt { (findComponent(panel, "connectButton") as JButton).text })
        } finally {
            onEdt { I18n.setLocaleId("en") }
        }
    }

    @Test
    fun `the commands panel relocalizes its buttons and preview title`() {
        val panel = onEdt { CommandsPanel() }
        try {
            onEdt {
                I18n.setLocaleId("zh-TW")
                panel.relocalize()
            }

            assertEquals("執行", onEdt { (findComponent(panel, "runCommandButton") as JButton).text })
            assertEquals("尚無儲存的指令。", onEdt { (findComponent(panel, "commandSnippetStatus") as JLabel).text })
        } finally {
            onEdt { I18n.setLocaleId("en") }
        }
    }

    @Test
    fun `table headers follow the language change`() {
        val panel = onEdt { SftpPanel() }
        try {
            onEdt {
                I18n.setLocaleId("zh-TW")
                panel.relocalize()
            }

            val fileTable = onEdt { findComponent(panel, "sftpTable") as JTable }
            assertEquals("名稱", onEdt { fileTable.columnModel.getColumn(0).headerValue })
            assertEquals("權限", onEdt { fileTable.columnModel.getColumn(4).headerValue })

            val transferTable = onEdt { findComponent(panel, "sftpTransferTable") as JTable }
            assertEquals("方向", onEdt { transferTable.columnModel.getColumn(0).headerValue })
            assertEquals("狀態", onEdt { transferTable.columnModel.getColumn(3).headerValue })
        } finally {
            onEdt { I18n.setLocaleId("en") }
        }
    }

    @Test
    fun `the monitor process headers follow the language change`() {
        val panel = onEdt { MonitorPanel() }
        try {
            onEdt {
                I18n.setLocaleId("ja")
                panel.relocalize()
            }

            val table = panel.processTableComponent
            assertEquals("名前", onEdt { table.columnModel.getColumn(0).headerValue })
            assertEquals("メモリ", onEdt { table.columnModel.getColumn(1).headerValue })
        } finally {
            onEdt { I18n.setLocaleId("en") }
        }
    }

    private fun findComponent(root: Component, name: String): Component? {
        if (root.name == name) return root
        if (root is Container) {
            root.components.forEach { child ->
                findComponent(child, name)?.let { return it }
            }
        }
        return null
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
