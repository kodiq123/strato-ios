package com.drdisagree.teledrive.core.crypto

import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * Layout: MAGIC(4) VERSION(1) SALT(8), then length(4, top bit marks the last) and sealed bytes per
 * chunk; index and last flag are bound as AAD, so chunks cannot be dropped or reordered.
 *
 * All integers are big-endian. The format is shared by every platform, so a drive encrypted on one
 * device decrypts on any other.
 */
class StreamCrypto {

    fun encryptStream(key: ByteArray, input: Source, output: Sink) {
        val salt = secureRandomBytes(SALT_LENGTH)
        output.write(MAGIC)
        output.writeByte(VERSION.toByte())
        output.write(salt)

        var current = ByteArray(CHUNK_SIZE)
        var next = ByteArray(CHUNK_SIZE)
        var currentSize = readFully(input, current)
        var chunkIndex = 0
        while (true) {
            val nextSize = if (currentSize < CHUNK_SIZE) 0 else readFully(input, next)
            val isFinal = nextSize <= 0
            writeChunk(key, salt, chunkIndex, current, currentSize, isFinal, output)
            if (isFinal) break
            val swap = current
            current = next
            next = swap
            currentSize = nextSize
            chunkIndex++
        }
        output.flush()
    }

    private fun writeChunk(
        key: ByteArray,
        salt: ByteArray,
        chunkIndex: Int,
        chunk: ByteArray,
        size: Int,
        isFinal: Boolean,
        output: Sink
    ) {
        val sealed = AesGcm.seal(
            key,
            nonceFor(salt, chunkIndex),
            aadFor(chunkIndex, isFinal),
            chunk,
            0,
            size.coerceAtLeast(0)
        )
        val header = if (isFinal) sealed.size or FINAL_FLAG else sealed.size
        output.writeInt(header)
        output.write(sealed)
    }

    fun decryptStream(key: ByteArray, input: Source, output: Sink) {
        val header = ByteArray(MAGIC.size + 1 + SALT_LENGTH)
        if (readFully(input, header) != header.size) throw EOFException("Truncated header")
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) throw IllegalArgumentException("Not an encrypted file")
        }
        if (header[MAGIC.size] != VERSION.toByte()) {
            throw IllegalArgumentException("Unsupported encryption version")
        }
        val salt = header.copyOfRange(MAGIC.size + 1, header.size)

        val lengthBytes = ByteArray(LENGTH_BYTES)
        var chunkIndex = 0
        var sawFinalChunk = false
        while (!sawFinalChunk) {
            val lengthRead = readFully(input, lengthBytes)
            if (lengthRead == 0) throw EOFException("Encrypted stream is truncated")
            if (lengthRead != LENGTH_BYTES) throw EOFException("Truncated chunk length")
            val marker = readInt(lengthBytes, 0)
            val isFinal = marker and FINAL_FLAG != 0
            val sealedLength = marker and FINAL_FLAG.inv()
            if (sealedLength < TAG_BYTES || sealedLength > CHUNK_SIZE + TAG_BYTES) {
                throw IllegalArgumentException("Invalid chunk length")
            }
            val sealed = ByteArray(sealedLength)
            if (readFully(input, sealed) != sealedLength) throw EOFException("Truncated chunk")

            output.write(
                AesGcm.open(key, nonceFor(salt, chunkIndex), aadFor(chunkIndex, isFinal), sealed)
            )
            sawFinalChunk = isFinal
            chunkIndex++
        }
        if (!input.exhausted()) throw IllegalArgumentException("Data after the final chunk")
        output.flush()
    }

    /**
     * Fixed-size frames let a seek locate the sealed bytes for any plaintext offset by arithmetic.
     */
    fun headerSize(): Int = MAGIC.size + 1 + SALT_LENGTH

    fun frameIndexOf(plainOffset: Long): Int = (plainOffset / CHUNK_SIZE).toInt()

    fun frameStart(frameIndex: Int): Long =
        headerSize() + frameIndex.toLong() * (LENGTH_BYTES + CHUNK_SIZE + TAG_BYTES)

    fun frameStoredSize(plainLength: Int): Int = LENGTH_BYTES + plainLength + TAG_BYTES

    fun storedSize(plainSize: Long): Long {
        if (plainSize <= 0) return headerSize().toLong() + frameStoredSize(0)
        val whole = plainSize / CHUNK_SIZE
        val remainder = (plainSize % CHUNK_SIZE).toInt()
        var total = headerSize().toLong() + whole * frameStoredSize(CHUNK_SIZE)
        if (remainder > 0) total += frameStoredSize(remainder)
        return total
    }

    fun saltOf(header: ByteArray): ByteArray {
        require(header.size >= headerSize()) { "Header too short" }
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) throw IllegalArgumentException("Not an encrypted file")
        }
        if (header[MAGIC.size] != VERSION.toByte()) {
            throw IllegalArgumentException("Unsupported encryption version")
        }
        return header.copyOfRange(MAGIC.size + 1, headerSize())
    }

    fun decryptFrame(
        key: ByteArray,
        salt: ByteArray,
        frameIndex: Int,
        frame: ByteArray
    ): ByteArray {
        require(frame.size > LENGTH_BYTES) { "Frame too short" }
        val marker = readInt(frame, 0)
        val isFinal = marker and FINAL_FLAG != 0
        val sealedLength = marker and FINAL_FLAG.inv()
        if (sealedLength < TAG_BYTES || sealedLength > CHUNK_SIZE + TAG_BYTES) {
            throw IllegalArgumentException("Invalid frame length")
        }
        if (frame.size < LENGTH_BYTES + sealedLength) throw EOFException("Truncated frame")

        return AesGcm.open(
            key,
            nonceFor(salt, frameIndex),
            aadFor(frameIndex, isFinal),
            frame,
            LENGTH_BYTES,
            sealedLength
        )
    }

    fun encryptBytes(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = secureRandomBytes(NONCE_LENGTH)
        return nonce + AesGcm.seal(key, nonce, null, plaintext)
    }

    fun decryptBytes(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_LENGTH + TAG_BYTES) { "Blob too short" }
        return AesGcm.open(
            key,
            blob.copyOfRange(0, NONCE_LENGTH),
            null,
            blob,
            NONCE_LENGTH,
            blob.size - NONCE_LENGTH
        )
    }

    /** salt(8) || chunkIndex(4, big-endian). */
    private fun nonceFor(salt: ByteArray, chunkIndex: Int): ByteArray =
        Buffer().apply {
            write(salt)
            writeInt(chunkIndex)
        }.readByteArray()

    /** version(4) || chunkIndex(4) || isFinal(1), all big-endian. */
    private fun aadFor(chunkIndex: Int, isFinal: Boolean): ByteArray =
        Buffer().apply {
            writeInt(VERSION)
            writeInt(chunkIndex)
            writeByte(if (isFinal) 1 else 0)
        }.readByteArray()

    private fun readFully(input: Source, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.readAtMostTo(buffer, offset, buffer.size)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

    companion object {
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x45, 0x31) // "TDE1"
        private const val VERSION = 1
        private const val FINAL_FLAG = 1 shl 31
        const val CHUNK_SIZE = 1024 * 1024
        private const val LENGTH_BYTES = 4
        private const val SALT_LENGTH = 8
        private const val NONCE_LENGTH = 12
        private const val TAG_BYTES = 16
    }
}
