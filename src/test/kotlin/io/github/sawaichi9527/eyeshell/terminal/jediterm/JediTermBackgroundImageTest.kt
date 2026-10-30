package io.github.sawaichi9527.eyeshell.terminal.jediterm

import io.github.sawaichi9527.eyeshell.terminal.TerminalBackgroundImage
import io.github.sawaichi9527.eyeshell.terminal.TerminalColorSchemes
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves the background-image contract of the fork patch: the image shows through default-colored
 * cells, explicit colors are untouched, and a missing file degrades to the plain scheme background.
 */
class JediTermBackgroundImageTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a background image is painted behind the default cell background`() {
        val file = writeImage("red.png", 0xFFFF0000.toInt())
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 10) }

        try {
            onEdt { view.setBackgroundImageForTest(TerminalBackgroundImage(file)) }

            assertTrue(view.terminalPanel.hasBackgroundImage)
            assertFalse(view.terminalPanel.paintsDefaultBackground)

            val pixel = renderImage(view).getRGB(60, 60)
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            assertTrue(red > 200, "Expected the red image to show through, was ${Integer.toHexString(pixel)}")
            assertTrue(green < 60, "Expected a red-dominant pixel, was ${Integer.toHexString(pixel)}")
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `clearing the image restores the scheme background`() {
        val file = writeImage("red.png", 0xFFFF0000.toInt())
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 10) }

        try {
            onEdt { view.setBackgroundImageForTest(TerminalBackgroundImage(file)) }
            onEdt { view.setBackgroundImageForTest(null) }

            assertFalse(view.terminalPanel.hasBackgroundImage)
            assertTrue(view.terminalPanel.paintsDefaultBackground)
            assertEquals(TerminalColorSchemes.DEFAULT.backgroundRgb or ALPHA_OPAQUE, renderImage(view).getRGB(60, 60))
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `a missing image file falls back to the scheme background`() {
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 10) }

        try {
            onEdt { view.setBackgroundImageForTest(TerminalBackgroundImage(directory.resolve("missing.png"))) }

            assertFalse(view.terminalPanel.hasBackgroundImage)
            assertTrue(view.terminalPanel.paintsDefaultBackground)
            assertEquals(TerminalColorSchemes.DEFAULT.backgroundRgb or ALPHA_OPAQUE, renderImage(view).getRGB(60, 60))
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `a corrupt image file falls back to the scheme background`() {
        val file = directory.resolve("corrupt.png")
        java.nio.file.Files.writeString(file, "not an image")
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 10) }

        try {
            onEdt { view.setBackgroundImageForTest(TerminalBackgroundImage(file)) }

            assertFalse(view.terminalPanel.hasBackgroundImage)
            assertEquals(TerminalColorSchemes.DEFAULT.backgroundRgb or ALPHA_OPAQUE, renderImage(view).getRGB(60, 60))
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `opacity blends the image over the scheme background`() {
        val file = writeImage("red.png", 0xFFFF0000.toInt())
        val view = onEdt { JediTermTerminalView(columns = 40, rows = 10) }

        try {
            onEdt { view.setBackgroundImageForTest(TerminalBackgroundImage(file, opacityPercent = 20)) }

            val pixel = renderImage(view).getRGB(60, 60)
            val background = TerminalColorSchemes.DEFAULT.backgroundRgb
            val alpha = 0.2
            val expectedRed = ((background shr 16 and 0xFF) * (1 - alpha) + 255 * alpha).toInt()
            val actualRed = pixel shr 16 and 0xFF
            assertTrue(
                kotlin.math.abs(actualRed - expectedRed) <= 4,
                "Expected blended red near $expectedRed, was $actualRed",
            )
            assertTrue((pixel and 0xFF) < 80, "Expected the background blue to remain, was ${Integer.toHexString(pixel)}")
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    fun `the loader caps oversized sources and rejects non-images`() {
        val wide = BufferedImage(6000, 40, BufferedImage.TYPE_INT_RGB)
        val file = directory.resolve("wide.png")
        ImageIO.write(wide, "png", file.toFile())

        val loaded = BackgroundImageLoader.load(file)
        assertTrue(loaded != null)
        assertEquals(BackgroundImageLoader.MAX_SOURCE_EDGE, loaded!!.width)
        assertTrue(loaded.height >= 1)

        assertTrue(BackgroundImageLoader.load(directory.resolve("missing.png")) == null)
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "EYESHELL_TEST_BACKGROUND_RENDER",
        matches = "1",
    )
    fun `renders a terminal with a background image for visual inspection`() {
        val file = writeGradient("gradient.png", 400, 300)
        val view = onEdt { JediTermTerminalView(columns = 60, rows = 12) }

        try {
            onEdt {
                view.setBackgroundImageForTest(TerminalBackgroundImage(file))
                view.attach(
                    io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession.fromText(
                        buildString {
                            append("\u001B[1;36meyeShell background image\u001B[0m\r\n")
                            append("\u001B[32mdrwxr-xr-x  3 user user  workspace\u001B[0m\r\n")
                            append("plain default-colored line shows the image through\r\n")
                            append("\u001B[41mANSI red background stays opaque\u001B[0m\r\n")
                            append("\u001B[1;33m[  OK  ] service started\u001B[0m\r\n")
                        },
                    ),
                )
            }
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos()
            while (onEdt { view.isSessionRunning } && System.nanoTime() < deadline) Thread.sleep(10)

            val image = onEdt { renderImage(view) }
            val report = Path.of("build", "reports", "terminal-background.png")
            java.nio.file.Files.createDirectories(report.parent)
            ImageIO.write(image, "png", report.toFile())
        } finally {
            onEdt { view.close() }
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "EYESHELL_TEST_BACKGROUND_RENDER",
        matches = "1",
    )
    fun `renders the bundled bg1 preset for visual inspection`() {
        val file = checkNotNull(
            io.github.sawaichi9527.eyeshell.platform.BackgroundImages.materializePreset("bg1.jpg", directory),
        )
        val view = onEdt { JediTermTerminalView(columns = 60, rows = 12) }

        try {
            onEdt {
                view.setBackgroundImageForTest(TerminalBackgroundImage(file))
                view.attach(
                    io.github.sawaichi9527.eyeshell.terminal.SyntheticTerminalSession.fromText(
                        buildString {
                            append("\u001B[1;36meyeShell · bundled preset bg1\u001B[0m\r\n")
                            append("\u001B[32mdrwxr-xr-x  3 user user  workspace\u001B[0m\r\n")
                            append("plain default-colored line shows the image through\r\n")
                            append("\u001B[41mANSI red background stays opaque\u001B[0m\r\n")
                            append("\u001B[1;33m[  OK  ] service started\u001B[0m\r\n")
                        },
                    ),
                )
            }
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos()
            while (onEdt { view.isSessionRunning } && System.nanoTime() < deadline) Thread.sleep(10)

            val image = onEdt { renderImage(view) }
            val report = Path.of("build", "reports", "terminal-background-preset.png")
            java.nio.file.Files.createDirectories(report.parent)
            ImageIO.write(image, "png", report.toFile())
        } finally {
            onEdt { view.close() }
        }
    }

    private fun writeGradient(name: String, width: Int, height: Int): Path {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val red = 40 + (x * 120 / width)
                val green = 60 + (y * 140 / height)
                val blue = 120 + ((x + y) * 80 / (width + height))
                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
        }
        val file = directory.resolve(name)
        ImageIO.write(image, "png", file.toFile())
        return file
    }

    private fun writeImage(name: String, argb: Int): Path {
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, argb)
            }
        }
        val file = directory.resolve(name)
        ImageIO.write(image, "png", file.toFile())
        return file
    }

    private fun renderImage(view: JediTermTerminalView): BufferedImage {
        val component = view.component
        val frame = javax.swing.JFrame().apply {
            isUndecorated = true
            contentPane.add(component)
            size = Dimension(520, 160)
            addNotify()
            validate()
        }
        try {
            component.size = Dimension(500, 140)
            component.doLayout()
            val image = BufferedImage(500, 140, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                component.printAll(graphics)
            } finally {
                graphics.dispose()
            }
            return image
        } finally {
            frame.dispose()
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val ALPHA_OPAQUE = 0xFF000000.toInt()
    }
}
