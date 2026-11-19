package com.drdisagree.teledrive.core.crypto

import com.drdisagree.teledrive.core.io.InputStream
import com.drdisagree.teledrive.core.io.OutputStream
import com.drdisagree.teledrive.core.io.asKxSink
import com.drdisagree.teledrive.core.io.asKxSource
import kotlinx.io.buffered

/**
 * Stream overloads (java.io streams on the JVM), so callers keep their streams while the format
 * lives over kotlinx-io. Neither stream is closed here, as before.
 */
fun StreamCrypto.encryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
    val sink = output.asKxSink().buffered()
    encryptStream(key, input.asKxSource().buffered(), sink)
    sink.flush()
}

fun StreamCrypto.decryptStream(key: ByteArray, input: InputStream, output: OutputStream) {
    val sink = output.asKxSink().buffered()
    decryptStream(key, input.asKxSource().buffered(), sink)
    sink.flush()
}
