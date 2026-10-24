package io.github.sawaichi9527.eyeshell.ui

/**
 * Host editor credential wording.
 *
 * Kept in one place so the future English / Traditional Chinese / Simplified Chinese resource
 * bundles can replace these constants without touching dialog logic.
 */
internal object UiText {
    const val CLEAR_PENDING = "The saved password will be cleared when you save."
    const val STORE_LOCKED =
        "The OS keyring is locked. Saving will ask you to unlock it; otherwise the password is used for this session only."
    const val STORE_UNAVAILABLE =
        "No OS credential store is available. The password is used for this session only and kept in memory until eyeShell closes."
    const val STORE_WILL_SAVE = "The password will be saved to the OS credential store."
    const val STORE_KEEP_OR_SESSION =
        "Leave blank to keep the saved password. An entered password is used for this session only."
    const val STORE_SESSION_ONLY =
        "The password is used for this session only. Select Remember to save it in the OS credential store."
    const val CONNECT_SESSION_PASSWORD =
        "A password entered for this saved host is used. It is kept in memory until eyeShell closes."
    const val CONNECT_STORE_LOCKED =
        "The OS keyring is locked; saving will ask you to unlock it. Otherwise the password is used for this session only."
    const val ADDRESS_COPY_TOOLTIP = "Copy IP address"
    const val CLICK_TO_COPY = "Click to copy"
    const val IDLE_STATUS = "Not connected"
    const val IDLE_HINT = "Use Connect... or Hosts..."
    const val LOAD_TOOLTIP =
        "Load average for the last 1, 5, and 15 minutes: the average number of processes waiting for CPU time. " +
            "When it stays above the CPU core count, the host cannot keep up."
}
