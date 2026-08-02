package io.github.sawaichi9527.eyeshell.secrets

object SystemPasswordCredentialStore {
    fun create(osName: String = System.getProperty("os.name")): PasswordCredentialStore = when {
        osName.startsWith("Windows", ignoreCase = true) -> WindowsPasswordCredentialStore()
        osName.startsWith("Linux", ignoreCase = true) -> LinuxSecretServicePasswordStore()
        else -> UnavailablePasswordCredentialStore()
    }
}
