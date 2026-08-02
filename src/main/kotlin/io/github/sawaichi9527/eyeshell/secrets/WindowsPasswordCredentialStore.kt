package io.github.sawaichi9527.eyeshell.secrets

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.UUID

internal class WindowsPasswordCredentialStore(
    private val api: WindowsCredentialApi = JnaWindowsCredentialApi(),
) : PasswordCredentialStore {
    override fun status(): CredentialStoreStatus = try {
        api.probe()
        CredentialStoreStatus.AVAILABLE
    } catch (_: Exception) {
        CredentialStoreStatus.UNAVAILABLE
    } catch (_: LinkageError) {
        CredentialStoreStatus.UNAVAILABLE
    }

    override fun retrieve(profileId: UUID): StoredPassword? = wrap("read") {
        api.read(target(profileId))?.let { password ->
            try {
                StoredPassword(password)
            } finally {
                Arrays.fill(password, '\u0000')
            }
        }
    }

    override fun save(profileId: UUID, password: CharArray) = wrap("save") {
        require(password.isNotEmpty()) { "Password must not be empty" }
        api.write(target(profileId), password)
    }

    override fun forget(profileId: UUID) = wrap("delete") {
        api.delete(target(profileId))
    }

    private fun target(profileId: UUID): String = "eyeShell/ssh/$profileId"

    private inline fun <T> wrap(operation: String, action: () -> T): T = try {
        action()
    } catch (failure: CredentialStoreException) {
        throw failure
    } catch (failure: Exception) {
        throw CredentialStoreException("Could not $operation Windows credential", failure)
    } catch (failure: LinkageError) {
        throw CredentialStoreException("Could not $operation Windows credential", failure)
    }
}

internal interface WindowsCredentialApi {
    fun probe()
    fun read(target: String): CharArray?
    fun write(target: String, password: CharArray)
    fun delete(target: String)
}

private class JnaWindowsCredentialApi : WindowsCredentialApi {
    override fun probe() {
        read("eyeShell/probe/availability")?.fill('\u0000')
    }

    override fun read(target: String): CharArray? {
        val result = PointerByReference()
        if (!AdvapiCredentials.INSTANCE.CredRead(target, CREDENTIAL_TYPE_GENERIC, 0, result)) {
            if (Native.getLastError() == ERROR_NOT_FOUND) return null
            throw IllegalStateException("CredReadW failed with error ${Native.getLastError()}")
        }
        val pointer = result.value
        try {
            val credential = Credential(pointer).apply { read() }
            val bytes = credential.credentialBlob?.getByteArray(0, credential.credentialBlobSize) ?: return null
            try {
                val chars = CharArray(bytes.size / Character.BYTES)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asCharBuffer().get(chars)
                return chars
            } finally {
                Arrays.fill(bytes, 0)
            }
        } finally {
            AdvapiCredentials.INSTANCE.CredFree(pointer)
        }
    }

    override fun write(target: String, password: CharArray) {
        val blob = Memory(password.size.toLong() * Character.BYTES)
        try {
            password.forEachIndexed { index, value -> blob.setShort(index.toLong() * Character.BYTES, value.code.toShort()) }
            val credential = Credential().apply {
                type = CREDENTIAL_TYPE_GENERIC
                targetName = WString(target)
                credentialBlobSize = blob.size().toInt()
                credentialBlob = blob
                persist = CREDENTIAL_PERSIST_LOCAL_MACHINE
                userName = WString("eyeShell")
                write()
            }
            if (!AdvapiCredentials.INSTANCE.CredWrite(credential, 0)) {
                throw IllegalStateException("CredWriteW failed with error ${Native.getLastError()}")
            }
        } finally {
            blob.clear()
        }
    }

    override fun delete(target: String) {
        if (!AdvapiCredentials.INSTANCE.CredDelete(target, CREDENTIAL_TYPE_GENERIC, 0) &&
            Native.getLastError() != ERROR_NOT_FOUND
        ) {
            throw IllegalStateException("CredDeleteW failed with error ${Native.getLastError()}")
        }
    }

    private interface AdvapiCredentials : StdCallLibrary {
        fun CredRead(targetName: String, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredWrite(credential: Credential, flags: Int): Boolean
        fun CredDelete(targetName: String, type: Int, flags: Int): Boolean
        fun CredFree(buffer: Pointer)

        companion object {
            val INSTANCE: AdvapiCredentials = Native.load("Advapi32", AdvapiCredentials::class.java, W32APIOptions.UNICODE_OPTIONS)
        }
    }

    @Structure.FieldOrder(
        "flags", "type", "targetName", "comment", "lastWritten", "credentialBlobSize", "credentialBlob",
        "persist", "attributeCount", "attributes", "targetAlias", "userName",
    )
    class Credential(pointer: Pointer? = null) : Structure(pointer) {
        @JvmField var flags: Int = 0
        @JvmField var type: Int = 0
        @JvmField var targetName: WString? = null
        @JvmField var comment: WString? = null
        @JvmField var lastWritten: FileTime = FileTime()
        @JvmField var credentialBlobSize: Int = 0
        @JvmField var credentialBlob: Pointer? = null
        @JvmField var persist: Int = 0
        @JvmField var attributeCount: Int = 0
        @JvmField var attributes: Pointer? = null
        @JvmField var targetAlias: WString? = null
        @JvmField var userName: WString? = null
    }

    @Structure.FieldOrder("lowDateTime", "highDateTime")
    class FileTime : Structure() {
        @JvmField var lowDateTime: Int = 0
        @JvmField var highDateTime: Int = 0
    }

    companion object {
        private const val CREDENTIAL_TYPE_GENERIC = 1
        private const val CREDENTIAL_PERSIST_LOCAL_MACHINE = 2
        private const val ERROR_NOT_FOUND = 1168
    }
}
