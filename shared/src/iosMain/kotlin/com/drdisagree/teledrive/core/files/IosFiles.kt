package com.drdisagree.teledrive.core.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/** Small Foundation-backed file helpers for iOS-only code. */
@OptIn(ExperimentalForeignApi::class)
object IosFiles {

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun readBytes(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

    fun deleteRecursively(path: String): Boolean =
        !exists(path) || NSFileManager.defaultManager.removeItemAtPath(path, null)

    fun createDirectories(path: String): Boolean =
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)

    fun NSData.toByteArray(): ByteArray {
        val size = length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
        }
        return bytes
    }
}
