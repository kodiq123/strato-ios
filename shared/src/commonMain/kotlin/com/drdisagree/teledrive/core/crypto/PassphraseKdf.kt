package com.drdisagree.teledrive.core.crypto

class PassphraseKdf {

    fun newSalt(): ByteArray = secureRandomBytes(SALT_LENGTH)

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray =
        pbkdf2HmacSha256(passphrase, salt, iterations, KEY_BYTES)

    companion object {
        const val ITERATIONS = 310_000
        private const val SALT_LENGTH = 16
        private const val KEY_BYTES = 32
    }
}
