package com.drdisagree.teledrive.core.crypto

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Layout: MAGIC(4) VERSION(1) iterations(4) salt(16) hintLength(2) hint(utf-8), then the AES-GCM
 * blob. All integers big-endian. The hint is plaintext, so the UI must warn against putting the
 * passphrase in it.
 */
class KeyBackupCodec(
    private val passphraseKdf: PassphraseKdf,
    private val streamCrypto: StreamCrypto
) {

    fun encode(contentKey: ByteArray, passphrase: CharArray, hint: String?): ByteArray {
        val salt = passphraseKdf.newSalt()
        val kek = passphraseKdf.deriveKey(passphrase, salt)
        val sealed = streamCrypto.encryptBytes(kek, contentKey)
        val hintBytes = hint.orEmpty().take(MAX_HINT_LENGTH).encodeToByteArray()
        return Buffer().apply {
            write(MAGIC)
            writeByte(VERSION)
            writeInt(PassphraseKdf.ITERATIONS)
            write(salt)
            writeShort(hintBytes.size.toShort())
            write(hintBytes)
            write(sealed)
        }.readByteArray()
    }

    fun readInfo(blob: ByteArray): KeyBackupInfo? = runCatching {
        val buffer = header(blob)
        val hintLength = buffer.readShort().toInt()
        val hintBytes = buffer.readByteArray(hintLength)
        KeyBackupInfo(hint = hintBytes.decodeToString().takeIf { it.isNotEmpty() })
    }.getOrNull()

    /** Returns null when the passphrase is wrong or the blob is corrupt. */
    fun decode(blob: ByteArray, passphrase: CharArray): ByteArray? = runCatching {
        val buffer = Buffer().apply { write(blob) }
        val magic = buffer.readByteArray(MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "Not a key backup" }
        require(buffer.readByte() == VERSION) { "Unsupported version" }
        val iterations = buffer.readInt()
        require(iterations in 10_000..10_000_000) { "Implausible iteration count" }
        val salt = buffer.readByteArray(SALT_LENGTH)
        val hintLength = buffer.readShort().toInt()
        require(hintLength in 0..MAX_HINT_LENGTH * 4) { "Invalid hint length" }
        buffer.skip(hintLength.toLong())
        val sealed = buffer.readByteArray()
        val kek = passphraseKdf.deriveKey(passphrase, salt, iterations)
        streamCrypto.decryptBytes(kek, sealed)
    }.getOrNull()

    private fun header(blob: ByteArray): Buffer {
        val buffer = Buffer().apply { write(blob) }
        val magic = buffer.readByteArray(MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "Not a key backup" }
        require(buffer.readByte() == VERSION) { "Unsupported version" }
        buffer.readInt()
        buffer.skip(SALT_LENGTH.toLong())
        return buffer
    }

    companion object {
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x4B, 0x42) // "TDKB"
        private const val VERSION: Byte = 1
        private const val SALT_LENGTH = 16
        private const val MAX_HINT_LENGTH = 128
        const val BACKUP_FILE_NAME = "teledrive.keybackup"
    }
}
