package com.drdisagree.teledrive.core.files

import com.drdisagree.teledrive.core.io.*
import com.drdisagree.teledrive.core.io.InputStream
import com.drdisagree.teledrive.core.io.OutputStream

interface PartialDownload {

    val token: String

    fun length(): Long

    fun truncate(size: Long)

    fun append(body: (OutputStream) -> Unit)

    fun read(): InputStream

    fun finish(): String?

    fun discard()
}
