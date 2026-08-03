package io.github.sawaichi9527.eyeshell.platform

class SafeMode private constructor(
    val isActive: Boolean,
    private val defaultScrollbackLines: Int,
    private val defaultMaxRefreshRate: Int,
) {
    val scrollbackLines: Int
        get() = if (isActive) SAFE_SCROLLBACK_LINES else defaultScrollbackLines

    val maxRefreshRate: Int
        get() = if (isActive) SAFE_MAX_REFRESH_RATE else defaultMaxRefreshRate

    fun systemProperties(osName: String = System.getProperty("os.name")): Map<String, String> {
        if (!isActive) return emptyMap()
        return if (osName.startsWith("Windows", ignoreCase = true)) {
            mapOf(
                "sun.java2d.d3d" to "false",
                "sun.java2d.opengl" to "false",
            )
        } else {
            emptyMap()
        }
    }

    companion object {
        const val ARGUMENT = "--safe-mode"
        const val SAFE_SCROLLBACK_LINES = 10_000
        const val SAFE_MAX_REFRESH_RATE = 10
        const val ANIMATION_PROPERTY = "flatlaf.animation"

        fun detect(
            arguments: Array<String>,
            defaultScrollbackLines: Int,
            defaultMaxRefreshRate: Int,
        ): SafeMode = SafeMode(
            arguments.any { it == ARGUMENT },
            defaultScrollbackLines,
            defaultMaxRefreshRate,
        )
    }
}
