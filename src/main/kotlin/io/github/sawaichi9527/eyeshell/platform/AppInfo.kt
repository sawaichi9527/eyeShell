package io.github.sawaichi9527.eyeshell.platform

import java.util.Properties

/**
 * Build identity of the running application.
 *
 * The Gradle version is written into `eyeshell-build.properties` at build time, so the window title,
 * the Settings page, and any future About dialog all agree with the packaged file name without a
 * second hard-coded constant. The snapshot suffix is dropped for display: `0.1.1-SNAPSHOT` is
 * shipped as `0.1.1`, exactly like the package version.
 */
object AppInfo {
    private const val RESOURCE = "/eyeshell-build.properties"

    /** Raw project version, for example `0.1.1-SNAPSHOT`; empty when the resource is missing. */
    val rawVersion: String by lazy { readRawVersion() }

    /** Display/package version, for example `0.1.1`; empty when the resource is missing. */
    val version: String by lazy { rawVersion.substringBefore('-') }

    /** Window title, for example `eyeShell v0.1.1`. */
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
