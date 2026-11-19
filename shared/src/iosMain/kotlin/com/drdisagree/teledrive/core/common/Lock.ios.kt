package com.drdisagree.teledrive.core.common

import platform.Foundation.NSRecursiveLock

actual class Lock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
