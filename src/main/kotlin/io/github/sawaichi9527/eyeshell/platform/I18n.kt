package io.github.sawaichi9527.eyeshell.platform

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Application-wide message lookup backed by `ResourceBundle`.
 *
 * Each supported language is a UTF-8 `.properties` file under `i18n/` (`messages.properties` is the
 * English base and the fallback). Adding a language means copying the base file, translating the
 * values, naming it `messages_<language>.properties`, and adding the id to [SUPPORTED_IDS] together
 * with the matching `settings.language.*` key.
 *
 * Unknown ids and unsupported system locales fall back to English; `zh` without a region defaults to
 * Traditional Chinese.
 */
object I18n {
    /** Stored value meaning "follow the operating system locale". */
    const val SYSTEM_ID = "system"

    val ZH_TW: Locale = Locale.forLanguageTag("zh-TW")
    val ZH_CN: Locale = Locale.forLanguageTag("zh-CN")

    /** Locales that ship a complete bundle. */
    val SUPPORTED: List<Locale> = listOf(Locale.ENGLISH, ZH_TW, ZH_CN, Locale.JAPANESE)

    /** Stable ids used by `settings.properties` and the language combo box, system first. */
    val SUPPORTED_IDS: List<String> = listOf(SYSTEM_ID, "en", "zh-TW", "zh-CN", "ja")

    /**
     * The default control falls back to the JVM default locale (zh_TW on this project's host) before
     * the base bundle, which would make "en" resolve to Chinese. [CONTROL] disables that fallback;
     * the base bundle stays the parent, so keys missing from a translation still fall back to English.
     */
    private val CONTROL: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    @Volatile
    private var current: Locale = Locale.ENGLISH

    @Volatile
    private var bundle: ResourceBundle = loadBundle(Locale.ENGLISH)

    fun setLocaleId(id: String?) {
        setLocale(resolve(id))
    }

    fun setLocale(locale: Locale) {
        current = normalize(locale)
        bundle = loadBundle(current)
    }

    fun locale(): Locale = current

    /** Id of the active locale, always one of `en`, `zh-TW`, `zh-CN`, `ja`. */
    fun localeId(): String = idFor(current)

    fun idFor(locale: Locale): String = when (normalize(locale)) {
        ZH_TW -> "zh-TW"
        ZH_CN -> "zh-CN"
        Locale.JAPANESE -> "ja"
        else -> "en"
    }

    /** Resolves a stored id (`system`, `en`, `zh-TW`, `zh-CN`, `ja`) to a supported locale. */
    fun resolve(id: String?): Locale {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == SYSTEM_ID) return normalize(Locale.getDefault())
        val parsed = runCatching { Locale.forLanguageTag(trimmed) }.getOrNull() ?: return Locale.ENGLISH
        return normalize(parsed)
    }

    /** Maps an arbitrary locale to the closest shipped bundle. */
    fun normalize(locale: Locale): Locale = when {
        locale.language.equals("en", ignoreCase = true) -> Locale.ENGLISH
        locale.language.equals("ja", ignoreCase = true) -> Locale.JAPANESE
        locale.language.equals("zh", ignoreCase = true) -> chinese(locale)
        else -> Locale.ENGLISH
    }

    fun t(key: String, vararg args: Any): String {
        val pattern = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            key
        }
        if (args.isEmpty()) return pattern
        return MessageFormat(pattern, current).format(args)
    }

    fun contains(key: String): Boolean = bundle.containsKey(key)

    private fun chinese(locale: Locale): Locale {
        val script = locale.script
        val country = locale.country.uppercase(Locale.ROOT)
        return when {
            script.equals("Hant", ignoreCase = true) -> ZH_TW
            script.equals("Hans", ignoreCase = true) -> ZH_CN
            country in setOf("TW", "HK", "MO") -> ZH_TW
            country in setOf("CN", "SG", "MY") -> ZH_CN
            else -> ZH_TW
        }
    }

    private fun loadBundle(locale: Locale): ResourceBundle =
        try {
            ResourceBundle.getBundle("i18n.messages", locale, CONTROL)
        } catch (_: MissingResourceException) {
            ResourceBundle.getBundle("i18n.messages", Locale.ROOT, CONTROL)
        }
}
