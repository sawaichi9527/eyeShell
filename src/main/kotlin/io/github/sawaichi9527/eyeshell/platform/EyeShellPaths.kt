package io.github.sawaichi9527.eyeshell.platform

import java.nio.file.Path

object EyeShellPaths {
    fun knownHostsFile(): Path = resolveKnownHostsFile(
        osName = System.getProperty("os.name"),
        userHome = System.getProperty("user.home"),
        appData = System.getenv("APPDATA"),
        xdgConfigHome = System.getenv("XDG_CONFIG_HOME"),
    )

    fun catalogDatabaseFile(): Path = resolveCatalogDatabaseFile(
        osName = System.getProperty("os.name"),
        userHome = System.getProperty("user.home"),
        localAppData = System.getenv("LOCALAPPDATA"),
        xdgDataHome = System.getenv("XDG_DATA_HOME"),
    )

    fun settingsFile(): Path = resolveSettingsFile(
        osName = System.getProperty("os.name"),
        userHome = System.getProperty("user.home"),
        appData = System.getenv("APPDATA"),
        xdgConfigHome = System.getenv("XDG_CONFIG_HOME"),
    )

    /** Directory holding imported terminal background images (non-secret preferences). */
    fun backgroundDirectory(): Path = configDirectory(
        osName = System.getProperty("os.name"),
        userHome = System.getProperty("user.home"),
        appData = System.getenv("APPDATA"),
        xdgConfigHome = System.getenv("XDG_CONFIG_HOME"),
    ).resolve("backgrounds")

    internal fun resolveSettingsFile(
        osName: String,
        userHome: String,
        appData: String?,
        xdgConfigHome: String?,
    ): Path = configDirectory(osName, userHome, appData, xdgConfigHome).resolve("settings.properties")

    internal fun resolveKnownHostsFile(
        osName: String,
        userHome: String,
        appData: String?,
        xdgConfigHome: String?,
    ): Path = configDirectory(osName, userHome, appData, xdgConfigHome).resolve("known_hosts")

    private fun configDirectory(
        osName: String,
        userHome: String,
        appData: String?,
        xdgConfigHome: String?,
    ): Path {
        val baseDirectory = if (osName.startsWith("Windows", ignoreCase = true)) {
            appData?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(userHome, "AppData", "Roaming")
        } else {
            xdgConfigHome?.takeIf(String::isNotBlank)?.let(Path::of)?.takeIf(Path::isAbsolute)
                ?: Path.of(userHome, ".config")
        }
        return baseDirectory.resolve(APP_DIRECTORY)
    }

    internal fun resolveCatalogDatabaseFile(
        osName: String,
        userHome: String,
        localAppData: String?,
        xdgDataHome: String?,
    ): Path {
        val baseDirectory = if (osName.startsWith("Windows", ignoreCase = true)) {
            localAppData?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(userHome, "AppData", "Local")
        } else {
            xdgDataHome?.takeIf(String::isNotBlank)?.let(Path::of)?.takeIf(Path::isAbsolute)
                ?: Path.of(userHome, ".local", "share")
        }
        return baseDirectory.resolve(APP_DIRECTORY).resolve("eyeshell.db")
    }

    private const val APP_DIRECTORY = "eyeShell"
}
