package io.github.sawaichi9527.eyeshell.platform

import java.util.Properties

/**
 * Build identity of the running application.
 *
 * The Gradle version is written into `eyeshell-build.properties` at build time, so the window title,
 * the Settings page, and any future About dialog all agree with the packaged file name without a
 * second hard-coded constant.
 *
 * A development build keeps its suffix: `0.1.9-dev` shows as `eyeShell v0.1.9-dev`, so a window, a
 * screenshot, or a bug report can never be mistaken for the released `0.1.9` (releases are cut from
 * a bare version). Package metadata still needs the numeric part; see [PackageArtifacts.version].
 */
object AppInfo {
    private const val RESOURCE = "/eyeshell-build.properties"

    /** Raw project version, for example `0.1.9-dev`; empty when the resource is missing. */
    val rawVersion: String by lazy { readRawVersion() }

    /** Display version, identical to [rawVersion]; a blank resource falls back to the app name only. */
    val version: String by lazy { rawVersion }

    /** Window title, for example `eyeShell v0.1.9-dev`. */
    val windowTitle: String by lazy {
        if (version.isBlank()) PackageArtifacts.APP_NAME else "${PackageArtifacts.APP_NAME} v$version"
    }

    private fun readRawVersion(): String = try {
        val properties = Properties()
        AppInfo::class.java.getResourceAsStream(RESOURCE)?.use(properties::load)
        properties.getProperty("version").orEmpty().trim()
    } catch (_: Exception) {
        ""
    }
}
