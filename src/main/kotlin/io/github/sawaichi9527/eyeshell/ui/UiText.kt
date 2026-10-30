package io.github.sawaichi9527.eyeshell.ui

import io.github.sawaichi9527.eyeshell.platform.I18n

/**
 * Host editor, connection dialog, and monitor wording.
 *
 * Everything resolves through [I18n] at access time, so a dialog built after a language change shows
 * the selected language.
 */
internal object UiText {
    val CLEAR_PENDING: String get() = I18n.t("ui.clearPending")
    val STORE_LOCKED: String get() = I18n.t("ui.storeLocked")
    val STORE_UNAVAILABLE: String get() = I18n.t("ui.storeUnavailable")
    val STORE_WILL_SAVE: String get() = I18n.t("ui.storeWillSave")
    val STORE_KEEP_OR_SESSION: String get() = I18n.t("ui.storeKeepOrSession")
    val STORE_SESSION_ONLY: String get() = I18n.t("ui.storeSessionOnly")
    val CONNECT_SESSION_PASSWORD: String get() = I18n.t("ui.connectSessionPassword")
    val CONNECT_STORE_LOCKED: String get() = I18n.t("ui.connectStoreLocked")
    val ADDRESS_COPY_TOOLTIP: String get() = I18n.t("ui.addressCopyTooltip")
    val CLICK_TO_COPY: String get() = I18n.t("ui.clickToCopy")
    val IDLE_STATUS: String get() = I18n.t("ui.idleStatus")
    val IDLE_HINT: String get() = I18n.t("ui.idleHint")
    val LOAD_TOOLTIP: String get() = I18n.t("ui.loadTooltip")

    /** Non-secret mask shown while an empty password field stands for a saved credential. */
    const val PASSWORD_PLACEHOLDER = "********"

    fun passwordPlaceholder(passwordAuthentication: Boolean, savedPassword: Boolean): String? =
        if (passwordAuthentication && savedPassword) PASSWORD_PLACEHOLDER else null
}
