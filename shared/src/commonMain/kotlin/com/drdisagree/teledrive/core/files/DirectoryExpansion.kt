package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.io.*
import com.drdisagree.teledrive.core.io.File

/**
 * Paths stay relative to the directory's parent, so the picked folder is the first segment; null
 * when unreadable.
 */
fun expandDirectory(reference: String): List<ImportSource>? {
    val root = runCatching { File(reference) }.getOrNull() ?: return null
    if (!root.isDirectory) return null
    val base = root.parentFile ?: return null
    return root.walkTopDown()
        .filter { it.isFile && it.length() > 0 }
        .map { file ->
            ImportSource(
                reference = file.absolutePath,
                relativeFolder = file.parentFile
                    ?.relativePathOrNull(base)
                    .orEmpty()
            )
        }
        .toList()
}
