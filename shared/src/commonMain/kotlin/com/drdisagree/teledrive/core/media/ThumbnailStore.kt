package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.io.*
import com.drdisagree.teledrive.core.io.File

interface ThumbnailStore {

    suspend fun thumbnailBytes(fileId: String): ByteArray?

    suspend fun uploadThumbnailFile(fileId: String): File?

    suspend fun storeFromDecrypted(fileId: String, plain: File)

    suspend fun storeFromStream(fileId: String, parts: List<MediaPart>, encrypted: Boolean)
}
