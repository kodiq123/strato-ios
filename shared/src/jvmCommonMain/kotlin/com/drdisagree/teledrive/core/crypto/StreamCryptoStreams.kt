package com.drdisagree.teledrive.core.crypto

import java.io.InputStream
import java.io.OutputStream
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * java.io overloads for the JVM callers (Android, desktop), so they keep their streams while the
 * format lives in common code. Neither stream is closed here, as before.
 */
fun StreamCrypto.encryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
    val sink = output.asSink().buffered()
    encryptStream(key, input.asSource().buffered(), sink)
    sink.flush()
}

fun StreamCrypto.decryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
    val sink = output.asSink().buffered()
    decryptStream(key, input.asSource().buffered(), sink)
    sink.flush()
}
