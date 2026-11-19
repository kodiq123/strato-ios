@file:OptIn(ExperimentalForeignApi::class)

package com.drdisagree.teledrive.core.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.posix.EOF
import platform.posix.FILE
import platform.posix.F_OK
import platform.posix.W_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgetc
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fputc
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fsync
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.strerror
import platform.posix.truncate
import kotlinx.cinterop.toKString
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.readBytes
import platform.posix.SEEK_CUR
import platform.posix.SEEK_END

/** java.io.File semantics over Foundation and POSIX, for the paths the engine uses. */
actual class File actual constructor(pathname: String) {

    private val path: String = normalize(pathname)

    actual constructor(parent: File?, child: String) : this(
        when {
            parent == null -> child
            parent.path.isEmpty() -> "/$child"
            parent.path.endsWith("/") -> parent.path + child
            else -> parent.path + "/" + child
        }
    )

    private val fm get() = NSFileManager.defaultManager

    actual fun getName(): String = path.substringAfterLast('/')

    actual fun getPath(): String = path

    actual fun getAbsolutePath(): String =
        if (path.startsWith("/")) path else normalize(fm.currentDirectoryPath + "/" + path)

    actual fun getParentFile(): File? {
        val index = path.lastIndexOf('/')
        return when {
            index > 0 -> File(path.substring(0, index))
            index == 0 && path.length > 1 -> File("/")
            else -> null
        }
    }

    actual fun exists(): Boolean = access(path, F_OK) == 0

    actual fun isFile(): Boolean = kind() == Kind.FILE

    actual fun isDirectory(): Boolean = kind() == Kind.DIRECTORY

    actual fun canWrite(): Boolean = access(path, W_OK) == 0

    actual fun length(): Long = if (isFile()) {
        (fm.attributesOfItemAtPath(path, null)?.get(platform.Foundation.NSFileSize) as? NSNumber)
            ?.longLongValue ?: 0L
    } else 0L

    actual fun lastModified(): Long {
        val date = fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate
            ?: return 0L
        return (date.timeIntervalSince1970 * 1000).toLong()
    }

    actual fun setLastModified(time: Long): Boolean = fm.setAttributes(
        mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(time / 1000.0)),
        ofItemAtPath = path,
        error = null
    )

    /** Like Java: files and empty directories only. */
    actual fun delete(): Boolean {
        if (!exists()) return false
        if (isDirectory() && !listFiles().isNullOrEmpty()) return false
        return fm.removeItemAtPath(path, null)
    }

    actual fun mkdirs(): Boolean =
        !exists() && fm.createDirectoryAtPath(path, true, null, null)

    /** rename(2), which replaces an existing destination file, as java.io.File does on Unix. */
    actual fun renameTo(dest: File): Boolean = rename(path, dest.path) == 0

    actual fun listFiles(): Array<File>? {
        if (!isDirectory()) return null
        val names = fm.contentsOfDirectoryAtPath(path, null) ?: return null
        return names.map { File(this, it as String) }.toTypedArray()
    }

    actual fun createNewFile(): Boolean {
        if (exists()) return false
        return fm.createFileAtPath(path, null, null)
    }

    actual fun getUsableSpace(): Long =
        (fm.attributesOfFileSystemForPath(path, null)?.get(NSFileSystemFreeSize) as? NSNumber)
            ?.longLongValue ?: 0L

    override fun equals(other: Any?): Boolean = other is File && other.path == path

    override fun hashCode(): Int = path.hashCode() xor 1234321

    override fun toString(): String = path

    private enum class Kind { FILE, DIRECTORY, OTHER, MISSING }

    private fun kind(): Kind {
        val type = fm.attributesOfItemAtPath(path, null)?.get(platform.Foundation.NSFileType)
            ?: return Kind.MISSING
        return when (type) {
            platform.Foundation.NSFileTypeRegular -> Kind.FILE
            platform.Foundation.NSFileTypeDirectory -> Kind.DIRECTORY
            platform.Foundation.NSFileTypeSymbolicLink -> {
                val target = fm.destinationOfSymbolicLinkAtPath(path, null)
                    ?.let { if (it.startsWith("/")) it else (getParentFile()?.path ?: "") + "/" + it }
                    ?: return Kind.OTHER
                File(target).kind()
            }
            else -> Kind.OTHER
        }
    }

    private companion object {
        fun normalize(raw: String): String {
            if (raw.isEmpty()) return raw
            val collapsed = raw.replace(Regex("/+"), "/")
            return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
        }
    }
}

actual abstract class InputStream actual constructor() : AutoCloseable {
    actual abstract fun read(): Int

    actual open fun read(b: ByteArray): Int = read(b, 0, b.size)

    actual open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var count = 0
        while (count < len) {
            val value = read()
            if (value < 0) return if (count == 0) -1 else count
            b[off + count] = value.toByte()
            count++
        }
        return count
    }

    actual open fun skip(n: Long): Long {
        var skipped = 0L
        while (skipped < n && read() >= 0) skipped++
        return skipped
    }

    actual open fun available(): Int = 0

    actual override fun close() {}
}

actual abstract class OutputStream actual constructor() : AutoCloseable {
    actual abstract fun write(b: Int)

    actual open fun write(b: ByteArray) = write(b, 0, b.size)

    actual open fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(b[i].toInt())
    }

    actual open fun flush() {}

    actual override fun close() {}
}

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}


actual fun File.inputStream(): InputStream = PosixInputStream(open(getPath(), "rb"))

actual fun File.outputStream(append: Boolean): OutputStream =
    PosixOutputStream(open(getPath(), if (append) "ab" else "wb"))

actual fun File.setLength(size: Long) {
    if (!exists()) createNewFile()
    if (truncate(getPath(), size) != 0) throw IOException(lastError("truncate ${getPath()}"))
}

actual fun File.sync() {
    val handle = fopen(getPath(), "r+b") ?: return
    fsync(fileno(handle))
    fclose(handle)
}

private fun open(path: String, mode: String): CPointer<FILE> =
    fopen(path, mode) ?: throw IOException(lastError("$path (open $mode)"))

private fun lastError(what: String): String = "$what: ${strerror(errno)?.toKString()}"

private class PosixInputStream(private var handle: CPointer<FILE>?) : InputStream() {

    private fun file(): CPointer<FILE> = handle ?: throw IOException("Stream closed")

    override fun read(): Int {
        val value = fgetc(file())
        return if (value == EOF) -1 else value and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = b.usePinned { fread(it.addressOf(off), 1u, len.convert(), file()).toInt() }
        return if (count == 0) -1 else count
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val file = file()
        val start = ftello(file)
        fseeko(file, 0, SEEK_END)
        val end = ftello(file)
        val target = minOf(start + n, end)
        fseeko(file, target, platform.posix.SEEK_SET)
        return target - start
    }

    override fun available(): Int {
        val file = handle ?: return 0
        val position = ftello(file)
        fseeko(file, 0, SEEK_END)
        val end = ftello(file)
        fseeko(file, position, platform.posix.SEEK_SET)
        return (end - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun close() {
        handle?.let { fclose(it) }
        handle = null
    }
}

private class PosixOutputStream(private var handle: CPointer<FILE>?) : OutputStream() {

    private fun file(): CPointer<FILE> = handle ?: throw IOException("Stream closed")

    override fun write(b: Int) {
        if (fputc(b and 0xFF, file()) == EOF) throw IOException(lastError("write"))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val written = b.usePinned { fwrite(it.addressOf(off), 1u, len.convert(), file()).toInt() }
        if (written != len) throw IOException(lastError("write"))
    }

    override fun flush() {
        fflush(file())
    }

    override fun close() {
        handle?.let {
            fflush(it)
            fclose(it)
        }
        handle = null
    }
}

actual class Sha256 actual constructor() {
    // The context lives in native memory, freed by digest(); an abandoned hasher leaks ~100 bytes.
    private val arena = kotlinx.cinterop.nativeHeap
    private val ctx = arena.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }
    private var finished = false

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "digest() already called" }
        if (length == 0) return
        bytes.usePinned { CC_SHA256_Update(ctx.ptr, it.addressOf(offset), length.convert()) }
    }

    actual fun digest(): ByteArray {
        check(!finished) { "digest() already called" }
        finished = true
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.asUByteArray().usePinned { CC_SHA256_Final(it.addressOf(0), ctx.ptr) }
        arena.free(ctx.rawPtr)
        return out
    }
}
