package com.drdisagree.teledrive.core.io

import java.io.RandomAccessFile
import java.security.MessageDigest

actual typealias File = java.io.File
actual typealias InputStream = java.io.InputStream
actual typealias OutputStream = java.io.OutputStream
actual typealias IOException = java.io.IOException

actual fun File.inputStream(): InputStream = java.io.FileInputStream(this)

actual fun File.outputStream(append: Boolean): OutputStream = java.io.FileOutputStream(this, append)

actual fun File.setLength(size: Long) {
    RandomAccessFile(this, "rw").use { it.setLength(size) }
}

actual fun File.sync() {
    runCatching { RandomAccessFile(this, "rw").use { it.fd.sync() } }
}

actual class Sha256 actual constructor() {
    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun update(bytes: ByteArray, offset: Int, length: Int) =
        digest.update(bytes, offset, length)

    actual fun digest(): ByteArray = digest.digest()
}

actual fun InputStream.buffered(bufferSize: Int): InputStream =
    java.io.BufferedInputStream(this, bufferSize)

actual fun OutputStream.buffered(bufferSize: Int): OutputStream =
    java.io.BufferedOutputStream(this, bufferSize)

actual fun gzipOutputStream(target: OutputStream): OutputStream = java.util.zip.GZIPOutputStream(target)

actual fun gzipInputStream(source: InputStream): InputStream = java.util.zip.GZIPInputStream(source)
