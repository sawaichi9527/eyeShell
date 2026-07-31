package io.github.sawaichi9527.eyeshell

import com.formdev.flatlaf.FlatDarkLaf
import io.github.sawaichi9527.eyeshell.ui.EyeShellWindow
import javax.swing.SwingUtilities

fun main() {
    FlatDarkLaf.setup()
    SwingUtilities.invokeLater {
        EyeShellWindow().isVisible = true
    }
}
