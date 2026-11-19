package com.drdisagree.teledrive.core.io

/** java.io.InputStream on the JVM (typealias), so Android stream implementations plug in as is. */
expect abstract class InputStream() : AutoCloseable {
    abstract fun read(): Int
    open fun read(b: ByteArray): Int
    open fun read(b: ByteArray, off: Int, len: Int): Int
    open fun skip(n: Long): Long
    open fun available(): Int
    override fun close()
}

/** java.io.OutputStream on the JVM (typealias). */
expect abstract class OutputStream() : AutoCloseable {
    abstract fun write(b: Int)
    open fun write(b: ByteArray)
    open fun write(b: ByteArray, off: Int, len: Int)
    open fun flush()
    override fun close()
}

/** java.io.IOException on the JVM. */
expect open class IOException : Exception {
    constructor()
    constructor(message: String?)
    constructor(message: String?, cause: Throwable?)
}


const val DEFAULT_BUFFER_SIZE: Int = 8 * 1024

fun InputStream.copyTo(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return total
}

fun InputStream.readBytes(): ByteArray {
    val out = ByteArrayOutputStream(maxOf(DEFAULT_BUFFER_SIZE, available()))
    copyTo(out)
    return out.toByteArray()
}

/** Reads until [length] bytes arrived or the stream ended; returns how many were read. */
fun InputStream.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
    var total = 0
    while (total < length) {
        val read = read(buffer, offset + total, length - total)
        if (read < 0) break
        total += read
    }
    return total
}

fun ByteArray.inputStream(): InputStream = ByteArrayInputStream(this)

private class ByteArrayInputStream(private val bytes: ByteArray) : InputStream() {
    private var position = 0

    override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(len, bytes.size - position)
        bytes.copyInto(b, off, position, position + count)
        position += count
        return count
    }

    override fun skip(n: Long): Long {
        val count = minOf(n.coerceAtLeast(0), (bytes.size - position).toLong()).toInt()
        position += count
        return count.toLong()
    }

    override fun available(): Int = bytes.size - position

    override fun close() {}
}

/** In-memory sink; a plain class on every target (java.io's is only used through this API). */
open class ByteArrayOutputStream(size: Int = 32) : OutputStream() {
    private var buffer = ByteArray(maxOf(size, 32))
    private var count = 0


    private fun ensure(extra: Int) {
        val needed = count + extra
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
    }

    override fun write(b: Int) {
        ensure(1)
        buffer[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        b.copyInto(buffer, count, off, off + len)
        count += len
    }

    open fun toByteArray(): ByteArray = buffer.copyOf(count)

    open fun size(): Int = count

    open fun reset() {
        count = 0
    }
}
