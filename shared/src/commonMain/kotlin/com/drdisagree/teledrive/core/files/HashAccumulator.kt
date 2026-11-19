package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.io.DigestingInputStream
import com.drdisagree.teledrive.core.io.InputStream
import com.drdisagree.teledrive.core.io.Sha256
import com.drdisagree.teledrive.core.io.toHex

/**
 * Hashes bytes already being streamed elsewhere, so the file is not read twice; feed chunks in file
 * order.
 */
class HashAccumulator {

    private val digest = Sha256()
    private var abandoned = false

    fun wrap(input: InputStream): InputStream = DigestingInputStream(input, digest)

    fun abandon() {
        abandoned = true
    }

    fun result(): String? = if (abandoned) null else digest.digest().toHex()
}
