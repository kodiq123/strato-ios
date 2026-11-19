package com.drdisagree.teledrive.core.telegram

import com.drdisagree.teledrive.core.common.Lock
import com.drdisagree.teledrive.core.common.withLock

/**
 * A caption counts as this session's only when it matches exactly, so another device's edit still
 * gets through. Bounded: the oldest entries are evicted first.
 */
class OwnWrites(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Lock()
    private val captions = LinkedHashMap<Long, String>()
    private val deletions = LinkedHashMap<Long, Unit>()

    fun wroteCaption(messageId: Long, caption: String) = lock.withLock {
        captions.remove(messageId)
        captions[messageId] = caption
        captions.trim()
    }

    fun deleted(messageIds: Collection<Long>) = lock.withLock {
        messageIds.forEach { deletions[it] = Unit }
        deletions.trim()
    }

    fun isOwnContent(messageId: Long, caption: String?): Boolean = lock.withLock {
        caption != null && captions[messageId] == caption
    }

    fun deletedElsewhere(messageIds: Collection<Long>): List<Long> = lock.withLock {
        messageIds.filter { it !in deletions }
    }

    /** Insertion order is iteration order, so the first keys are the eldest. */
    private fun <V> LinkedHashMap<Long, V>.trim() {
        val excess = size - capacity
        if (excess <= 0) return
        keys.take(excess).forEach { remove(it) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 4096
    }
}
