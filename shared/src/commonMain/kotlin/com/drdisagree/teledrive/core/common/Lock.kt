package com.drdisagree.teledrive.core.common

/** Reentrant mutual exclusion for blocking code: ReentrantLock on the JVM, NSRecursiveLock on iOS. */
expect class Lock() {
    fun lock()
    fun unlock()
}

/** Replaces `synchronized` / `@Synchronized` in common code. */
inline fun <T> Lock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
