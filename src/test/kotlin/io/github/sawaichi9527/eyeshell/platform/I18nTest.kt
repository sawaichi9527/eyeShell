package io.github.sawaichi9527.eyeshell.platform

import java.util.Locale
import java.util.ResourceBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I18nTest {
    @Test
    fun `every locale defines exactly the same keys as the base bundle`() {
        val baseKeys = bundle(Locale.ENGLISH).keySet()

        I18n.SUPPORTED.forEach { locale ->
            val translated = bundle(locale)
            assertEquals(baseKeys, translated.keySet(), "Key mismatch for $locale")
            translated.keySet().forEach { key ->
                assertTrue(translated.getString(key).isNotBlank(), "Blank translation for $key in $locale")
            }
        }
    }

    @Test
    fun `the english bundle contains no CJK characters`() {
        val base = bundle(Locale.ENGLISH)
        base.keySet().forEach { key ->
            val value = base.getString(key)
            assertTrue(
                value.none { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF },
                "English value for $key must not contain CJK text: $value",
            )
        }
    }

    @Test
    fun `placeholders are formatted with the active locale`() {
        try {
            I18n.setLocaleId("zh-TW")
            assertEquals("設定", I18n.t("settings.title"))
            assertEquals("已連線至 mobius", I18n.t("sessionStatus.line.connected", "mobius"))

            I18n.setLocaleId("ja")
            assertEquals("設定", I18n.t("settings.title"))
        } finally {
            I18n.setLocaleId("en")
        }
    }

    @Test
    fun `unknown ids and unsupported system locales fall back to english`() {
        try {
            I18n.setLocaleId("de-DE")
            assertEquals("en", I18n.localeId())
            assertEquals("Settings", I18n.t("settings.title"))
        } finally {
            I18n.setLocaleId("en")
        }
    }

    @Test
    fun `chinese locales normalize by region and script`() {
        assertEquals("zh-TW", I18n.idFor(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-TW", I18n.idFor(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-TW", I18n.idFor(Locale.forLanguageTag("zh-Hant")))
        assertEquals("zh-CN", I18n.idFor(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-CN", I18n.idFor(Locale.forLanguageTag("zh-SG")))
        assertEquals("zh-CN", I18n.idFor(Locale.forLanguageTag("zh-Hans")))
        assertEquals("ja", I18n.idFor(Locale.JAPANESE))
        assertEquals("en", I18n.idFor(Locale.GERMAN))
    }

    @Test
    fun `a missing key is returned verbatim instead of throwing`() {
        try {
            I18n.setLocaleId("en")
            assertEquals("no.such.key", I18n.t("no.such.key"))
        } finally {
            I18n.setLocaleId("en")
        }
    }

    private fun bundle(locale: Locale): ResourceBundle = ResourceBundle.getBundle(
        "i18n.messages",
        locale,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )
}
