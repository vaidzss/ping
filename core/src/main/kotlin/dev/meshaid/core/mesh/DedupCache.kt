package dev.meshaid.core.mesh

import dev.meshaid.core.protocol.MessageId

/**
 * LRU dedup cache with time expiry (BitChat-proven sizing: 1000 entries, 5 minutes).
 * Replayed/flooded duplicates of a message must never be delivered or relayed twice.
 */
class DedupCache(
    private val clock: () -> Long,
    private val maxEntries: Int = 1000,
    private val expiryMs: Long = 5 * 60 * 1000L,
) {
    private val entries = LinkedHashMap<MessageId, Long>()

    /** Returns true if the id was new (and records it); false if it is a duplicate. */
    @Synchronized
    fun checkAndRecord(id: MessageId): Boolean {
        val now = clock()
        expunge(now)
        if (entries.containsKey(id)) return false
        entries[id] = now
        while (entries.size > maxEntries) {
            val eldest = entries.keys.first()
            entries.remove(eldest)
        }
        return true
    }

    @Synchronized
    fun size(): Int {
        expunge(clock())
        return entries.size
    }

    private fun expunge(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value >= expiryMs) it.remove() else break // insertion order => oldest first
        }
    }
}
