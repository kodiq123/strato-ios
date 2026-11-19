package com.drdisagree.teledrive.presentation.preview

import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The iOS ZIP preview reads the central directory itself; zip4j, which Android and desktop use,
 * is the reference it must agree with.
 */
class ZipCentralDirectoryTest {

    private val root = Files.createTempDirectory("zip-listing").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `matches zip4j for UTF-8 names, folders, stored and deflated entries`() {
        val zip = File(root, "mixed.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.setComment("archive comment that sits after the central directory")
            out.putNextEntry(ZipEntry("Fotos/"))
            out.closeEntry()
            out.putNextEntry(ZipEntry("Fotos/férias 2026 – 日本.txt"))
            out.write("texto repetido ".repeat(500).toByteArray())
            out.closeEntry()
            val stored = "stored bytes".toByteArray()
            out.putNextEntry(ZipEntry("raw.bin").apply {
                method = ZipEntry.STORED
                size = stored.size.toLong()
                compressedSize = stored.size.toLong()
                crc = CRC32().apply { update(stored) }.value
            })
            out.write(stored)
            out.closeEntry()
            out.putNextEntry(ZipEntry("empty.txt"))
            out.closeEntry()
        }

        assertMatchesZip4j(zip)
    }

    @Test
    fun `decodes names without the UTF-8 flag as CP437, like zip4j`() {
        val zip = File(root, "cp437.zip")
        ZipOutputStream(zip.outputStream(), charset("Cp437")).use { out ->
            out.putNextEntry(ZipEntry("Ärger ½ ░▒▓ café.txt"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
        }

        val entries = ZipCentralDirectory.read(zip)
        assertEquals("Ärger ½ ░▒▓ café.txt", entries.single().name)
        assertMatchesZip4j(zip)
    }

    @Test
    fun `reads ZIP64 archives with more than 65535 entries`() {
        val zip = File(root, "zip64.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            repeat(ZIP64_ENTRY_COUNT) { index ->
                out.putNextEntry(ZipEntry("e$index"))
                out.closeEntry()
            }
        }

        val entries = ZipCentralDirectory.read(zip)
        assertEquals(ZIP64_ENTRY_COUNT, entries.size)
        assertEquals("e${ZIP64_ENTRY_COUNT - 1}", entries.last().name)
        assertMatchesZip4j(zip)
    }

    @Test
    fun `rejects files that are not ZIP archives`() {
        val notZip = File(root, "plain.txt").apply { writeText("not a zip") }
        assertTrue(runCatching { ZipCentralDirectory.read(notZip) }.isFailure)
    }

    @Test
    fun `CP437 table covers every high byte`() {
        val all = File(root, "all.zip")
        val name = ByteArray(128) { (0x80 + it).toByte() }
        // A raw central directory name with every byte from 0x80 to 0xFF.
        ZipOutputStream(all.outputStream(), charset("Cp437")).use { out ->
            out.putNextEntry(ZipEntry(String(name, charset("Cp437"))))
            out.closeEntry()
        }
        assertEquals(String(name, charset("Cp437")), ZipCentralDirectory.read(all).single().name)
    }

    private fun assertMatchesZip4j(zip: File) {
        assertEquals(listZipEntries(zip.absolutePath), ZipCentralDirectory.read(zip))
    }

    private companion object {
        const val ZIP64_ENTRY_COUNT = 70_000
    }
}
