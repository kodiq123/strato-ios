@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.drdisagree.teledrive.core.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.inflateReset
import platform.zlib.z_stream
import platform.zlib.zlibVersion

actual fun InputStream.buffered(bufferSize: Int): InputStream = BufferedInput(this, bufferSize)

actual fun OutputStream.buffered(bufferSize: Int): OutputStream = BufferedOutput(this, bufferSize)

actual fun gzipOutputStream(target: OutputStream): OutputStream = GzipOutput(target)

actual fun gzipInputStream(source: InputStream): InputStream = GzipInput(source)

private class BufferedInput(private val delegate: InputStream, size: Int) : InputStream() {
    private val buffer = ByteArray(size)
    private var position = 0
    private var limit = 0

    private fun fill(): Boolean {
        if (position < limit) return true
        val read = delegate.read(buffer, 0, buffer.size)
        if (read <= 0) return false
        position = 0
        limit = read
        return true
    }

    override fun read(): Int = if (fill()) buffer[position++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        // Large reads bypass the buffer, like BufferedInputStream.
        if (position >= limit && len >= buffer.size) return delegate.read(b, off, len)
        if (!fill()) return -1
        val count = minOf(len, limit - position)
        buffer.copyInto(b, off, position, position + count)
        position += count
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffered = (limit - position).toLong()
        if (buffered > 0) {
            val count = minOf(n, buffered)
            position += count.toInt()
            return count
        }
        return delegate.skip(n)
    }

    override fun available(): Int = (limit - position) + delegate.available()

    override fun close() = delegate.close()
}

private class BufferedOutput(private val delegate: OutputStream, size: Int) : OutputStream() {
    private val buffer = ByteArray(size)
    private var count = 0

    private fun drain() {
        if (count > 0) {
            delegate.write(buffer, 0, count)
            count = 0
        }
    }

    override fun write(b: Int) {
        if (count == buffer.size) drain()
        buffer[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len >= buffer.size) {
            drain()
            delegate.write(b, off, len)
            return
        }
        if (len > buffer.size - count) drain()
        b.copyInto(buffer, count, off, off + len)
        count += len
    }

    override fun flush() {
        drain()
        delegate.flush()
    }

    override fun close() {
        try {
            flush()
        } finally {
            delegate.close()
        }
    }
}

/** windowBits 15 + 16 selects the gzip wrapper in zlib. */
private const val GZIP_WINDOW_BITS = MAX_WBITS + 16
private const val CHUNK = 64 * 1024

private class GzipOutput(private val delegate: OutputStream) : OutputStream() {
    private val stream = nativeHeap.alloc<z_stream>()
    private val out = ByteArray(CHUNK)
    private var closed = false

    init {
        val status = deflateInit2_(
            stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, GZIP_WINDOW_BITS, 8,
            Z_DEFAULT_STRATEGY, zlibVersion(), sizeOf<z_stream>().convert()
        )
        if (status != Z_OK) {
            nativeHeap.free(stream)
            throw IOException("deflateInit2 failed: $status")
        }
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "Stream closed" }
        if (len == 0) return
        b.usePinned { input ->
            stream.next_in = input.addressOf(off).reinterpret()
            stream.avail_in = len.convert()
            pump(Z_NO_FLUSH)
        }
    }

    private fun pump(flush: Int) {
        out.usePinned { output ->
            do {
                stream.next_out = output.addressOf(0).reinterpret()
                stream.avail_out = CHUNK.convert()
                val status = deflate(stream.ptr, flush)
                if (status != Z_OK && status != Z_STREAM_END && status != Z_BUF_ERROR) {
                    throw IOException("deflate failed: $status")
                }
                val produced = CHUNK - stream.avail_out.toInt()
                if (produced > 0) delegate.write(out, 0, produced)
                if (flush == Z_FINISH && status == Z_STREAM_END) break
            } while (stream.avail_out.toInt() == 0 || (flush == Z_FINISH))
        }
    }

    override fun flush() = delegate.flush()

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.next_in = null
            stream.avail_in = 0u
            pump(Z_FINISH)
            delegate.flush()
        } finally {
            deflateEnd(stream.ptr)
            nativeHeap.free(stream)
            delegate.close()
        }
    }
}

/** Handles concatenated gzip members, like GZIPInputStream. */
private class GzipInput(private val delegate: InputStream) : InputStream() {
    private val stream = nativeHeap.alloc<z_stream>()
    private val input = ByteArray(CHUNK)
    private var inputStart = 0
    private var inputEnd = 0
    private var finished = false
    private var closed = false

    init {
        val status = inflateInit2_(
            stream.ptr, GZIP_WINDOW_BITS, zlibVersion(), sizeOf<z_stream>().convert()
        )
        if (status != Z_OK) {
            nativeHeap.free(stream)
            throw IOException("inflateInit2 failed: $status")
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check(!closed) { "Stream closed" }
        if (len == 0) return 0
        while (!finished) {
            if (inputStart == inputEnd) {
                val read = delegate.read(input, 0, input.size)
                if (read < 0) throw IOException("Unexpected end of gzip stream")
                inputStart = 0
                inputEnd = read
                if (read == 0) continue
            }
            var produced = 0
            var status = Z_OK
            input.usePinned { src ->
                b.usePinned { dst ->
                    stream.next_in = src.addressOf(inputStart).reinterpret()
                    stream.avail_in = (inputEnd - inputStart).convert()
                    stream.next_out = dst.addressOf(off).reinterpret()
                    stream.avail_out = len.convert()
                    status = inflate(stream.ptr, Z_NO_FLUSH)
                    inputStart = inputEnd - stream.avail_in.toInt()
                    produced = len - stream.avail_out.toInt()
                }
            }
            when (status) {
                Z_OK, Z_BUF_ERROR -> Unit
                Z_STREAM_END -> if (!nextMember()) finished = true
                else -> throw IOException("Corrupt gzip data ($status)")
            }
            if (produced > 0) return produced
        }
        return -1
    }

    /** True when another gzip member follows; trailing bytes that are not gzip end the stream. */
    private fun nextMember(): Boolean {
        if (inputStart == inputEnd) {
            val read = delegate.read(input, 0, input.size)
            if (read <= 0) return false
            inputStart = 0
            inputEnd = read
        }
        if (input[inputStart] != 0x1f.toByte()) return false
        return inflateReset(stream.ptr) == Z_OK
    }

    override fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        nativeHeap.free(stream)
        delegate.close()
    }
}
