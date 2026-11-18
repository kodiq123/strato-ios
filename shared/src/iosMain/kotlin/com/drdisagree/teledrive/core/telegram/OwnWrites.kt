package com.drdisagree.teledrive.core.telegram

import platform.Foundation.NSLock

/**
 * iOS twin of the JVM OwnWrites: a caption counts as this session's only when it matches exactly,
 * so another device's edit still gets through. Bounded, oldest entries evicted first.
 */
class OwnWrites(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = NSLock()
    private val captions = LinkedHashMap<Long, String>()
    private val deletions = LinkedHashMap<Long, Unit>()

    fun wroteCaption(messageId: Long, caption: String) = locked {
        captions.remove(messageId)
        captions[messageId] = caption
        captions.trim()
    }

    fun deleted(messageIds: Collection<Long>) = locked {
        messageIds.forEach { deletions[it] = Unit }
        deletions.trim()
    }

    fun isOwnContent(messageId: Long, caption: String?): Boolean = locked {
        caption != null && captions[messageId] == caption
    }

    fun deletedElsewhere(messageIds: Collection<Long>): List<Long> = locked {
        messageIds.filter { it !in deletions }
    }

    private fun <V> LinkedHashMap<Long, V>.trim() {
        while (size > capacity) remove(keys.first())
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
