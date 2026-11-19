package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.io.File

internal actual fun listZipEntries(path: String): List<ArchiveEntry> =
    ZipCentralDirectory.read(File(path))
