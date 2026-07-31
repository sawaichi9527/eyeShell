import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "eyeShell"

include(":jediterm-core", ":jediterm-ui")
project(":jediterm-core").projectDir = file("gradle/jediterm/core")
project(":jediterm-ui").projectDir = file("gradle/jediterm/ui")
