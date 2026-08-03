package io.github.sawaichi9527.eyeshell.platform

enum class DesktopSessionType {
    WAYLAND,
    X11,
    UNKNOWN,
}

object DesktopSession {
    fun detect(
        xdgSessionType: String?,
        waylandDisplay: String?,
        display: String?,
    ): DesktopSessionType = when {
        xdgSessionType.equals("wayland", ignoreCase = true) || !waylandDisplay.isNullOrBlank() -> {
            DesktopSessionType.WAYLAND
        }
        xdgSessionType.equals("x11", ignoreCase = true) || !display.isNullOrBlank() -> DesktopSessionType.X11
        else -> DesktopSessionType.UNKNOWN
    }
}

class LaunchStrategy private constructor(
    val toolkit: ToolkitChoice,
) {
    enum class ToolkitChoice {
        PLATFORM_DEFAULT,
        X11,
        WAYLAND,
    }

    fun systemProperties(): Map<String, String> = when (toolkit) {
        ToolkitChoice.PLATFORM_DEFAULT -> emptyMap()
        ToolkitChoice.X11 -> mapOf("awt.toolkit" to "sun.awt.X11.XToolkit")
        ToolkitChoice.WAYLAND -> mapOf("awt.toolkit" to "sun.awt.WLToolkit")
    }

    companion object {
        fun resolve(
            osName: String,
            session: DesktopSessionType,
            forceX11: Boolean,
            waylandToolkitSupported: Boolean,
        ): LaunchStrategy {
            if (osName.startsWith("Windows", ignoreCase = true)) {
                return LaunchStrategy(ToolkitChoice.PLATFORM_DEFAULT)
            }
            if (forceX11) return LaunchStrategy(ToolkitChoice.X11)
            if (waylandToolkitSupported && session == DesktopSessionType.WAYLAND) {
                return LaunchStrategy(ToolkitChoice.WAYLAND)
            }
            return LaunchStrategy(ToolkitChoice.X11)
        }

        fun isNativeWaylandToolkitAvailable(): Boolean = runCatching {
            Class.forName("sun.awt.WLToolkit")
        }.isSuccess
    }
}
