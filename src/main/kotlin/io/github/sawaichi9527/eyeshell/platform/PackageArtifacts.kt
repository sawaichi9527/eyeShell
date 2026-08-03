package io.github.sawaichi9527.eyeshell.platform

object PackageArtifacts {
    const val APP_NAME = "eyeShell"
    const val LINUX_PACKAGE_NAME = "eyeshell"
    const val MAIN_CLASS = "io.github.sawaichi9527.eyeshell.MainKt"

    fun version(projectVersion: String): String = projectVersion.substringBefore('-')

    fun portableFileName(version: String, windows: Boolean): String =
        "$APP_NAME-$version.${if (windows) "zip" else "tar.gz"}"

    fun installerFileName(version: String, windows: Boolean): String =
        if (windows) "$APP_NAME-$version.msi" else "$LINUX_PACKAGE_NAME" + "_${version}_amd64.deb"
}
