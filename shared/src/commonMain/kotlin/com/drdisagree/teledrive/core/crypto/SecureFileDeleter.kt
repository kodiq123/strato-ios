package com.drdisagree.teledrive.core.crypto

import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.isFile
import com.drdisagree.teledrive.core.io.outputStream
import com.drdisagree.teledrive.core.io.sync

/** Best effort: wear leveling on flash storage can keep the old bytes physically. */
class SecureFileDeleter {

    fun delete(file: File): Boolean {
        if (!file.exists()) return true
        runCatching {
            if (file.isFile && file.canWrite()) {
                file.outputStream().use { output ->
                    var remaining = file.length()
                    while (remaining > 0) {
                        val toWrite = minOf(remaining, OVERWRITE_BUFFER.toLong()).toInt()
                        output.write(secureRandomBytes(toWrite), 0, toWrite)
                        remaining -= toWrite
                    }
                }
                file.sync()
            }
        }
        return file.delete()
    }

    companion object {
        private const val OVERWRITE_BUFFER = 64 * 1024
    }
}
