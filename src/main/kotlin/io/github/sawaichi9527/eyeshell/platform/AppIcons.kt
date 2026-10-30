package io.github.sawaichi9527.eyeshell.platform

import java.awt.Image
import javax.imageio.ImageIO

/**
 * Window/taskbar icons bundled with eyeShell.
 *
 * The PNGs are pre-rendered at the sizes AWT asks for (`JFrame.setIconImages` picks the closest), and
 * `packaging/eyeshell.ico` reuses the same artwork for the Windows package.
 */
object AppIcons {
    val SIZES: List<Int> = listOf(512, 256, 128, 64, 48, 32, 16)

    fun images(): List<Image> = SIZES.mapNotNull { size -> image(size) }

    fun image(size: Int): Image? =
        AppIcons::class.java.getResourceAsStream("/icons/eyeshell-$size.png")?.use { stream ->
            runCatching { ImageIO.read(stream) }.getOrNull()
        }
}
