package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.io.File
import com.drdisagree.teledrive.core.io.InputStream
import com.drdisagree.teledrive.core.io.Sha256
import com.drdisagree.teledrive.core.io.inputStream
import com.drdisagree.teledrive.core.io.toHex

object Hashing {

    fun sha256(file: File): String? = sha256(file.inputStream())

    fun sha256(stream: InputStream): String? = runCatching {
        val digest = Sha256()
        stream.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer, 0, buffer.size)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }.getOrNull()
}
