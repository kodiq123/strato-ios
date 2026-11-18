package com.drdisagree.teledrive.core.crypto

import com.drdisagree.teledrive.core.files.IosFiles.toByteArray
import com.drdisagree.teledrive.core.telegram.TdlibDatabaseKeyProvider
import com.drdisagree.teledrive.core.telegram.TelegramCredentials

/**
 * Small secret storage implemented by the iOS host over the Keychain (this-device-only, available
 * after first unlock). Values never leave the device and are not included in backups.
 */
interface SecureStore {
    fun read(key: String): platform.Foundation.NSData?
    fun write(key: String, value: platform.Foundation.NSData): Boolean
    fun remove(key: String)
}

/** TDLib's database key: random, minted once per install, held in the Keychain. */
class KeychainTdlibDatabaseKeyProvider(private val store: SecureStore) : TdlibDatabaseKeyProvider {

    override fun databaseKey(): ByteArray {
        store.read(KEY)?.toByteArray()?.takeIf { it.size == KEY_BYTES }?.let { return it }
        val fresh = secureRandomBytes(KEY_BYTES)
        check(store.write(KEY, fresh.toNSData())) { "Could not store the TDLib database key" }
        return fresh
    }

    private companion object {
        const val KEY = "tdlib_db_key"
        const val KEY_BYTES = 32
    }
}

/** The user's own Telegram API id and hash, kept in the Keychain rather than preferences. */
class KeychainCredentialStore(private val store: SecureStore) {

    fun load(): TelegramCredentials? {
        val raw = store.read(KEY)?.toByteArray()?.decodeToString() ?: return null
        val apiId = raw.substringBefore(':').toIntOrNull() ?: return null
        val apiHash = raw.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
        return TelegramCredentials(apiId, apiHash)
    }

    fun save(credentials: TelegramCredentials) {
        store.write(KEY, "${credentials.apiId}:${credentials.apiHash}".encodeToByteArray().toNSData())
    }

    fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "telegram_credentials"
    }
}
