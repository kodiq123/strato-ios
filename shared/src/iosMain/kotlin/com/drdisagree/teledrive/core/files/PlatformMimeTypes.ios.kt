package com.drdisagree.teledrive.core.files

import platform.UniformTypeIdentifiers.UTType

internal actual fun platformMimeTypeFromExtension(extension: String): String? =
    UTType.typeWithFilenameExtension(extension)?.preferredMIMEType
