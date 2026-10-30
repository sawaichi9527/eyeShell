package io.github.sawaichi9527.eyeshell.platform

import java.util.Properties

/**
 * One entry of the About page's open-source list.
 *
 * The data is generated at build time (`generateAboutManifest`) from the resolved runtime classpath,
 * so the versions on screen cannot drift from the jars that are packaged.
 */
data class OpenSourceComponent(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val scope: Scope,
) {
    enum class Scope { RUNTIME, TEST, ASSET }
}

object AboutComponents {
    fun load(): List<OpenSourceComponent> {
        val properties = Properties()
        val stream = AboutComponents::class.java.getResourceAsStream("/about/components.properties")
            ?: return emptyList()
        stream.use(properties::load)

        val components = mutableListOf<OpenSourceComponent>()
        var index = 1
        while (true) {
            val name = properties.getProperty("component.$index.name") ?: break
            components += OpenSourceComponent(
                name = name,
                version = properties.getProperty("component.$index.version").orEmpty(),
                license = properties.getProperty("component.$index.license").orEmpty(),
                url = properties.getProperty("component.$index.url").orEmpty(),
                scope = when (properties.getProperty("component.$index.scope")) {
                    "test" -> OpenSourceComponent.Scope.TEST
                    "asset" -> OpenSourceComponent.Scope.ASSET
                    else -> OpenSourceComponent.Scope.RUNTIME
                },
            )
            index += 1
        }
        return components
    }

    /** Bundled copy of `THIRD_PARTY_NOTICES.md`, or an empty string when it is missing. */
    fun thirdPartyNotices(): String =
        AboutComponents::class.java.getResourceAsStream("/about/THIRD_PARTY_NOTICES.md")
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
            .orEmpty()
}
