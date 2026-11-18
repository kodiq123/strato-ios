package com.drdisagree.teledrive.core.crypto

import com.drdisagree.teledrive.core.files.IosFiles.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * AES-GCM is only public through CryptoKit, a Swift-only framework, so the iOS host registers a
 * CryptoKit-backed provider at launch. Ciphertext layout matches javax.crypto: ciphertext || tag.
 */
interface AesGcmProvider {

    /** Returns ciphertext || 16-byte tag, or null if CryptoKit rejects the inputs. */
    fun seal(key: NSData, nonce: NSData, aad: NSData?, plaintext: NSData): NSData?

    /** Returns the plaintext, or null when authentication fails. */
    fun open(key: NSData, nonce: NSData, aad: NSData?, sealed: NSData): NSData?
}

/** Set by the iOS host before any Kotlin code encrypts or decrypts. */
object IosCryptoBridge {
    var aesGcm: AesGcmProvider? = null
}

actual object AesGcm {

    actual fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        input: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray = provider().seal(
        key.toNSData(),
        nonce.toNSData(),
        aad?.toNSData(),
        input.toNSData(offset, length)
    )?.toByteArray() ?: throw IllegalArgumentException("AES-GCM seal rejected its inputs")

    actual fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        input: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray = provider().open(
        key.toNSData(),
        nonce.toNSData(),
        aad?.toNSData(),
        input.toNSData(offset, length)
    )?.toByteArray() ?: throw AuthenticationFailedException("AES-GCM authentication failed")

    private fun provider(): AesGcmProvider = IosCryptoBridge.aesGcm
        ?: error("IosCryptoBridge.aesGcm was not registered by the iOS host")
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
actual fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int
): ByteArray {
    val passwordBytes = password.concatToString().encodeToByteArray()
    val derived = ByteArray(keyLengthBytes)
    // usePinned cannot address an empty array, so empty inputs get a one-byte stand-in with length 0.
    val passwordBuffer = if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes
    val saltBuffer = if (salt.isEmpty()) ByteArray(1) else salt
    try {
        val status = passwordBuffer.usePinned { pw ->
            saltBuffer.usePinned { s ->
                derived.usePinned { out ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2,
                        pw.addressOf(0),
                        passwordBytes.size.convert(),
                        s.addressOf(0).reinterpret(),
                        salt.size.convert(),
                        kCCPRFHmacAlgSHA256,
                        iterations.convert(),
                        out.addressOf(0).reinterpret(),
                        keyLengthBytes.convert()
                    )
                }
            }
        }
        check(status == kCCSuccess) { "CCKeyDerivationPBKDF failed: $status" }
        return derived
    } finally {
        passwordBytes.fill(0)
        passwordBuffer.fill(0)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(offset: Int = 0, length: Int = size): NSData {
    if (length == 0) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(offset), length = length.convert())
    }
}
