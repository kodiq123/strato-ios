package com.drdisagree.teledrive.presentation.preview

import net.lingala.zip4j.ZipFile

internal actual fun listZipEntries(path: String): List<ArchiveEntry> =
    ZipFile(path).fileHeaders.map { header ->
        ArchiveEntry(
            name = header.fileName,
            sizeBytes = header.uncompressedSize,
            compressedBytes = header.compressedSize,
            isDirectory = header.isDirectory
        )
    }
