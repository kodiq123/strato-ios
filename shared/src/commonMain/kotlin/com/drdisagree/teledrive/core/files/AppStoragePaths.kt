package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.io.*
import com.drdisagree.teledrive.core.io.File

interface AppStoragePaths {

    val filesDir: File

    val cacheDir: File

    val externalCacheDir: File?

    val externalStorageRoot: File?
}
