package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.io.File

/** iOS has no zip reader in the platform SDK, so APK previews fall back to the generic file icon. */
actual object ApkIconExtractor {
    actual fun extractIconBytes(file: File): ByteArray? = null
}
