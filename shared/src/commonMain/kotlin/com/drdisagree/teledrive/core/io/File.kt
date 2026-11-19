package com.drdisagree.teledrive.core.io

/**
 * The subset of java.io.File the engine uses, so engine code can live in commonMain. On the JVM
 * targets this is java.io.File itself (typealias), so Android and desktop behaviour is unchanged;
 * iOS has its own implementation. Members keep Java's method names because a typealias actual can
 * only match real Java members; the property-style accessors below cover the Kotlin spelling.
 */
expect class File {
    constructor(pathname: String)
    constructor(parent: File?, child: String)

    fun getName(): String
    fun getPath(): String
    fun getAbsolutePath(): String
    fun getParentFile(): File?
    fun exists(): Boolean
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun canWrite(): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun setLastModified(time: Long): Boolean
    fun delete(): Boolean
    fun mkdirs(): Boolean
    fun renameTo(dest: File): Boolean
    fun listFiles(): Array<File>?
    fun createNewFile(): Boolean
    fun getUsableSpace(): Long
}

/** Opens the file for reading; FileInputStream on the JVM. */
expect fun File.inputStream(): InputStream

/** Opens the file for writing, truncating unless [append]; FileOutputStream on the JVM. */
expect fun File.outputStream(append: Boolean = false): OutputStream

/** Cuts or zero-extends the file to [size]; RandomAccessFile.setLength on the JVM. */
expect fun File.setLength(size: Long)

/** Forces written bytes to storage; FileDescriptor.sync on the JVM. Best effort. */
expect fun File.sync()

val File.name: String get() = getName()
val File.path: String get() = getPath()
val File.absolutePath: String get() = getAbsolutePath()
val File.parentFile: File? get() = getParentFile()
val File.isFile: Boolean get() = isFile()
val File.isDirectory: Boolean get() = isDirectory()
val File.usableSpace: Long get() = getUsableSpace()
val File.extension: String get() = getName().substringAfterLast('.', "")
val File.nameWithoutExtension: String get() = getName().substringBeforeLast('.')

fun File.resolve(child: String): File = File(this, child)

fun File.readBytes(): ByteArray = inputStream().use { it.readBytes() }

fun File.writeBytes(bytes: ByteArray) = outputStream().use { it.write(bytes) }

fun File.appendBytes(bytes: ByteArray) = outputStream(append = true).use { it.write(bytes) }

fun File.readText(): String = readBytes().decodeToString()

fun File.writeText(text: String) = writeBytes(text.encodeToByteArray())

/** Directories before their contents, like kotlin.io's walkTopDown. */
fun File.walkTopDown(): Sequence<File> = sequence {
    if (!exists()) return@sequence
    val pending = ArrayDeque<File>()
    pending.add(this@walkTopDown)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        yield(next)
        if (next.isDirectory()) {
            next.listFiles()?.sortedBy { it.getName() }?.let { pending.addAll(0, it) }
        }
    }
}

/** Contents before their directory; true when everything is gone, like kotlin.io's version. */
fun File.deleteRecursively(): Boolean {
    var ok = true
    if (isDirectory()) listFiles()?.forEach { if (!it.deleteRecursively()) ok = false }
    return (delete() || !exists()) && ok
}

/** Same contract as kotlin.io's File.copyTo for regular files. */
fun File.copyTo(target: File, overwrite: Boolean = false, bufferSize: Int = DEFAULT_BUFFER_SIZE): File {
    if (!exists()) throw IOException("The source file doesn't exist: ${getPath()}")
    if (target.exists() && (!overwrite || !target.delete())) {
        throw IOException("The destination file already exists: ${target.getPath()}")
    }
    target.getParentFile()?.mkdirs()
    inputStream().use { input -> target.outputStream().use { input.copyTo(it, bufferSize) } }
    return target
}

/** Path of this file relative to [base] with '/' separators, or null when it is not inside it. */
fun File.relativePathOrNull(base: File): String? {
    val root = base.getAbsolutePath().replace(BACKSLASH, '/').trimEnd('/')
    val self = getAbsolutePath().replace(BACKSLASH, '/')
    if (self == root) return ""
    return if (self.startsWith("$root/")) self.removePrefix("$root/") else null
}

private const val BACKSLASH = '\\'

/**
 * walkTopDown with kotlin.io's onEnter: a directory (the root included) for which [enter] is false
 * is neither returned nor descended into.
 */
fun File.walkFiltered(enter: (File) -> Boolean): Sequence<File> = sequence {
    if (!exists()) return@sequence
    val pending = ArrayDeque<File>()
    pending.add(this@walkFiltered)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (next.isDirectory()) {
            if (!enter(next)) continue
            yield(next)
            next.listFiles()?.sortedBy { it.getName() }?.let { pending.addAll(0, it) }
        } else {
            yield(next)
        }
    }
}
