package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.IOException
import com.drdisagree.teledrive.core.io.inputStream
import com.drdisagree.teledrive.core.io.readFully

/** The entries of the ZIP archive at [path]; zip4j on the JVM, [ZipCentralDirectory] elsewhere. */
internal expect fun listZipEntries(path: String): List<ArchiveEntry>

/**
 * Lists a ZIP archive from its central directory alone, so nothing is decompressed and the cost
 * does not grow with the archive's size. Reads ZIP64 archives. Names use UTF-8 when the entry's
 * language-encoding flag says so and CP437 otherwise, the same rule zip4j applies.
 */
internal object ZipCentralDirectory {

    fun read(file: File): List<ArchiveEntry> {
        val length = file.length()
        val tailSize = minOf(length, (EOCD_SIZE + MAX_COMMENT + ZIP64_LOCATOR_SIZE).toLong()).toInt()
        if (tailSize < EOCD_SIZE) throw IOException("Not a ZIP archive")
        val tail = readRange(file, length - tailSize, tailSize)
        val eocd = (tailSize - EOCD_SIZE downTo 0).firstOrNull { tail.int32(it) == EOCD_SIG }
            ?: throw IOException("Not a ZIP archive")

        var entryCount = tail.uint16(eocd + 10).toLong()
        var directorySize = tail.uint32(eocd + 12)
        var directoryOffset = tail.uint32(eocd + 16)
        val locator = eocd - ZIP64_LOCATOR_SIZE
        if (locator >= 0 && tail.int32(locator) == ZIP64_LOCATOR_SIG) {
            val record = readRange(file, tail.int64(locator + 8), ZIP64_EOCD_SIZE)
            if (record.int32(0) != ZIP64_EOCD_SIG) throw IOException("Broken ZIP64 record")
            entryCount = record.int64(32)
            directorySize = record.int64(40)
            directoryOffset = record.int64(48)
        }
        if (directoryOffset < 0 || directorySize < 0 ||
            directoryOffset + directorySize > length || directorySize > Int.MAX_VALUE
        ) {
            throw IOException("Broken central directory")
        }

        val directory = readRange(file, directoryOffset, directorySize.toInt())
        val entries = ArrayList<ArchiveEntry>(entryCount.coerceAtMost(MAX_PREALLOCATED).toInt())
        var position = 0
        for (index in 0 until entryCount) {
            if (position + CENTRAL_HEADER_SIZE > directory.size ||
                directory.int32(position) != CENTRAL_HEADER_SIG
            ) {
                throw IOException("Broken central directory entry $index")
            }
            val flags = directory.uint16(position + 8)
            var compressed = directory.uint32(position + 20)
            var uncompressed = directory.uint32(position + 24)
            val nameLength = directory.uint16(position + 28)
            val extraLength = directory.uint16(position + 30)
            val commentLength = directory.uint16(position + 32)
            val nameStart = position + CENTRAL_HEADER_SIZE
            val extraStart = nameStart + nameLength
            val next = extraStart + extraLength + commentLength
            if (next > directory.size) throw IOException("Broken central directory entry $index")

            val nameBytes = directory.copyOfRange(nameStart, extraStart)
            val name = if (flags and UTF8_FLAG != 0) nameBytes.decodeToString() else nameBytes.decodeCp437()

            // ZIP64 extra field: only the sizes that overflowed the 32-bit fields are present, in
            // this order.
            var extra = extraStart
            while (extra + 4 <= extraStart + extraLength) {
                val id = directory.uint16(extra)
                val size = directory.uint16(extra + 2)
                if (id == ZIP64_EXTRA_ID) {
                    var field = extra + 4
                    if (uncompressed == UINT32_MAX && field + 8 <= extra + 4 + size) {
                        uncompressed = directory.int64(field)
                        field += 8
                    }
                    if (compressed == UINT32_MAX && field + 8 <= extra + 4 + size) {
                        compressed = directory.int64(field)
                    }
                    break
                }
                extra += 4 + size
            }

            entries += ArchiveEntry(
                name = name,
                sizeBytes = uncompressed,
                compressedBytes = compressed,
                isDirectory = name.endsWith("/") || name.endsWith("\\")
            )
            position = next
        }
        return entries
    }

    private fun readRange(file: File, offset: Long, size: Int): ByteArray {
        if (offset < 0) throw IOException("Offset outside the file")
        val buffer = ByteArray(size)
        file.inputStream().use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val step = stream.skip(offset - skipped)
                if (step <= 0) throw IOException("Offset outside the file")
                skipped += step
            }
            if (stream.readFully(buffer) != size) throw IOException("Truncated ZIP archive")
        }
        return buffer
    }

    private fun ByteArray.uint16(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.int32(at: Int): Int =
        uint16(at) or (uint16(at + 2) shl 16)

    private fun ByteArray.uint32(at: Int): Long = int32(at).toLong() and 0xFFFFFFFFL

    private fun ByteArray.int64(at: Int): Long = uint32(at) or (uint32(at + 4) shl 32)

    private fun ByteArray.decodeCp437(): String = buildString(size) {
        for (byte in this@decodeCp437) {
            val code = byte.toInt() and 0xFF
            append(if (code < 0x80) code.toChar() else CP437_HIGH[code - 0x80])
        }
    }

    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CENTRAL_HEADER_SIG = 0x02014b50
    private const val EOCD_SIZE = 22
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_EOCD_SIZE = 56
    private const val CENTRAL_HEADER_SIZE = 46
    private const val MAX_COMMENT = 0xFFFF
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val UTF8_FLAG = 1 shl 11
    private const val UINT32_MAX = 0xFFFFFFFFL
    private const val MAX_PREALLOCATED = 4096L

    /** Code page 437, bytes 0x80 to 0xFF. */
    private const val CP437_HIGH =
        "ÇüéâäàåçêëèïîìÄÅ" +
            "ÉæÆôöòûùÿÖÜ¢£¥₧ƒ" +
            "áíóúñÑªº¿⌐¬½¼¡«»" +
            "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
            "└┴┬├─┼╞╟╚╔╩╦╠═╬╧" +
            "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
            "αßΓπΣσµτΦΘΩδ∞φε∩" +
            "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "
}
