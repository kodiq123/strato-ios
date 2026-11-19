package com.drdisagree.teledrive.core.common

import kotlin.time.TimeSource

/** Wall-clock milliseconds since the Unix epoch, like System.currentTimeMillis on the JVM. */
expect fun nowMillis(): Long

private val processStart = TimeSource.Monotonic.markNow()

/** Monotonic milliseconds with an arbitrary origin (System.nanoTime / 1e6); for intervals only. */
fun monotonicMillis(): Long = processStart.elapsedNow().inWholeMilliseconds
