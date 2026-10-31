package io.github.sawaichi9527.eyeshell.platform

object PackageArtifacts {
    const val APP_NAME = "eyeShell"
    const val LINUX_PACKAGE_NAME = "eyeshell"
    const val MAIN_CLASS = "io.github.sawaichi9527.eyeshell.MainKt"

    fun version(projectVersion: String): String = projectVersion.substringBefore('-')

    /**
     * The X11 `WM_CLASS` AWT assigns from the main class name (`MainKt` -> `MainKt`, dots become
     * dashes). Desktop entries must match it through `StartupWMClass`, otherwise GNOME cannot link
     * the window to the application and falls back to a generic icon in the dock and app menu.
     */
    val wmClass: String = MAIN_CLASS.replace('.', '-')

    fun portableFileName(version: String, windows: Boolean): String =
        "$APP_NAME-$version.${if (windows) "zip" else "tar.gz"}"

    fun installerFileName(version: String, windows: Boolean): String =
        if (windows) "$APP_NAME-$version.msi" else "$LINUX_PACKAGE_NAME" + "_${version}_amd64.deb"

    fun appImageFileName(version: String, arch: String = "x86_64"): String =
        "$APP_NAME-$version-$arch.AppImage"
}
