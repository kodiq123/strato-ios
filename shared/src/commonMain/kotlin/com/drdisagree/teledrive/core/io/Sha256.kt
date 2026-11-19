package com.drdisagree.teledrive.core.io

/** Incremental SHA-256: MessageDigest on the JVM, CommonCrypto on iOS. */
expect class Sha256() {
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun digest(): ByteArray
}

/** Lowercase hex, the format "%02x" produced in the original JVM code. */
fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val v = byte.toInt() and 0xFF
        append(HEX[v shr 4])
        append(HEX[v and 0xF])
    }
}

private const val HEX = "0123456789abcdef"

/** Feeds everything read through it into [digest], like java.security.DigestInputStream. */
class DigestingInputStream(
    private val delegate: InputStream,
    private val digest: Sha256
) : InputStream() {
    override fun read(): Int =
        delegate.read().also { if (it >= 0) digest.update(byteArrayOf(it.toByte())) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) digest.update(b, off, it) }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}
