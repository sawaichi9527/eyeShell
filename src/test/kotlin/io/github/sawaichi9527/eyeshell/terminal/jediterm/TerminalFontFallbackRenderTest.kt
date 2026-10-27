package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession
import io.github.sawaichi9527.eyeshell.terminal.TerminalTypography
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in render proof that the per-run fallback fonts actually paint glyphs the primary font lacks.
 *
 * Two images are produced: one with a deliberately Latin-only primary font (Consolas) and one with
 * the application's resolved default font. Run with `EYESHELL_TEST_FONT_RENDER=1`; the PNGs land in
 * `build/reports/` for visual inspection because "the box is gone" is a pixel question, not an
 * assertion.
 */
class TerminalFontFallbackRenderTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "EYESHELL_TEST_FONT_RENDER", matches = "1")
    fun `renders CJK, emoji, and Nerd Font icons through fallback fonts`() {
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        assumeTrue(installed.contains("Consolas"), "Consolas is required to model a Latin-only primary font")

        render(Font("Consolas", Font.PLAIN, 16), Path.of("build", "reports", "font-fallback-latin-primary.png"))
        render(TerminalTypography.defaultFont(), Path.of("build", "reports", "font-fallback-default.png"))
    }

    private fun render(primary: Font, output: Path) {
        val view = onEdt {
            JediTermTerminalView(columns = 46, rows = 8, font = primary).also {
                it.attach(SyntheticTerminalSession.fromText(SCRIPT))
            }
        }
        val frame = onEdt {
            JFrame().apply {
                isUndecorated = true
                contentPane.add(view.component)
                size = Dimension(640, 200)
                addNotify()
                validate()
            }
        }

        try {
            awaitExit(view)
            onEdt {
                val component = view.component
                component.size = Dimension(600, 180)
                component.doLayout()
                val image = BufferedImage(640, 200, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    component.printAll(graphics)
                } finally {
                    graphics.dispose()
                }
                Files.createDirectories(output.parent)
                ImageIO.write(image, "png", output.toFile())
                assertTrue(Files.size(output) > 0)
            }
        } finally {
            onEdt { view.close() }
            onEdt { frame.dispose() }
        }
    }

    private fun awaitExit(view: JediTermTerminalView) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (view.isSessionRunning && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        check(!view.isSessionRunning) { "Synthetic session did not finish" }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                result = block()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val SCRIPT = "ASCII Applications\r\n" +
            "\uF015 \uF17C \uF303 \uE0B0 \uE0A0\r\n" +
            "CJK 中文測試 臺裡\r\n" +
            "Emoji \uD83D\uDE00 \uD83D\uDCE6\r\n"
    }
}
