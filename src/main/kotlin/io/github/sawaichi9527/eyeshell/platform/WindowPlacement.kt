package io.github.sawaichi9527.eyeshell.platform

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Persisted geometry of the main window: the normal (non-maximized) frame bounds plus whether the
 * window was maximized when it closed. Stored as the `window.*` keys in `settings.properties`.
 */
data class WindowPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
) {
    val bounds: Rectangle
        get() = Rectangle(x, y, width, height)

    companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 800
        const val MINIMUM_WIDTH = 960
        const val MINIMUM_HEIGHT = 640

        /**
         * How much of the window must overlap a screen before a stored position is trusted. A window
         * that barely pokes into a screen is treated as off-screen and centered again, so changing
         * the monitor layout cannot leave the frame unreachable.
         */
        const val MIN_VISIBLE_WIDTH = 160
        const val MIN_VISIBLE_HEIGHT = 80
    }
}

/**
 * Screen work areas (device bounds minus taskbars, docks, and panels) in virtual-screen coordinates,
 * primary screen first. Empty when the environment has no display.
 */
fun screenWorkAreas(): List<Rectangle> = try {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val toolkit = Toolkit.getDefaultToolkit()
    environment.screenDevices.map { device ->
        val configuration = device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = toolkit.getScreenInsets(configuration)
        Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            (bounds.width - insets.left - insets.right).coerceAtLeast(1),
            (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
        )
    }
} catch (_: Exception) {
    emptyList()
}

/**
 * Resolves the bounds to apply at startup.
 *
 * No stored placement centers the default size on the primary screen. A stored placement keeps its
 * position while it is still usable, and is otherwise centered on the primary screen. In both cases
 * the size is clamped to the minimum window size and to the target screen's work area, so a
 * resolution change or an unplugged monitor cannot produce an unreachable or oversized window.
 */
fun resolveWindowPlacement(
    stored: WindowPlacement?,
    screens: List<Rectangle>,
    minimumSize: Dimension = Dimension(WindowPlacement.MINIMUM_WIDTH, WindowPlacement.MINIMUM_HEIGHT),
    defaultSize: Dimension = Dimension(WindowPlacement.DEFAULT_WIDTH, WindowPlacement.DEFAULT_HEIGHT),
): WindowPlacement {
    val available = screens.filter { it.width > 0 && it.height > 0 }
    if (available.isEmpty()) {
        val bounds = stored?.bounds ?: Rectangle(0, 0, defaultSize.width, defaultSize.height)
        return WindowPlacement(
            bounds.x,
            bounds.y,
            bounds.width.coerceAtLeast(minimumSize.width),
            bounds.height.coerceAtLeast(minimumSize.height),
            stored?.maximized ?: false,
        )
    }

    val primary = available.first()
    if (stored == null) {
        val size = fittedSize(defaultSize.width, defaultSize.height, primary, minimumSize)
        return WindowPlacement(
            centeredCoordinate(primary.x, primary.width, size.width),
            centeredCoordinate(primary.y, primary.height, size.height),
            size.width,
            size.height,
            maximized = false,
        )
    }

    val storedBounds = stored.bounds
    val overlap = available.maxByOrNull { overlapArea(it, storedBounds) } ?: primary
    val target = if (overlapArea(overlap, storedBounds) > 0) overlap else primary
    val size = fittedSize(stored.width, stored.height, target, minimumSize)
    val candidate = Rectangle(stored.x, stored.y, size.width, size.height)
    val visibleScreen = available.firstOrNull { screen ->
        overlapWidth(screen, candidate) >= minOf(WindowPlacement.MIN_VISIBLE_WIDTH, candidate.width) &&
            overlapHeight(screen, candidate) >= minOf(WindowPlacement.MIN_VISIBLE_HEIGHT, candidate.height)
    }

    val placed = if (visibleScreen == null) {
        Rectangle(
            centeredCoordinate(primary.x, primary.width, size.width),
            centeredCoordinate(primary.y, primary.height, size.height),
            size.width,
            size.height,
        )
    } else {
        // Keep the title bar inside the screen even when the stored window was dragged above it.
        Rectangle(
            candidate.x,
            candidate.y.coerceAtLeast(visibleScreen.y),
            candidate.width,
            candidate.height,
        )
    }
    return WindowPlacement(placed.x, placed.y, placed.width, placed.height, stored.maximized)
}

private fun fittedSize(width: Int, height: Int, screen: Rectangle, minimum: Dimension): Dimension =
    Dimension(
        width.coerceIn(minimum.width, maxOf(minimum.width, screen.width)),
        height.coerceIn(minimum.height, maxOf(minimum.height, screen.height)),
    )

private fun centeredCoordinate(start: Int, length: Int, size: Int): Int =
    start + (length - size).coerceAtLeast(0) / 2

private fun overlapWidth(left: Rectangle, right: Rectangle): Int =
    (minOf(left.x + left.width, right.x + right.width) - maxOf(left.x, right.x)).coerceAtLeast(0)

private fun overlapHeight(left: Rectangle, right: Rectangle): Int =
    (minOf(left.y + left.height, right.y + right.height) - maxOf(left.y, right.y)).coerceAtLeast(0)

private fun overlapArea(left: Rectangle, right: Rectangle): Int =
    overlapWidth(left, right) * overlapHeight(left, right)
