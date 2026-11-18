package com.drdisagree.teledrive.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object AesGcm {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    actual fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        input: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray = cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(input, offset, length)

    actual fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        input: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray = cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(input, offset, length)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (aad != null) updateAAD(aad)
        }
}

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int
): ByteArray {
    val spec = PBEKeySpec(password, salt, iterations, keyLengthBytes * 8)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}
