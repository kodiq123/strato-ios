package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.io.File

expect object ApkIconExtractor {
    /** The icon of the APK at [file] as encoded image bytes, or null when none can be read. */
    fun extractIconBytes(file: File): ByteArray?
}
