package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.io.*
import com.drdisagree.teledrive.core.io.File

interface MediaMetadataExtractor {

    fun extract(file: File, mimeType: String): MediaInfo
}
