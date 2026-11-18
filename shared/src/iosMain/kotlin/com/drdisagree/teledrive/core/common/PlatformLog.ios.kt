package com.drdisagree.teledrive.core.common

import platform.Foundation.NSLog

// NSLog treats its first argument as a format string, so the text always goes through "%@".

internal actual fun logDebug(tag: String, message: String) {
    NSLog("%@", "D/$tag: $message")
}

internal actual fun logWarn(tag: String, message: String, throwable: Throwable?) {
    NSLog("%@", "W/$tag: $message" + throwable.describe())
}

internal actual fun logError(tag: String, message: String, throwable: Throwable?) {
    NSLog("%@", "E/$tag: $message" + throwable.describe())
}

private fun Throwable?.describe(): String =
    if (this == null) "" else "\n" + stackTraceToString()
