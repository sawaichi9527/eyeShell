package io.github.sawaichi9527.eyeshell.terminal.jediterm

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Decodes and scales terminal background images.
 *
 * Decoding runs off the Swing EDT and the decoded source is capped at [MAX_SOURCE_EDGE] pixels per
 * side, so a 8K wallpaper never reaches the paint path at full resolution. Scaling to the panel size
 * is cached by the caller, which keeps repaint free of decode and resize work.
 */
internal object BackgroundImageLoader {
    const val MAX_SOURCE_EDGE = 4096
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** Returns `null` for a missing, oversized, undecodable, or non-image file. */
    fun load(file: Path): BufferedImage? {
        return try {
            if (!Files.isRegularFile(file)) return null
            if (Files.size(file) > MAX_FILE_BYTES) return null
            val decoded = ImageIO.read(file.toFile()) ?: return null
            downscale(decoded)
        } catch (_: Throwable) {
            null
        }
    }

    /** Scales to cover [width]x[height] with the aspect ratio preserved and centres the result. */
    fun cover(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val gfx = target.createGraphics()
        try {
            applyQualityHints(gfx)
            val scale = maxOf(width.toDouble() / image.width, height.toDouble() / image.height)
            val scaledWidth = maxOf(width, (image.width * scale).toInt())
            val scaledHeight = maxOf(height, (image.height * scale).toInt())
            gfx.drawImage(
                image,
                (width - scaledWidth) / 2,
                (height - scaledHeight) / 2,
                scaledWidth,
                scaledHeight,
                null,
            )
        } finally {
            gfx.dispose()
        }
        return target
    }

    private fun downscale(image: BufferedImage): BufferedImage {
        val longest = maxOf(image.width, image.height)
        if (longest <= MAX_SOURCE_EDGE) return image
        val scale = MAX_SOURCE_EDGE.toDouble() / longest
        val width = maxOf(1, (image.width * scale).toInt())
        val height = maxOf(1, (image.height * scale).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val gfx = target.createGraphics()
        try {
            applyQualityHints(gfx)
            gfx.drawImage(image, 0, 0, width, height, null)
        } finally {
            gfx.dispose()
        }
        return target
    }

    private fun applyQualityHints(gfx: java.awt.Graphics2D) {
        gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        gfx.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }
}
