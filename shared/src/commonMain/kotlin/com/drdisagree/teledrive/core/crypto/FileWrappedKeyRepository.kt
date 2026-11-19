package com.drdisagree.teledrive.core.crypto

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.common.Lock
import com.drdisagree.teledrive.core.common.withLock
import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.parentFile
import com.drdisagree.teledrive.core.io.readBytes
import com.drdisagree.teledrive.core.io.writeBytes

class FileWrappedKeyRepository(
    private val storagePaths: AppStoragePaths,
    private val cipher: CredentialCipher
) : WrappedKeyRepository {

    private val cache = mutableMapOf<String, ByteArray>()
    private val recreated = mutableSetOf<String>()
    private val lock = Lock()

    /**
     * The Keystore cannot unwrap keys of a restored copy; disposable keys are minted again, the
     * content key never is.
     */
    override fun getOrCreate(name: String, sizeBytes: Int): ByteArray = lock.withLock { getOrCreateLocked(name, sizeBytes) }

    private fun getOrCreateLocked(name: String, sizeBytes: Int): ByteArray {
        cache[name]?.let { return it }
        val file = keyFile(name)
        if (file.exists()) {
            val stored = runCatching { cipher.decrypt(file.readBytes()) }.getOrNull()
            if (stored != null) {
                cache[name] = stored
                return stored
            }
            SafeLog.w(TAG, "Wrapped key $name cannot be unwrapped on this device")
            if (name !in RECREATABLE) throw KeyUnavailableException(name)
            file.delete()
            recreated += name
        }
        val fresh = secureRandomBytes(sizeBytes)
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(fresh))
        cache[name] = fresh
        return fresh
    }

    /** True when [name] had to be replaced, so whatever it guarded is now junk. */
    override fun wasRecreated(name: String): Boolean = lock.withLock { name in recreated }

    /**
     * Never creates a key: minting one on a decryption path would make existing data unreadable.
     */
    override fun get(name: String): ByteArray? = lock.withLock { getLocked(name) }

    private fun getLocked(name: String): ByteArray? {
        cache[name]?.let { return it }
        val file = keyFile(name)
        if (!file.exists()) return null
        return runCatching { cipher.decrypt(file.readBytes()) }
            .onFailure { SafeLog.w(TAG, "Wrapped key $name cannot be unwrapped") }
            .getOrNull()
            ?.also { cache[name] = it }
    }

    override fun exists(name: String): Boolean = lock.withLock { get(name) != null }

    override fun store(name: String, key: ByteArray) = lock.withLock {
        val file = keyFile(name)
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.encrypt(key))
        cache[name] = key
    }

    private fun keyFile(name: String): File =
        File(File(storagePaths.filesDir, "keys"), "$name.bin")

    private companion object {
        const val TAG = "WrappedKeyRepository"
        val RECREATABLE = setOf(
            CryptoKeys.TDLIB_DATABASE,
            CryptoKeys.THUMBNAIL,
            CryptoKeys.CACHE
        )
    }
}
