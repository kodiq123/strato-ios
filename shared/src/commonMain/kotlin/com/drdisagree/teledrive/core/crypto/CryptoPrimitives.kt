package com.drdisagree.teledrive.core.crypto

/**
 * The only platform pieces the encryption formats need. Everything above them (frame layout,
 * nonces, AAD, key backup encoding) is common code, so every platform writes identical bytes.
 */
expect object AesGcm {

    /** AES-GCM with a 128-bit tag over input[offset, offset+length); returns ciphertext || tag. */
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size
    ): ByteArray

    /** Inverse of [seal]; throws when the tag does not verify. */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray?,
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size
    ): ByteArray
}

expect fun secureRandomBytes(size: Int): ByteArray

/** PBKDF2-HMAC-SHA256 over the UTF-8 bytes of [password], as PBKDF2WithHmacSHA256 does. */
expect fun pbkdf2HmacSha256(
    password: CharArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBytes: Int
): ByteArray

class AuthenticationFailedException(message: String) : Exception(message)
