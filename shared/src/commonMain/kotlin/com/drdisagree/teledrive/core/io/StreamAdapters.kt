package com.drdisagree.teledrive.core.io

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/** BufferedInputStream on the JVM. */
expect fun InputStream.buffered(bufferSize: Int = DEFAULT_BUFFER_SIZE): InputStream

/** BufferedOutputStream on the JVM. */
expect fun OutputStream.buffered(bufferSize: Int = DEFAULT_BUFFER_SIZE): OutputStream

/** gzip compression (GZIPOutputStream on the JVM, zlib on iOS); standard RFC 1952 framing. */
expect fun gzipOutputStream(target: OutputStream): OutputStream

/** gzip decompression (GZIPInputStream on the JVM, zlib on iOS). */
expect fun gzipInputStream(source: InputStream): InputStream

/** kotlinx-io view of a stream, like the JVM-only asSource(); closing it closes the stream. */
fun InputStream.asKxSource(): RawSource = object : RawSource {
    private val chunk = ByteArray(DEFAULT_BUFFER_SIZE)

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (byteCount == 0L) return 0L
        val wanted = minOf(byteCount, chunk.size.toLong()).toInt()
        val read = this@asKxSource.read(chunk, 0, wanted)
        if (read < 0) return -1L
        sink.write(chunk, 0, read)
        return read.toLong()
    }

    override fun close() = this@asKxSource.close()
}

/** kotlinx-io view of a stream, like the JVM-only asSink(); closing it closes the stream. */
fun OutputStream.asKxSink(): RawSink = object : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val bytes = source.readByteArray(minOf(remaining, DEFAULT_BUFFER_SIZE.toLong()).toInt())
            this@asKxSink.write(bytes, 0, bytes.size)
            remaining -= bytes.size
        }
    }

    override fun flush() = this@asKxSink.flush()

    override fun close() = this@asKxSink.close()
}
